package org.kkobi.event.game.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.assessment.calculator.BehaviorContextFactory;
import org.kkobi.assessment.calculator.BehaviorRuleEngine;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.domain.BehaviorContext;
import org.kkobi.assessment.domain.BehaviorEvent;
import org.kkobi.assessment.enums.BehaviorActionType;
import org.kkobi.assessment.enums.BehaviorAssetType;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.game.domain.EventGameClock;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.dto.EventActionLogDto;
import org.kkobi.event.game.dto.response.EventGameStatusResponse;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.service.EventSessionService;
import org.kkobi.game.dto.GameStartRequest;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 행사 참가자의 게임 상태(자산)를 생성/조회한다.
// 회원용 GameStartService와 달리 userId가 아닌 participantId를 기준으로 동작하며,
// 초기 자산은 event_sessions.initialCash를 기준으로 계산한다.
@Service
@RequiredArgsConstructor
public class EventGameStateService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int START_TICK = 0;
    private static final BigDecimal TOTAL_RATIO = BigDecimal.valueOf(100);

    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionService eventSessionService;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventActionLogMapper eventActionLogMapper;
    private final ScenarioService scenarioService;
    private final EventGameClockService eventGameClockService;
    private final BehaviorContextFactory behaviorContextFactory;
    private final BehaviorRuleEngine behaviorRuleEngine;

    @Transactional
    public EventGameStatusResponse ensureGameState(String participantToken, GameStartRequest request) {
        return ensureGameState(participantToken, request, LocalDateTime.now(KST));
    }

    EventGameStatusResponse ensureGameState(String participantToken, GameStartRequest request, LocalDateTime now) {
        EventParticipant participant = findParticipant(participantToken);
        EventSession session = eventSessionService.getSynchronizedSession(participant.getSessionId(), now);

        EventGameState gameState = eventGameStateMapper.findByParticipantId(participant.getParticipantId());
        if (gameState == null) {
            gameState = createGameState(participant, session, request);
        }

        return buildStatusResponse(participant, session, gameState, now);
    }

    @Transactional
    public EventGameStatusResponse getStatus(String participantToken) {
        return getStatus(participantToken, LocalDateTime.now(KST));
    }

    EventGameStatusResponse getStatus(String participantToken, LocalDateTime now) {
        EventParticipant participant = findParticipant(participantToken);
        EventSession session = eventSessionService.getSynchronizedSession(participant.getSessionId(), now);

        EventGameState gameState = eventGameStateMapper.findByParticipantId(participant.getParticipantId());
        if (gameState == null) {
            throw new EventNotStartedException("아직 게임이 시작되지 않았습니다.");
        }

        return buildStatusResponse(participant, session, gameState, now);
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

    // 게임 상태가 없으면 초기 자산을 계산해 생성한다.
    // 동시 요청으로 인한 중복 생성은 DB UNIQUE 제약조건을 최종 방어선으로 사용하고 기존 상태를 반환한다.
    private EventGameState createGameState(
            EventParticipant participant,
            EventSession session,
            GameStartRequest request) {
        validateAllocationRequest(request);

        long initialCash = session.getInitialCash();
        long stockAmount = calculateAssetAmount(initialCash, request.getStockRatio());
        long depositAmount = calculateAssetAmount(initialCash, request.getDepositRatio());
        long cashAmount = initialCash - stockAmount - depositAmount;

        EventGameState gameState = new EventGameState();
        gameState.setParticipantId(participant.getParticipantId());
        gameState.setSessionId(session.getSessionId());
        gameState.setScenarioId(session.getScenarioId());
        gameState.setInitialCash(initialCash);
        gameState.setCashBalance(cashAmount);
        gameState.setStockPrincipal(stockAmount);
        gameState.setStockQuantity(BigDecimal.ZERO);
        gameState.setDepositAmount(depositAmount);

        try {
            eventGameStateMapper.saveGameState(gameState);
        } catch (DuplicateKeyException e) {
            return eventGameStateMapper.findByParticipantId(participant.getParticipantId());
        }

        saveInitialAllocationLog(participant, session, gameState);
        return gameState;
    }

    private void validateAllocationRequest(GameStartRequest request) {
        if (request == null
                || request.getCashRatio() == null
                || request.getStockRatio() == null
                || request.getDepositRatio() == null) {
            throw new IllegalArgumentException("초기 자산 비율은 모두 필수입니다.");
        }
        validateRatioRange(request.getCashRatio());
        validateRatioRange(request.getStockRatio());
        validateRatioRange(request.getDepositRatio());

        BigDecimal ratioSum = request.getCashRatio()
                .add(request.getStockRatio())
                .add(request.getDepositRatio());
        if (ratioSum.compareTo(TOTAL_RATIO) != 0) {
            throw new IllegalArgumentException("초기 자산 비율의 합은 100이어야 합니다.");
        }
    }

    private void validateRatioRange(BigDecimal ratio) {
        if (ratio.compareTo(BigDecimal.ZERO) < 0
                || ratio.compareTo(TOTAL_RATIO) > 0) {
            throw new IllegalArgumentException("초기 자산 비율은 0 이상 100 이하여야 합니다.");
        }
    }

    private long calculateAssetAmount(long initialCash, BigDecimal ratio) {
        return BigDecimal.valueOf(initialCash)
                .multiply(ratio)
                .divide(TOTAL_RATIO, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    private void saveInitialAllocationLog(
            EventParticipant participant,
            EventSession session,
            EventGameState gameState) {
        BehaviorEvent initialAllocation = new BehaviorEvent();
        initialAllocation.setGameTick(START_TICK);
        initialAllocation.setActionType(BehaviorActionType.INITIAL_ALLOCATION);
        initialAllocation.setAssetType(BehaviorAssetType.ALL);
        initialAllocation.setActionAmount(gameState.getInitialCash());
        initialAllocation.setCurrentCash(gameState.getCashBalance());
        initialAllocation.setCurrentStockPrincipal(gameState.getStockPrincipal());
        initialAllocation.setCurrentDeposit(gameState.getDepositAmount());
        initialAllocation.setMarketState(MarketState.NORMAL);

        BehaviorContext behaviorContext = behaviorContextFactory.createBehaviorContext(
                initialAllocation,
                List.of()
        );
        BehaviorAnalysisResult analysisResult = behaviorRuleEngine.calculateGameBehaviorAnalysis(behaviorContext);

        EventActionLogDto actionLog = new EventActionLogDto();
        actionLog.setParticipantId(participant.getParticipantId());
        actionLog.setSessionId(session.getSessionId());
        actionLog.setScenarioId(session.getScenarioId());
        actionLog.setGameTick(START_TICK);
        actionLog.setActionType("INITIAL_ALLOCATION");
        actionLog.setAssetType("ALL");
        actionLog.setActionAmount(gameState.getInitialCash());
        actionLog.setMarketState(MarketState.NORMAL.name());
        actionLog.setDepositStatus(gameState.getDepositAmount() > 0 ? "ACTIVE" : "NONE");
        actionLog.setCurrentCash(gameState.getCashBalance());
        actionLog.setCurrentStock(gameState.getStockPrincipal());
        actionLog.setCurrentDeposit(gameState.getDepositAmount());
        actionLog.setRtScoreDelta(analysisResult.getTotalScoreDelta().getRtDelta());
        actionLog.setLhScoreDelta(analysisResult.getTotalScoreDelta().getLhDelta());
        actionLog.setRpScoreDelta(analysisResult.getTotalScoreDelta().getRpDelta());
        eventActionLogMapper.saveActionLog(actionLog);
    }

    private EventGameStatusResponse buildStatusResponse(
            EventParticipant participant,
            EventSession session,
            EventGameState gameState,
            LocalDateTime now) {
        ScenarioDto scenario = scenarioService.getScenario(session.getScenarioId());
        EventGameClock clock = eventGameClockService.calculateClock(session, scenario, now);
        long currentPrice = getScenarioPrice(scenario, clock.getCurrentTick());
        long totalAssetPrincipal = gameState.getCashBalance()
                + gameState.getStockPrincipal()
                + gameState.getDepositAmount();
        long totalAssetValue = calculateAssetValue(gameState, currentPrice);
        String depositStatus = gameState.getDepositAmount() > 0 ? "ACTIVE" : "NONE";

        return new EventGameStatusResponse(
                participant.getParticipantId(),
                session.getSessionId(),
                session.getScenarioId(),
                session.getStatus().name(),
                now,
                session.getStartAt(),
                session.getEndAt(),
                clock.getRemainingSeconds(),
                clock.getCurrentTick(),
                clock.getTotalTickCount(),
                currentPrice,
                gameState.getInitialCash(),
                gameState.getCashBalance(),
                gameState.getStockPrincipal(),
                gameState.getStockQuantity(),
                gameState.getDepositAmount(),
                totalAssetPrincipal,
                totalAssetValue,
                depositStatus
        );
    }

    // 로그 전체를 다시 계산하지 않고 cashBalance + depositAmount + stockQuantity * currentPrice로 평가자산을 구한다.
    private long calculateAssetValue(EventGameState gameState, long currentPrice) {
        BigDecimal stockValue = gameState.getStockQuantity()
                .multiply(BigDecimal.valueOf(currentPrice))
                .setScale(0, RoundingMode.HALF_UP);
        return gameState.getCashBalance() + gameState.getDepositAmount() + stockValue.longValueExact();
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
}
