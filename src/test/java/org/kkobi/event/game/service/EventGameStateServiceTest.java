package org.kkobi.event.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.AssetRatioCalculator;
import org.kkobi.assessment.calculator.BehaviorContextFactory;
import org.kkobi.assessment.calculator.BehaviorRuleEngine;
import org.kkobi.assessment.calculator.MarketStateCalculator;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.dto.response.EventGameStatusResponse;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.service.EventSessionService;
import org.kkobi.game.dto.GameStartRequest;
import org.kkobi.game.service.ScenarioService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventGameStateServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final String TOKEN = "token-123";

    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventSessionService eventSessionService = mock(EventSessionService.class);
    private final EventGameStateMapper eventGameStateMapper = mock(EventGameStateMapper.class);
    private final EventActionLogMapper eventActionLogMapper = mock(EventActionLogMapper.class);

    private final EventGameStateService service = new EventGameStateService(
            eventParticipantMapper,
            eventSessionService,
            eventGameStateMapper,
            eventActionLogMapper,
            new ScenarioService(),
            new EventGameClockService(),
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

    private EventSession createSession(long initialCash) {
        EventSession session = new EventSession();
        session.setSessionId(10L);
        session.setStatus(EventSessionStatus.RUNNING);
        session.setScenarioId("SC001");
        session.setStartAt(NOW.minusSeconds(10));
        session.setEndAt(NOW.plusSeconds(170));
        session.setDurationSeconds(180);
        session.setInitialCash(initialCash);
        return session;
    }

    private GameStartRequest createRequest(String cashRatio, String stockRatio, String depositRatio) {
        GameStartRequest request = new GameStartRequest();
        request.setCashRatio(new BigDecimal(cashRatio));
        request.setStockRatio(new BigDecimal(stockRatio));
        request.setDepositRatio(new BigDecimal(depositRatio));
        return request;
    }

    @Test
    @DisplayName("게임 상태가 없으면 eventSession.initialCash 전액을 현금으로 초기 자산을 생성한다")
    void ensureGameStateCreatesInitialAllocationFromSessionInitialCash() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(5_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventGameStateMapper.saveGameState(any(EventGameState.class))).thenAnswer(invocation -> {
            EventGameState gameState = invocation.getArgument(0);
            gameState.setGameStateId(100L);
            return 1;
        });

        EventGameStatusResponse response = service.ensureGameState(TOKEN, createRequest("100", "0", "0"), NOW);

        assertEquals(5_000_000L, response.getInitialCash());
        assertEquals(5_000_000L, response.getCashBalance());
        assertEquals(0L, response.getStockPrincipal());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getStockQuantity()));
        assertEquals(0L, response.getDepositAmount());
        assertEquals(5_000_000L, response.getTotalAssetPrincipal());
        assertEquals(5_000_000L, response.getTotalAssetValue());
        verify(eventGameStateMapper, times(1)).saveGameState(any(EventGameState.class));
        verify(eventActionLogMapper, times(1)).saveActionLog(any());
    }

    @Test
    @DisplayName("initialCash가 10,000,000원인 세션은 총자산 원금/평가액이 10,000,000원으로 생성된다")
    void ensureGameStateCreatesTenMillionInitialAssetForDefaultSession() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventGameStateMapper.saveGameState(any(EventGameState.class))).thenAnswer(invocation -> {
            EventGameState gameState = invocation.getArgument(0);
            gameState.setGameStateId(100L);
            return 1;
        });

        EventGameStatusResponse response = service.ensureGameState(TOKEN, createRequest("100", "0", "0"), NOW);

        assertEquals(10_000_000L, response.getCashBalance());
        assertEquals(10_000_000L, response.getTotalAssetPrincipal());
        assertEquals(10_000_000L, response.getTotalAssetValue());
    }

    @Test
    @DisplayName("클라이언트가 100/0/0이 아닌 비율을 보내면 초기 자산 배분을 조작할 수 없고 요청이 거절된다")
    void ensureGameStateRejectsClientControlledRatios() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.ensureGameState(TOKEN, createRequest("0", "100", "0"), NOW)
        );
        verify(eventGameStateMapper, never()).saveGameState(any());
    }

    @Test
    @DisplayName("이미 게임 상태가 있으면 재요청 시 중복 생성하지 않고 기존 상태를 반환한다")
    void ensureGameStateReusesExistingStateOnRetry() {
        EventGameState existing = new EventGameState(
                100L, 1L, 10L, "SC001", 10_000_000L,
                2_000_000L, 5_000_000L, new BigDecimal("200"), 3_000_000L, null, null
        );
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(existing);

        EventGameStatusResponse response = service.ensureGameState(TOKEN, createRequest("20", "50", "30"), NOW);

        assertEquals(2_000_000L, response.getCashBalance());
        assertEquals(5_000_000L, response.getStockPrincipal());
        assertEquals(0, new BigDecimal("200").compareTo(response.getStockQuantity()));
        assertEquals(3_000_000L, response.getDepositAmount());
        verify(eventGameStateMapper, never()).saveGameState(any());
        verify(eventActionLogMapper, never()).saveActionLog(any());
    }

    @Test
    @DisplayName("100/0/0(현금 100%)이 아닌 비율 조합은 게임 상태를 생성하지 않는다")
    void ensureGameStateRejectsInvalidRatioSum() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.ensureGameState(TOKEN, createRequest("20", "50", "20"), NOW)
        );
        verify(eventGameStateMapper, never()).saveGameState(any());
    }

    @Test
    @DisplayName("유효하지 않은 participantToken은 거절된다")
    void ensureGameStateRejectsInvalidToken() {
        when(eventParticipantMapper.findByParticipantToken("unknown")).thenReturn(null);

        assertThrows(
                InvalidParticipantTokenException.class,
                () -> service.ensureGameState("unknown", createRequest("20", "50", "30"), NOW)
        );
    }

    @Test
    @DisplayName("게임 상태 생성 전에 조회하면 아직 시작되지 않은 것으로 처리한다")
    void getStatusRejectsWhenGameStateNotCreatedYet() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);

        assertThrows(EventNotStartedException.class, () -> service.getStatus(TOKEN, NOW));
    }

    @Test
    @DisplayName("게임 상태 조회 시 서버 기준 currentTick과 currentPrice를 함께 반환한다")
    void getStatusReturnsServerCalculatedTickAndPrice() {
        EventGameState existing = new EventGameState(
                100L, 1L, 10L, "SC001", 10_000_000L,
                10_000_000L, 0L, BigDecimal.ZERO, 0L, null, null
        );
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(existing);

        EventGameStatusResponse response = service.getStatus(TOKEN, NOW);

        assertEquals(53, response.getTotalTickCount());
        assertEquals("RUNNING", response.getEventStatus());
        // startAt 10초 경과: floor(10000ms * 53 / 180000ms) = tick 2 (SC001 tick 2 가격 20600)
        assertEquals(2, response.getCurrentTick());
        assertEquals(20600L, response.getCurrentPrice());
        // 보유 수량이 0이므로 평가자산은 현금+예금(10,000,000원)과 동일하다
        assertEquals(10_000_000L, response.getTotalAssetValue());
    }

    @Test
    @DisplayName("보유 수량이 있으면 stock_quantity * currentPrice가 평가자산에 반영된다")
    void getStatusIncludesStockValuationInTotalAssetValue() {
        // 보유 수량 100주, tick2 가격 20600원 -> 평가액 2,060,000원
        EventGameState existing = new EventGameState(
                100L, 1L, 10L, "SC001", 10_000_000L,
                7_000_000L, 2_000_000L, new BigDecimal("100"), 1_000_000L, null, null
        );
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(createSession(10_000_000L));
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(existing);

        EventGameStatusResponse response = service.getStatus(TOKEN, NOW);

        assertEquals(20600L, response.getCurrentPrice());
        // 7,000,000(현금) + 1,000,000(예금) + 100주 * 20,600원(평가액 2,060,000) = 10,060,000
        assertEquals(10_060_000L, response.getTotalAssetValue());
    }
}
