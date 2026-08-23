package org.kkobi.event.game.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kkobi.assessment.calculator.BehaviorContextFactory;
import org.kkobi.assessment.calculator.BehaviorRuleEngine;
import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.domain.BehaviorContext;
import org.kkobi.assessment.domain.BehaviorEvent;
import org.kkobi.assessment.enums.BehaviorActionType;
import org.kkobi.assessment.enums.BehaviorAssetType;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyFinishedException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.calculator.StockQuantityPolicy;
import org.kkobi.event.game.domain.EventGameClock;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.dto.EventActionLogDto;
import org.kkobi.event.game.dto.request.EventGameActionRequest;
import org.kkobi.event.game.dto.response.EventGameActionResponse;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.service.EventSessionService;
import org.kkobi.game.calculator.GamePriceRateCalculator;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 행사 참가자의 게임 행동(매수/매도/예금 해지)을 처리한다.
// gameTick과 매매 가격은 요청 값을 신뢰하지 않고 서버가 계산한 값을 사용한다.
@Service
@RequiredArgsConstructor
public class EventGameActionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long GAME_SECURITY_ID = 1L;

    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionService eventSessionService;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventActionLogMapper eventActionLogMapper;
    private final ScenarioService scenarioService;
    private final EventGameClockService eventGameClockService;
    private final GamePriceRateCalculator gamePriceRateCalculator;
    private final RecentExtremaMarketStateCalculator recentExtremaMarketStateCalculator;
    private final GameSecurityReturnCalculator gameSecurityReturnCalculator;
    private final BehaviorContextFactory behaviorContextFactory;
    private final BehaviorRuleEngine behaviorRuleEngine;

    @Transactional
    public EventGameActionResponse saveAction(String participantToken, EventGameActionRequest request) {
        return saveAction(participantToken, request, LocalDateTime.now(KST));
    }

    EventGameActionResponse saveAction(String participantToken, EventGameActionRequest request, LocalDateTime now) {
        EventParticipant participant = findParticipant(participantToken);
        EventSession session = eventSessionService.getSynchronizedSession(participant.getSessionId(), now);
        validateSessionStarted(session);

        EventGameState gameState = eventGameStateMapper.findByParticipantId(participant.getParticipantId());
        if (gameState == null) {
            throw new EventNotStartedException("먼저 게임 상태를 생성해야 합니다.");
        }

        ScenarioDto scenario = scenarioService.getScenario(session.getScenarioId());
        EventGameClock clock = eventGameClockService.calculateClock(session, scenario, now);
        validateActionAllowed(clock);
        int serverTick = clock.getCurrentTick();
        long currentPrice = getScenarioPrice(scenario, serverTick);

        BehaviorActionType actionType = BehaviorActionType.getBehaviorActionType(request.getActionType());
        BehaviorAssetType assetType = BehaviorAssetType.getBehaviorAssetType(request.getAssetType());
        validateActionRequest(actionType, assetType, request);
        long actionAmount = resolveActionAmount(actionType, request, currentPrice);

        ActionBalances balances = applyAction(
                gameState, actionType, request.getActionQuantity(), actionAmount);
        long newCash = balances.getCash();
        long newStock = balances.getStockPrincipal();
        BigDecimal newStockQuantity = balances.getStockQuantity();
        long newDeposit = balances.getDeposit();

        List<EventActionLogDto> previousLogs =
                eventActionLogMapper.getActionLogsByParticipantId(participant.getParticipantId());

        BehaviorEvent currentEvent = createCurrentBehaviorEvent(
                actionAmount, actionType, assetType, scenario, serverTick,
                newCash, newStock, newDeposit, previousLogs
        );
        List<BehaviorEvent> previousEvents = previousLogs.stream()
                .map(log -> createPreviousBehaviorEvent(log, scenario))
                .toList();
        BehaviorContext behaviorContext = behaviorContextFactory.createBehaviorContext(
                currentEvent, previousEvents
        );
        BehaviorAnalysisResult analysisResult = behaviorRuleEngine.calculateGameBehaviorAnalysis(behaviorContext);

        EventActionLogDto actionLog = createActionLog(
                participant, session, actionAmount, serverTick,
                behaviorContext, analysisResult, newCash, newStock, newDeposit
        );
        eventActionLogMapper.saveActionLog(actionLog);
        eventGameStateMapper.updateBalances(
                gameState.getGameStateId(), newCash, newStock, newStockQuantity, newDeposit
        );

        return buildActionResponse(actionLog, newStockQuantity, currentPrice);
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

    // WAITING/COUNTDOWN은 아직 시작 전, FINISHED이거나 강제 종료로 인해 시간상 종료된 경우는 종료 후로 판단한다.
    private void validateSessionStarted(EventSession session) {
        if (session.getStatus() == EventSessionStatus.WAITING
                || session.getStatus() == EventSessionStatus.COUNTDOWN) {
            throw new EventNotStartedException("아직 게임이 시작되지 않았습니다.");
        }
    }

    private void validateActionAllowed(EventGameClock clock) {
        if (!clock.isActionAllowed()) {
            throw new EventAlreadyFinishedException("게임이 종료되었습니다.");
        }
    }

    private void validateActionRequest(
            BehaviorActionType actionType,
            BehaviorAssetType assetType,
            EventGameActionRequest request) {
        if (actionType != BehaviorActionType.BUY
                && actionType != BehaviorActionType.SELL
                && actionType != BehaviorActionType.CANCEL_PRODUCT) {
            throw new IllegalArgumentException("행사 게임에서 지원하지 않는 행동입니다: " + actionType);
        }
        if ((actionType == BehaviorActionType.BUY || actionType == BehaviorActionType.SELL)
                && assetType != BehaviorAssetType.SECURITY) {
            throw new IllegalArgumentException("매수와 매도의 assetType은 STOCK이어야 합니다.");
        }
        if (actionType == BehaviorActionType.CANCEL_PRODUCT && assetType != BehaviorAssetType.PRODUCT) {
            throw new IllegalArgumentException("예금 해지의 assetType은 DEPOSIT이어야 합니다.");
        }
        if ((actionType == BehaviorActionType.BUY || actionType == BehaviorActionType.SELL)
                && (request.getActionQuantity() == null || request.getActionQuantity() <= 0)) {
            throw new IllegalArgumentException("주문 수량은 1주 이상이어야 합니다.");
        }
        if (actionType == BehaviorActionType.CANCEL_PRODUCT
                && (request.getActionAmount() == null || request.getActionAmount() <= 0)) {
            throw new IllegalArgumentException("행동 금액은 0보다 커야 합니다.");
        }
    }

    private long resolveActionAmount(
            BehaviorActionType actionType,
            EventGameActionRequest request,
            long currentPrice) {
        if (actionType == BehaviorActionType.BUY || actionType == BehaviorActionType.SELL) {
            return Math.multiplyExact(request.getActionQuantity(), currentPrice);
        }
        return request.getActionAmount();
    }

    // BUY/SELL은 클라이언트가 선택한 정수 수량을 유지하고, 주문 금액은 서버 현재가로 계산한다.
    // SELL은 매도 수량 비율만큼 원금(stock_principal, 평단가 기준)도 함께 줄여 두 필드의 정합성을 유지한다.
    // 이렇게 하면 로그 전체를 다시 계산하지 않고 stock_quantity * 현재가로 평가자산을 바로 구할 수 있다.
    private ActionBalances applyAction(
            EventGameState gameState,
            BehaviorActionType actionType,
            Long actionQuantity,
            long actionAmount) {
        long cash = gameState.getCashBalance();
        long stockPrincipal = gameState.getStockPrincipal();
        BigDecimal stockQuantity = gameState.getStockQuantity();
        long deposit = gameState.getDepositAmount();

        switch (actionType) {
            case BUY -> {
                if (cash < actionAmount) {
                    throw new IllegalArgumentException("현금이 부족합니다.");
                }
                BigDecimal boughtQuantity = BigDecimal.valueOf(actionQuantity);
                cash -= actionAmount;
                stockPrincipal += actionAmount;
                stockQuantity = stockQuantity.add(boughtQuantity);
            }
            case SELL -> {
                BigDecimal soldQuantity = BigDecimal.valueOf(actionQuantity);
                if (stockQuantity.compareTo(soldQuantity) < 0) {
                    throw new IllegalArgumentException("보유 주식 수량이 부족합니다.");
                }
                long soldPrincipal = StockQuantityPolicy.calculateProportionalPrincipal(
                        stockPrincipal, soldQuantity, stockQuantity);
                cash += actionAmount;
                stockPrincipal -= soldPrincipal;
                stockQuantity = stockQuantity.subtract(soldQuantity);
            }
            case CANCEL_PRODUCT -> {
                if (deposit < actionAmount) {
                    throw new IllegalArgumentException("예금 잔액이 부족합니다.");
                }
                deposit -= actionAmount;
                cash += actionAmount;
            }
            default -> throw new IllegalArgumentException("행사 게임에서 지원하지 않는 행동입니다: " + actionType);
        }
        return new ActionBalances(cash, stockPrincipal, stockQuantity, deposit);
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

    @Getter
    @AllArgsConstructor
    private static class ActionBalances {
        private final long cash;
        private final long stockPrincipal;
        private final BigDecimal stockQuantity;
        private final long deposit;
    }

    private BehaviorEvent createCurrentBehaviorEvent(
            long actionAmount,
            BehaviorActionType actionType,
            BehaviorAssetType assetType,
            ScenarioDto scenario,
            int serverTick,
            long newCash,
            long newStock,
            long newDeposit,
            List<EventActionLogDto> previousLogs) {
        BehaviorEvent event = new BehaviorEvent();
        event.setGameTick(serverTick);
        event.setActionType(actionType);
        event.setAssetType(assetType);
        if (assetType == BehaviorAssetType.SECURITY) {
            event.setSecurityId(GAME_SECURITY_ID);
        }
        event.setActionAmount(actionAmount);
        event.setCurrentCash(newCash);
        event.setCurrentStockPrincipal(newStock);
        event.setCurrentDeposit(newDeposit);
        event.setCurrentPriceChangeRate(
                gamePriceRateCalculator.calculateTickPriceChangeRate(scenario, serverTick)
        );
        event.setMarketState(
                recentExtremaMarketStateCalculator.calculateMarketState(scenario, serverTick)
        );
        updateSecurityReturnRate(event, scenario, serverTick, previousLogs);
        event.setTradedAt(calculateGameActionAt(scenario, serverTick));
        return event;
    }

    private void updateSecurityReturnRate(
            BehaviorEvent event,
            ScenarioDto scenario,
            int serverTick,
            List<EventActionLogDto> previousLogs) {
        if (event.getAssetType() != BehaviorAssetType.SECURITY) {
            return;
        }
        BigDecimal currentReturnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                scenario, serverTick, toGameActionLogDtos(previousLogs)
        );
        if (event.getActionType() == BehaviorActionType.BUY) {
            event.setPositionReturnRate(currentReturnRate);
        } else if (event.getActionType() == BehaviorActionType.SELL) {
            event.setRealizedReturnRate(currentReturnRate);
        }
    }

    // GameSecurityReturnCalculator는 회원용 ActionLogDto를 입력으로 받으므로
    // 평균 매입가 계산에 필요한 최소 필드(tick, actionLogId, currentStock)만 얇게 변환한다.
    private List<ActionLogDto> toGameActionLogDtos(List<EventActionLogDto> eventLogs) {
        return eventLogs.stream()
                .map(log -> {
                    ActionLogDto dto = new ActionLogDto();
                    dto.setActionLogId(log.getActionLogId());
                    dto.setGameTick(log.getGameTick());
                    dto.setCurrentStock(log.getCurrentStock());
                    return dto;
                })
                .toList();
    }

    private BehaviorEvent createPreviousBehaviorEvent(EventActionLogDto log, ScenarioDto scenario) {
        BehaviorEvent event = new BehaviorEvent();
        event.setGameTick(log.getGameTick());
        event.setActionSequence(log.getActionLogId());
        event.setActionType(BehaviorActionType.getBehaviorActionType(log.getActionType()));
        event.setAssetType(BehaviorAssetType.getBehaviorAssetType(log.getAssetType()));
        if (event.getAssetType() == BehaviorAssetType.SECURITY) {
            event.setSecurityId(GAME_SECURITY_ID);
        }
        event.setActionAmount(log.getActionAmount());
        event.setCurrentCash(log.getCurrentCash());
        event.setCurrentStockPrincipal(log.getCurrentStock());
        event.setCurrentDeposit(log.getCurrentDeposit());
        event.setMarketState(MarketState.getMarketState(log.getMarketState()));
        event.setTradedAt(calculateGameActionAt(scenario, log.getGameTick()));
        return event;
    }

    private LocalDateTime calculateGameActionAt(ScenarioDto scenario, Integer gameTick) {
        return scenario.getTicks()
                .stream()
                .filter(scenarioTick -> scenarioTick.getTick() == gameTick)
                .map(ScenarioTickDto::getDate)
                .filter(scenarioDate -> scenarioDate != null && !scenarioDate.isBlank())
                .map(LocalDate::parse)
                .map(LocalDate::atStartOfDay)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "게임 시나리오 tick 날짜를 찾을 수 없습니다: " + gameTick
                ));
    }

    private EventActionLogDto createActionLog(
            EventParticipant participant,
            EventSession session,
            long actionAmount,
            int serverTick,
            BehaviorContext behaviorContext,
            BehaviorAnalysisResult analysisResult,
            long newCash,
            long newStock,
            long newDeposit) {
        EventActionLogDto actionLog = new EventActionLogDto();
        actionLog.setParticipantId(participant.getParticipantId());
        actionLog.setSessionId(session.getSessionId());
        actionLog.setScenarioId(session.getScenarioId());
        actionLog.setGameTick(serverTick);
        actionLog.setActionType(getActionLogActionType(behaviorContext.getCurrentEvent().getActionType()));
        actionLog.setAssetType(getActionLogAssetType(behaviorContext.getCurrentEvent().getAssetType()));
        actionLog.setActionAmount(actionAmount);
        actionLog.setMarketState(behaviorContext.getMarketState().name());
        actionLog.setDepositStatus(getDepositStatus(behaviorContext.getCurrentEvent().getActionType(), newDeposit));
        actionLog.setCurrentCash(newCash);
        actionLog.setCurrentStock(newStock);
        actionLog.setCurrentDeposit(newDeposit);
        actionLog.setRtScoreDelta(analysisResult.getTotalScoreDelta().getRtDelta());
        actionLog.setLhScoreDelta(analysisResult.getTotalScoreDelta().getLhDelta());
        actionLog.setRpScoreDelta(analysisResult.getTotalScoreDelta().getRpDelta());
        return actionLog;
    }

    private String getActionLogActionType(BehaviorActionType actionType) {
        return actionType == BehaviorActionType.CANCEL_PRODUCT ? "DEPOSIT_CANCEL" : actionType.name();
    }

    private String getActionLogAssetType(BehaviorAssetType assetType) {
        return switch (assetType) {
            case SECURITY -> "STOCK";
            case PRODUCT -> "DEPOSIT";
            default -> assetType.name();
        };
    }

    private String getDepositStatus(BehaviorActionType actionType, long currentDeposit) {
        if (actionType == BehaviorActionType.CANCEL_PRODUCT) {
            return "CANCELLED";
        }
        return currentDeposit > 0L ? "ACTIVE" : "NONE";
    }

    private EventGameActionResponse buildActionResponse(
            EventActionLogDto actionLog,
            BigDecimal stockQuantity,
            long currentPrice) {
        long totalAssetPrincipal = actionLog.getCurrentCash()
                + actionLog.getCurrentStock()
                + actionLog.getCurrentDeposit();
        long stockValue = stockQuantity
                .multiply(BigDecimal.valueOf(currentPrice))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        long totalAssetValue = actionLog.getCurrentCash() + actionLog.getCurrentDeposit() + stockValue;
        return new EventGameActionResponse(
                actionLog.getActionLogId(),
                actionLog.getGameTick(),
                actionLog.getActionType(),
                actionLog.getAssetType(),
                actionLog.getActionAmount(),
                actionLog.getCurrentCash(),
                actionLog.getCurrentStock(),
                stockQuantity,
                actionLog.getCurrentDeposit(),
                totalAssetPrincipal,
                totalAssetValue,
                actionLog.getMarketState(),
                actionLog.getDepositStatus(),
                actionLog.getRtScoreDelta(),
                actionLog.getLhScoreDelta(),
                actionLog.getRpScoreDelta()
        );
    }
}
