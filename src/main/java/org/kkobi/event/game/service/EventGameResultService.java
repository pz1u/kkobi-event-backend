package org.kkobi.event.game.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.enums.PersonaType;
import org.kkobi.assessment.mapper.AssessmentMapper;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.calculator.EventExtractionResult;
import org.kkobi.event.game.calculator.EventPersonaFeatureExtractor;
import org.kkobi.event.game.calculator.EventPersonaScoreCalculator;
import org.kkobi.event.game.domain.EventGameClock;
import org.kkobi.event.game.domain.EventGameResult;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.dto.EventActionLogDto;
import org.kkobi.event.game.dto.response.EventGameResultResponse;
import org.kkobi.event.game.exception.EventNotFinishedException;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameResultMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.service.EventSessionService;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;
import org.kkobi.persona.dto.PersonaResponseDto;
import org.kkobi.persona.mapper.PersonaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 행사 참가자의 게임 종료 결과(최종 자산/수익률/성향)를 서버에서 최초 1회 확정한다.
// event_game_states + SC001 종료 시점 가격으로 최종 자산을 계산하고,
// 성향 점수는 이벤트 전용 EventPersonaFeatureExtractor/EventPersonaScoreCalculator로 산출한다.
// 강제 초기 배분(현금 100%)은 참가자의 선택이 아니므로 성향 점수에서 완전히 제외했다.
// 회원용 GameBehaviorAssessmentCalculator/GameScoreCalculator는 이 흐름과 무관하게 유지된다.
// 주의: 행동 로그의 rt/lh/rp delta 합계는 최종 점수와 일치하지 않는다(분석 참고용 데이터).
@Service
@RequiredArgsConstructor
public class EventGameResultService {

    private static final Logger log = LoggerFactory.getLogger(EventGameResultService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int RETURN_RATE_SCALE = 2;
    // 로그 재구성 수량 허용 오차 (StockQuantityPolicy scale=8 반올림 잔여 방어)
    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.000001");

    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionService eventSessionService;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventActionLogMapper eventActionLogMapper;
    private final EventGameResultMapper eventGameResultMapper;
    private final ScenarioService scenarioService;
    private final EventGameClockService eventGameClockService;
    private final EventPersonaFeatureExtractor eventPersonaFeatureExtractor;
    private final EventPersonaScoreCalculator eventPersonaScoreCalculator;
    private final PersonaClassifier personaClassifier;
    private final AssessmentMapper assessmentMapper;
    private final PersonaMapper personaMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventGameResultResponse getOrCreateResult(String participantToken) {
        return getOrCreateResult(participantToken, LocalDateTime.now(KST));
    }

    EventGameResultResponse getOrCreateResult(String participantToken, LocalDateTime now) {
        EventParticipant participant = findParticipant(participantToken);

        EventGameResult existing = eventGameResultMapper.findByParticipantId(participant.getParticipantId());
        if (existing != null) {
            return buildResponse(participant, existing);
        }

        EventSession session = eventSessionService.getSynchronizedSession(participant.getSessionId(), now);
        validateFinished(session);

        // 참가자 결과 요청과 리더보드의 전원 결과 확정이 동시에 들어와도
        // 같은 참가자의 결과 계산은 한 트랜잭션만 수행하도록 게임 상태 행을 잠근다.
        EventGameState gameState = eventGameStateMapper.findByParticipantIdForUpdate(
                participant.getParticipantId());
        if (gameState == null) {
            throw new EventNotStartedException("게임 진행 기록이 없습니다.");
        }

        // 잠금을 기다리는 동안 다른 트랜잭션이 결과를 확정했을 수 있으므로 재조회한다.
        existing = eventGameResultMapper.findByParticipantId(participant.getParticipantId());
        if (existing != null) {
            return buildResponse(participant, existing);
        }

        EventGameResult result = calculateAndSaveResult(session, gameState);
        return buildResponse(participant, result);
    }

    // 이미 결과가 있으면 그대로 반환하고, 없으면 최초 1회 확정해 저장한다.
    // 리더보드(EventLeaderboardService)가 결과 화면을 열지 않은 참가자의 결과를
    // 조회 전에 일괄 확정할 때 이 계산 로직을 그대로 재사용한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventGameResult getOrCreateResult(EventSession session, EventGameState gameState) {
        EventGameResult existing = eventGameResultMapper.findByParticipantId(gameState.getParticipantId());
        if (existing != null) {
            return existing;
        }

        EventGameState lockedGameState = eventGameStateMapper.findByParticipantIdForUpdate(
                gameState.getParticipantId());
        if (lockedGameState == null) {
            throw new EventNotStartedException("게임 진행 기록이 없습니다.");
        }

        // 잠금 획득 전 확정된 결과를 현재 커밋 상태에서 다시 확인한다.
        existing = eventGameResultMapper.findByParticipantId(gameState.getParticipantId());
        if (existing != null) {
            return existing;
        }
        return calculateAndSaveResult(session, lockedGameState);
    }

    private EventGameResult calculateAndSaveResult(EventSession session, EventGameState gameState) {
        Long participantId = gameState.getParticipantId();
        EventGameResult result = calculateResult(participantId, session, gameState);
        try {
            eventGameResultMapper.saveResult(result);
        } catch (DuplicateKeyException e) {
            // 동시 요청으로 인한 중복 생성은 DB UNIQUE 제약조건을 최종 방어선으로 사용하고 기존 결과를 반환한다
            result = eventGameResultMapper.findByParticipantId(participantId);
        }
        return result;
    }

    private EventParticipant findParticipant(String participantToken) {
        if (participantToken == null || participantToken.isBlank()) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }
        EventParticipant participant = eventParticipantMapper.findByParticipantToken(participantToken);
        if (participant == null) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }
        return participant;
    }

    private void validateFinished(EventSession session) {
        if (session.getStatus() == EventSessionStatus.WAITING
                || session.getStatus() == EventSessionStatus.COUNTDOWN) {
            throw new EventNotStartedException("게임이 아직 시작되지 않았습니다.");
        }
        if (session.getStatus() == EventSessionStatus.RUNNING) {
            throw new EventNotFinishedException("게임이 아직 종료되지 않았습니다.");
        }
    }

    private EventGameResult calculateResult(
            Long participantId,
            EventSession session,
            EventGameState gameState) {
        ScenarioDto scenario = scenarioService.getScenario(session.getScenarioId());
        LocalDateTime finishedAt = session.getFinishedAt();

        // 강제 종료 시에도 startAt/endAt/durationSeconds 기준의 동일한 Tick 계산 공식을
        // now 대신 finishedAt으로 호출해 그대로 재사용한다 (마지막 Tick으로 잘못 계산하지 않는다).
        EventGameClock clock = eventGameClockService.calculateClock(session, scenario, finishedAt);
        int finalTick = clock.getCurrentTick();
        long finalPrice = getScenarioPrice(scenario, finalTick);

        long initialAsset = session.getInitialCash();
        long finalAsset = calculateFinalAsset(gameState, finalPrice);
        BigDecimal returnRate = calculateReturnRate(initialAsset, finalAsset);

        List<EventActionLogDto> eventLogs =
                eventActionLogMapper.getActionLogsByParticipantId(participantId);

        // 이벤트 전용 성향 계산: 강제 초기 배분은 점수에서 제외하고
        // 시간가중 자산구성(RT·LH) + 수익 추구 행동(RP)으로 산출한다.
        // 로그 재구성이 유일한 원천이며, 게임 상태와의 불일치는 WARN으로만 기록한다.
        EventExtractionResult extraction = eventPersonaFeatureExtractor.extract(
                scenario, toGameActionLogDtos(eventLogs), finalTick
        );
        warnIfReconstructionMismatch(gameState, extraction);
        AssessmentScore assessmentScore = eventPersonaScoreCalculator.calculate(extraction.features());
        PersonaType personaType = personaClassifier.calculatePersona(assessmentScore);
        Long personaId = assessmentMapper.getPersonaIdByAxisCode(personaType.getAxisCode());
        if (personaId == null) {
            throw new IllegalStateException("투자 성향 기준 정보를 찾을 수 없습니다: " + personaType.getAxisCode());
        }

        EventGameResult result = new EventGameResult();
        result.setParticipantId(participantId);
        result.setSessionId(session.getSessionId());
        result.setPersonaId(personaId);
        result.setInitialAsset(initialAsset);
        result.setFinalAsset(finalAsset);
        result.setReturnRate(returnRate);
        result.setRtScore(assessmentScore.getRtScore());
        result.setLhScore(assessmentScore.getLhScore());
        result.setRpScore(assessmentScore.getRpScore());
        result.setFinalTick(finalTick);
        result.setFinalPrice(finalPrice);
        result.setFinishedAt(finishedAt);
        return result;
    }

    // 로그 재구성 결과와 저장된 게임 상태의 정합성을 검증한다.
    // 점수는 재구성값 기준(로그가 유일한 원천)이며, 게임 상태는 검증용이다.
    // 허용 오차 초과 시에도 예외로 실패시키지 않고 WARN만 남긴다(강제 종료 등 예외 케이스 방어).
    private void warnIfReconstructionMismatch(EventGameState gameState, EventExtractionResult extraction) {
        BigDecimal quantityDifference = extraction.finalQuantity().subtract(gameState.getStockQuantity()).abs();
        if (quantityDifference.compareTo(QUANTITY_TOLERANCE) > 0
                || extraction.finalPrincipal() != gameState.getStockPrincipal()) {
            log.warn("이벤트 성향 계산 로그 재구성 불일치: participantId={}, 재구성 수량={}, 상태 수량={}, "
                            + "재구성 원금={}, 상태 원금={}",
                    gameState.getParticipantId(),
                    extraction.finalQuantity().toPlainString(),
                    gameState.getStockQuantity() == null ? null : gameState.getStockQuantity().toPlainString(),
                    extraction.finalPrincipal(),
                    gameState.getStockPrincipal());
        }
    }

    // stockPrincipal(원가)이 아닌 stockQuantity * finalPrice(시가)로 평가자산을 계산한다.
    private long calculateFinalAsset(EventGameState gameState, long finalPrice) {
        BigDecimal stockValue = gameState.getStockQuantity()
                .multiply(BigDecimal.valueOf(finalPrice))
                .setScale(0, RoundingMode.HALF_UP);
        return gameState.getCashBalance() + gameState.getDepositAmount() + stockValue.longValueExact();
    }

    private BigDecimal calculateReturnRate(long initialAsset, long finalAsset) {
        return BigDecimal.valueOf(finalAsset - initialAsset)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(initialAsset), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
    }

    // GameBehaviorAssessmentCalculator는 회원용 ActionLogDto 목록을 입력으로 받으므로
    // 계산에 필요한 필드를 그대로 옮겨 담는 얇은 어댑터로 변환한다.
    private List<ActionLogDto> toGameActionLogDtos(List<EventActionLogDto> eventLogs) {
        return eventLogs.stream()
                .map(log -> {
                    ActionLogDto dto = new ActionLogDto();
                    dto.setActionLogId(log.getActionLogId());
                    dto.setGameTick(log.getGameTick());
                    dto.setActionType(log.getActionType());
                    dto.setAssetType(log.getAssetType());
                    dto.setActionAmount(log.getActionAmount());
                    dto.setMarketState(log.getMarketState());
                    dto.setDepositStatus(log.getDepositStatus());
                    dto.setCurrentCash(log.getCurrentCash());
                    dto.setCurrentStock(log.getCurrentStock());
                    dto.setCurrentDeposit(log.getCurrentDeposit());
                    return dto;
                })
                .toList();
    }

    private long getScenarioPrice(ScenarioDto scenario, int tick) {
        return scenario.getTicks()
                .stream()
                .filter(scenarioTick -> scenarioTick.getTick() == tick)
                .map(ScenarioTickDto::getPrice)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "게임 시나리오 tick을 찾을 수 없습니다: " + tick
                ));
    }

    private EventGameResultResponse buildResponse(EventParticipant participant, EventGameResult result) {
        PersonaResponseDto persona = personaMapper.findPersonaById(result.getPersonaId());
        String personaName = persona == null ? null : persona.getPersonaName();

        return new EventGameResultResponse(
                participant.getParticipantId(),
                participant.getNickname(),
                result.getInitialAsset(),
                result.getFinalAsset(),
                result.getReturnRate(),
                result.getRtScore(),
                result.getLhScore(),
                result.getRpScore(),
                result.getPersonaId(),
                personaName,
                persona,
                result.getFinalTick(),
                result.getFinalPrice(),
                result.getFinishedAt()
        );
    }
}
