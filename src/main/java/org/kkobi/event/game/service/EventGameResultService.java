package org.kkobi.event.game.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.assessment.calculator.GameScoreCalculator;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.enums.PersonaType;
import org.kkobi.assessment.mapper.AssessmentMapper;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
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
import org.kkobi.assessment.calculator.GameBehaviorAssessmentCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;
import org.kkobi.persona.dto.PersonaResponseDto;
import org.kkobi.persona.mapper.PersonaMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 행사 참가자의 게임 종료 결과(최종 자산/수익률/성향)를 서버에서 최초 1회 확정한다.
// event_game_states + SC001 종료 시점 가격으로 최종 자산을 계산하고,
// 기존 회원용 GameBehaviorAssessmentCalculator/GameScoreCalculator/PersonaClassifier를 그대로 재사용해
// 성향 점수와 페르소나를 회원 게임과 동일한 방식으로 산출한다.
@Service
@RequiredArgsConstructor
public class EventGameResultService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int RETURN_RATE_SCALE = 2;

    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionService eventSessionService;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventActionLogMapper eventActionLogMapper;
    private final EventGameResultMapper eventGameResultMapper;
    private final ScenarioService scenarioService;
    private final EventGameClockService eventGameClockService;
    private final GameBehaviorAssessmentCalculator gameBehaviorAssessmentCalculator;
    private final GameScoreCalculator gameScoreCalculator;
    private final PersonaClassifier personaClassifier;
    private final AssessmentMapper assessmentMapper;
    private final PersonaMapper personaMapper;

    @Transactional
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

        EventGameState gameState = eventGameStateMapper.findByParticipantId(participant.getParticipantId());
        if (gameState == null) {
            throw new EventNotStartedException("게임 진행 기록이 없습니다.");
        }

        EventGameResult result = calculateAndSaveResult(session, gameState);
        return buildResponse(participant, result);
    }

    // 이미 결과가 있으면 그대로 반환하고, 없으면 최초 1회 확정해 저장한다.
    // 리더보드(EventLeaderboardService)가 결과 화면을 열지 않은 참가자의 결과를
    // 조회 전에 일괄 확정할 때 이 계산 로직을 그대로 재사용한다.
    @Transactional
    public EventGameResult getOrCreateResult(EventSession session, EventGameState gameState) {
        EventGameResult existing = eventGameResultMapper.findByParticipantId(gameState.getParticipantId());
        if (existing != null) {
            return existing;
        }
        return calculateAndSaveResult(session, gameState);
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
        BehaviorAnalysisResult gameAnalysis = gameBehaviorAssessmentCalculator.calculateForEventGame(
                scenario, toGameActionLogDtos(eventLogs)
        );
        AssessmentScore assessmentScore = gameScoreCalculator.calculateGameScore(
                List.of(gameAnalysis.getTotalScoreDelta())
        );
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
