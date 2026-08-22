package org.kkobi.event.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.AssetRatioCalculator;
import org.kkobi.assessment.calculator.BehaviorContextFactory;
import org.kkobi.assessment.calculator.BehaviorRuleEngine;
import org.kkobi.assessment.calculator.MarketStateCalculator;
import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.assessment.calculator.SecurityPriceRateCalculator;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyFinishedException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
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
import org.kkobi.game.service.ScenarioService;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventGameActionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final String TOKEN = "token-123";

    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventSessionService eventSessionService = mock(EventSessionService.class);
    private final EventGameStateMapper eventGameStateMapper = mock(EventGameStateMapper.class);
    private final EventActionLogMapper eventActionLogMapper = mock(EventActionLogMapper.class);

    private final EventGameActionService service = new EventGameActionService(
            eventParticipantMapper,
            eventSessionService,
            eventGameStateMapper,
            eventActionLogMapper,
            new ScenarioService(),
            new EventGameClockService(),
            new GamePriceRateCalculator(new SecurityPriceRateCalculator()),
            new RecentExtremaMarketStateCalculator(),
            new GameSecurityReturnCalculator(),
            new BehaviorContextFactory(new AssetRatioCalculator(), new MarketStateCalculator()),
            new BehaviorRuleEngine()
    );

    private EventParticipant createParticipant() {
        EventParticipant participant = new EventParticipant();
        participant.setParticipantId(1L);
        participant.setSessionId(10L);
        participant.setNickname("박지우");
        participant.setParticipantToken(TOKEN);
        return participant;
    }

    private EventSession createSession(EventSessionStatus status, LocalDateTime startAt, LocalDateTime endAt) {
        EventSession session = new EventSession();
        session.setSessionId(10L);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setStartAt(startAt);
        session.setEndAt(endAt);
        session.setDurationSeconds(180);
        session.setInitialCash(10_000_000L);
        return session;
    }

    private EventSession createRunningSession() {
        return createSession(EventSessionStatus.RUNNING, NOW.minusSeconds(10), NOW.plusSeconds(170));
    }

    private EventGameState createGameState(long cash, long stock, long deposit) {
        return createGameState(cash, stock, BigDecimal.ZERO, deposit);
    }

    private EventGameState createGameState(long cash, long stockPrincipal, BigDecimal stockQuantity, long deposit) {
        return new EventGameState(
                100L, 1L, 10L, "SC001", 10_000_000L,
                cash, stockPrincipal, stockQuantity, deposit, null, null
        );
    }

    private EventGameActionRequest createRequest(String actionType, String assetType, long amount) {
        EventGameActionRequest request = new EventGameActionRequest();
        request.setActionType(actionType);
        request.setAssetType(assetType);
        request.setActionAmount(amount);
        return request;
    }

    private void stubCommon(EventSession session, EventGameState gameState) {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(session);
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L)).thenReturn(List.of());
    }

    @Test
    @DisplayName("RUNNING 상태에서 매수하면 현금이 줄고, 서버 가격 기준 수량만큼 stock_quantity가 늘어난다")
    void buyMovesCashToStockAndUpdatesQuantity() {
        stubCommon(createRunningSession(), createGameState(10_000_000L, 0L, 0L));

        EventGameActionResponse response = service.saveAction(
                TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW
        );

        // startAt 10초 경과 -> tick2, SC001 tick2 가격 20080원. 1,000,000 / 20080 = 49.80079681(scale 8, HALF_UP)
        assertEquals(9_000_000L, response.getCashBalance());
        assertEquals(1_000_000L, response.getStockPrincipal());
        assertEquals(0, new BigDecimal("49.80079681").compareTo(response.getStockQuantity()));
        // 매수 직후이므로 평가액(반올림 오차 내)이 원금과 거의 같아 총자산이 그대로 보존된다
        assertEquals(10_000_000L, response.getTotalAssetValue());

        ArgumentCaptor<BigDecimal> quantityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(eventGameStateMapper).updateBalances(
                eq(100L), eq(9_000_000L), eq(1_000_000L), quantityCaptor.capture(), eq(0L)
        );
        assertEquals(0, new BigDecimal("49.80079681").compareTo(quantityCaptor.getValue()));
    }

    @Test
    @DisplayName("이벤트 게임 행동은 최근 고점 대비 낙폭으로 계산한 시장 상태를 저장한다")
    void actionStoresRecentExtremaMarketState() {
        EventSession crashSession = createSession(
                EventSessionStatus.RUNNING,
                NOW.minusSeconds(70),
                NOW.plusSeconds(110)
        );
        stubCommon(crashSession, createGameState(10_000_000L, 0L, 0L));

        service.saveAction(
                TOKEN,
                createRequest("BUY", "STOCK", 1_000_000L),
                NOW
        );

        ArgumentCaptor<EventActionLogDto> logCaptor =
                ArgumentCaptor.forClass(EventActionLogDto.class);
        verify(eventActionLogMapper).saveActionLog(logCaptor.capture());
        assertEquals(20, logCaptor.getValue().getGameTick());
        assertEquals("CRASH", logCaptor.getValue().getMarketState());
    }

    @Test
    @DisplayName("매도 시 매도 수량 비율만큼 stock_quantity와 원금이 함께 줄고, 현금은 매도 대금만큼 늘어난다")
    void sellMovesStockToCashAndUpdatesQuantityProportionally() {
        // 보유 50주(원금 1,004,000원, 평단가 20,080원 = 현재 tick2 가격과 동일)
        stubCommon(
                createRunningSession(),
                createGameState(9_000_000L, 1_004_000L, new BigDecimal("50"), 0L)
        );

        // 20주(20,080원 * 20 = 401,600원)를 매도
        EventGameActionResponse response = service.saveAction(
                TOKEN, createRequest("SELL", "STOCK", 401_600L), NOW
        );

        assertEquals(9_401_600L, response.getCashBalance());
        assertEquals(602_400L, response.getStockPrincipal());
        assertEquals(0, new BigDecimal("30").compareTo(response.getStockQuantity()));
        assertEquals(10_004_000L, response.getTotalAssetValue());
    }

    @Test
    @DisplayName("예금 해지 시 예금 잔액이 줄고 현금이 늘어난다")
    void cancelProductMovesDepositToCash() {
        stubCommon(createRunningSession(), createGameState(7_000_000L, 0L, 3_000_000L));

        EventGameActionResponse response = service.saveAction(
                TOKEN, createRequest("DEPOSIT_CANCEL", "DEPOSIT", 3_000_000L), NOW
        );

        assertEquals(10_000_000L, response.getCashBalance());
        assertEquals(0L, response.getDepositAmount());
    }

    @Test
    @DisplayName("보유 현금을 초과하는 매수는 거절된다")
    void buyRejectedWhenInsufficientCash() {
        stubCommon(createRunningSession(), createGameState(100_000L, 0L, 0L));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
        verify(eventGameStateMapper, never()).updateBalances(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("보유 수량을 초과하는 매도는 거절된다 (원금이 아닌 stock_quantity 기준)")
    void sellRejectedWhenInsufficientQuantity() {
        // 보유 10주(원금 200,800원, 평단가 20,080원)
        stubCommon(
                createRunningSession(),
                createGameState(9_000_000L, 200_800L, new BigDecimal("10"), 0L)
        );

        // 20주(401,600원) 매도를 시도 -> 보유 수량(10주) 초과
        assertThrows(
                IllegalArgumentException.class,
                () -> service.saveAction(TOKEN, createRequest("SELL", "STOCK", 401_600L), NOW)
        );
        verify(eventGameStateMapper, never()).updateBalances(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("클라이언트가 보낸 gameTick은 무시되고 서버가 계산한 tick이 저장된다")
    void clientSuppliedGameTickIsIgnored() {
        stubCommon(createRunningSession(), createGameState(10_000_000L, 0L, 0L));
        EventGameActionRequest request = createRequest("BUY", "STOCK", 1_000_000L);
        request.setGameTick(9999);

        EventGameActionResponse response = service.saveAction(TOKEN, request, NOW);

        // startAt 10초 경과: floor(10000ms * 53 / 180000ms) = tick 2 (요청의 gameTick=9999는 무시)
        assertEquals(2, response.getGameTick());
    }

    @Test
    @DisplayName("WAITING 상태에서는 게임 행동이 거절된다")
    void actionRejectedWhenWaiting() {
        stubCommon(createSession(EventSessionStatus.WAITING, null, null), createGameState(10_000_000L, 0L, 0L));

        assertThrows(
                EventNotStartedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 게임 행동이 거절된다")
    void actionRejectedWhenCountdown() {
        stubCommon(
                createSession(EventSessionStatus.COUNTDOWN, NOW.plusSeconds(3), NOW.plusSeconds(183)),
                createGameState(10_000_000L, 0L, 0L)
        );

        assertThrows(
                EventNotStartedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("FINISHED 상태에서는 게임 행동이 거절된다")
    void actionRejectedWhenFinished() {
        stubCommon(
                createSession(EventSessionStatus.FINISHED, NOW.minusSeconds(190), NOW.minusSeconds(10)),
                createGameState(10_000_000L, 0L, 0L)
        );

        assertThrows(
                EventAlreadyFinishedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("강제 종료로 endAt이 미래로 남아 있어도 FINISHED 상태면 행동이 거절된다")
    void actionRejectedWhenForceFinishedWithFutureEndAt() {
        stubCommon(
                createSession(EventSessionStatus.FINISHED, NOW.minusSeconds(10), NOW.plusSeconds(500)),
                createGameState(10_000_000L, 0L, 0L)
        );

        assertThrows(
                EventAlreadyFinishedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("status가 RUNNING이어도 서버 시각이 endAt 이후면 행동이 거절된다")
    void actionRejectedWhenPastEndAtEvenIfStillRunning() {
        stubCommon(
                createSession(EventSessionStatus.RUNNING, NOW.minusSeconds(190), NOW.minusSeconds(10)),
                createGameState(10_000_000L, 0L, 0L)
        );

        assertThrows(
                EventAlreadyFinishedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("게임 상태가 아직 생성되지 않았으면 행동이 거절된다")
    void actionRejectedWhenGameStateNotCreated() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createRunningSession());
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);

        assertThrows(
                EventNotStartedException.class,
                () -> service.saveAction(TOKEN, createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }

    @Test
    @DisplayName("유효하지 않은 participantToken은 거절된다")
    void actionRejectedWhenInvalidToken() {
        when(eventParticipantMapper.findByParticipantToken("unknown")).thenReturn(null);

        assertThrows(
                InvalidParticipantTokenException.class,
                () -> service.saveAction("unknown", createRequest("BUY", "STOCK", 1_000_000L), NOW)
        );
    }
}
