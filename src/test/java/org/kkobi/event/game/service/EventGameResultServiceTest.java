package org.kkobi.event.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.AssetRatioCalculator;
import org.kkobi.assessment.calculator.GameBehaviorAssessmentCalculator;
import org.kkobi.assessment.calculator.GameScoreCalculator;
import org.kkobi.assessment.calculator.MarketStateCalculator;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.calculator.SecurityPriceRateCalculator;
import org.kkobi.assessment.mapper.AssessmentMapper;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
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
import org.kkobi.game.calculator.GamePriceRateCalculator;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;
import org.kkobi.persona.dto.PersonaResponseDto;
import org.kkobi.persona.mapper.PersonaMapper;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventGameResultServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final String TOKEN = "token-123";

    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventSessionService eventSessionService = mock(EventSessionService.class);
    private final EventGameStateMapper eventGameStateMapper = mock(EventGameStateMapper.class);
    private final EventActionLogMapper eventActionLogMapper = mock(EventActionLogMapper.class);
    private final EventGameResultMapper eventGameResultMapper = mock(EventGameResultMapper.class);
    private final AssessmentMapper assessmentMapper = mock(AssessmentMapper.class);
    private final PersonaMapper personaMapper = mock(PersonaMapper.class);

    private EventGameResultService createService(ScenarioService scenarioService) {
        return new EventGameResultService(
                eventParticipantMapper,
                eventSessionService,
                eventGameStateMapper,
                eventActionLogMapper,
                eventGameResultMapper,
                scenarioService,
                new EventGameClockService(),
                new GameBehaviorAssessmentCalculator(
                        new AssetRatioCalculator(),
                        new MarketStateCalculator(),
                        new GamePriceRateCalculator(new SecurityPriceRateCalculator()),
                        new GameSecurityReturnCalculator()
                ),
                new GameScoreCalculator(),
                new PersonaClassifier(),
                assessmentMapper,
                personaMapper
        );
    }

    private EventParticipant createParticipant() {
        EventParticipant participant = new EventParticipant();
        participant.setParticipantId(1L);
        participant.setSessionId(10L);
        participant.setNickname("박지우");
        participant.setParticipantToken(TOKEN);
        return participant;
    }

    private EventSession createSession(
            EventSessionStatus status,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime finishedAt,
            long initialCash) {
        EventSession session = new EventSession();
        session.setSessionId(10L);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setStartAt(startAt);
        session.setEndAt(endAt);
        session.setFinishedAt(finishedAt);
        session.setDurationSeconds(180);
        session.setInitialCash(initialCash);
        return session;
    }

    // 5 tick(0~4)짜리 평탄한(변동률 0%) 커스텀 시나리오. 시장상태 관련 부가 규칙 오염 없이
    // DEPOSIT_MATURITY 등 완료 시점 규칙만 정밀하게 검증하기 위해 사용한다.
    private ScenarioDto createFlatScenario(List<Long> prices) {
        ScenarioDto scenario = new ScenarioDto();
        scenario.setScenarioId("SC001");
        scenario.setTotalTicks(prices.size());
        List<ScenarioTickDto> ticks = new ArrayList<>();
        for (int tick = 0; tick < prices.size(); tick++) {
            ScenarioTickDto tickDto = new ScenarioTickDto();
            tickDto.setTick(tick);
            tickDto.setChangeRate(0.0);
            tickDto.setPrice(prices.get(tick));
            ticks.add(tickDto);
        }
        scenario.setTicks(ticks);
        return scenario;
    }

    private EventGameState createGameState(long cash, long stockPrincipal, BigDecimal stockQuantity, long deposit) {
        return new EventGameState(
                100L, 1L, 10L, "SC001", 10_000_000L,
                cash, stockPrincipal, stockQuantity, deposit, null, null
        );
    }

    private EventActionLogDto createInitialAllocationLog(long cash, long stock, long deposit) {
        EventActionLogDto log = new EventActionLogDto();
        log.setActionLogId(1L);
        log.setParticipantId(1L);
        log.setSessionId(10L);
        log.setScenarioId("SC001");
        log.setGameTick(0);
        log.setActionType("INITIAL_ALLOCATION");
        log.setAssetType("ALL");
        log.setActionAmount(cash + stock + deposit);
        log.setMarketState("NORMAL");
        log.setDepositStatus(deposit > 0 ? "ACTIVE" : "NONE");
        log.setCurrentCash(cash);
        log.setCurrentStock(stock);
        log.setCurrentDeposit(deposit);
        return log;
    }

    private void stubUpToFinished(EventSession session, EventGameState gameState) {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(session);
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(gameState);
    }

    @Test
    @DisplayName("이미 결과가 있으면 재계산하지 않고 저장된 결과를 그대로 반환한다")
    void returnsExistingResultWithoutRecalculating() {
        EventGameResult existing = new EventGameResult(
                500L, 1L, 10L, 3L,
                10_000_000L, 10_842_000L, new BigDecimal("8.42"),
                new BigDecimal("60.00"), new BigDecimal("55.00"), new BigDecimal("52.00"),
                52, 13250L, NOW.minusSeconds(100), NOW.minusSeconds(90)
        );
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(existing);
        when(personaMapper.findPersonaById(3L))
                .thenReturn(personaOf(3L, "불꽃 추격자"));

        EventGameResultResponse response = createService(mock(ScenarioService.class))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(10_000_000L, response.getInitialAsset());
        assertEquals(10_842_000L, response.getFinalAsset());
        assertEquals(0, new BigDecimal("8.42").compareTo(response.getReturnRate()));
        assertEquals("불꽃 추격자", response.getPersonaName());
        verify(eventGameResultMapper, never()).saveResult(any());
        verify(eventSessionService, never()).getSynchronizedSession(any(), any());
        verify(eventGameStateMapper, never()).findByParticipantId(any());
    }

    @Test
    @DisplayName("유효하지 않은 participantToken은 거절된다")
    void rejectsInvalidToken() {
        when(eventParticipantMapper.findByParticipantToken("unknown")).thenReturn(null);

        assertThrows(
                InvalidParticipantTokenException.class,
                () -> createService(mock(ScenarioService.class)).getOrCreateResult("unknown", NOW)
        );
    }

    @Test
    @DisplayName("WAITING 상태에서는 결과 조회가 거절된다")
    void rejectsWhenWaiting() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.WAITING, null, null, null, 10_000_000L));

        assertThrows(
                EventNotStartedException.class,
                () -> createService(mock(ScenarioService.class)).getOrCreateResult(TOKEN, NOW)
        );
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 결과 조회가 거절된다")
    void rejectsWhenCountdown() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(
                createSession(EventSessionStatus.COUNTDOWN, NOW.plusSeconds(3), NOW.plusSeconds(183), null, 10_000_000L)
        );

        assertThrows(
                EventNotStartedException.class,
                () -> createService(mock(ScenarioService.class)).getOrCreateResult(TOKEN, NOW)
        );
    }

    @Test
    @DisplayName("RUNNING 상태에서는 결과 조회가 거절된다")
    void rejectsWhenRunning() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(
                createSession(EventSessionStatus.RUNNING, NOW.minusSeconds(10), NOW.plusSeconds(170), null, 10_000_000L)
        );

        assertThrows(
                EventNotFinishedException.class,
                () -> createService(mock(ScenarioService.class)).getOrCreateResult(TOKEN, NOW)
        );
    }

    @Test
    @DisplayName("FINISHED이지만 게임 진행 기록이 없으면 결과 조회가 거절된다")
    void rejectsWhenFinishedButNoGameState() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L)).thenReturn(null);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(
                createSession(EventSessionStatus.FINISHED, NOW.minusSeconds(190), NOW.minusSeconds(10), NOW.minusSeconds(10), 10_000_000L)
        );
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(null);

        assertThrows(
                EventNotStartedException.class,
                () -> createService(mock(ScenarioService.class)).getOrCreateResult(TOKEN, NOW)
        );
    }

    @Test
    @DisplayName("최종 평가자산은 stockPrincipal이 아니라 stockQuantity * finalPrice를 사용한다")
    void finalAssetUsesStockQuantityNotPrincipal() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 10_000_000L);
        // stockPrincipal(9,000,000)과 실제 평가액(stockQuantity 100주 * 20,000원 = 2,000,000원)을 크게 다르게 설정
        EventGameState gameState = createGameState(1_000_000L, 9_000_000L, new BigDecimal("100"), 0L);
        ScenarioDto scenario = createFlatScenario(List.of(20000L, 20000L, 20000L, 20000L, 20000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(1_000_000L, 9_000_000L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        // 1,000,000(현금) + 0(예금) + 100주 * 20,000원(2,000,000) = 3,000,000
        assertEquals(3_000_000L, response.getFinalAsset());
    }

    @Test
    @DisplayName("정상 종료(finishedAt == endAt)는 마지막 Tick 가격을 사용한다")
    void normalCompletionUsesLastTick() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 10_000_000L);
        EventGameState gameState = createGameState(10_000_000L, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 11000L, 12000L, 13000L, 14000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(10_000_000L, 0L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(4, response.getFinalTick());
        assertEquals(14000L, response.getFinalPrice());
    }

    @Test
    @DisplayName("강제 종료(finishedAt < endAt)는 진행된 시점의 Tick을 사용하며 마지막 Tick으로 잘못 계산하지 않는다")
    void forcedFinishMidGameUsesProgressedTick() {
        LocalDateTime startAt = NOW.minusSeconds(90);
        LocalDateTime endAt = NOW.plusSeconds(90);
        LocalDateTime finishedAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, finishedAt, 10_000_000L);
        EventGameState gameState = createGameState(10_000_000L, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 11000L, 12000L, 13000L, 14000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(10_000_000L, 0L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        // elapsed 90s / duration 180s * 5 tick = tick 2 (마지막 tick 4가 아니어야 한다)
        assertEquals(2, response.getFinalTick());
        assertEquals(12000L, response.getFinalPrice());
    }

    @Test
    @DisplayName("COUNTDOWN 중 강제 종료(finishedAt <= startAt)는 Tick 0으로 계산한다")
    void forcedFinishBeforeStartUsesTickZero() {
        LocalDateTime startAt = NOW.plusSeconds(3);
        LocalDateTime endAt = startAt.plusSeconds(180);
        LocalDateTime finishedAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, finishedAt, 10_000_000L);
        EventGameState gameState = createGameState(10_000_000L, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 11000L, 12000L, 13000L, 14000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(10_000_000L, 0L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(0, response.getFinalTick());
        assertEquals(10000L, response.getFinalPrice());
    }

    @Test
    @DisplayName("수익이 나면 수익률이 양수로 계산된다")
    void returnRateIsPositiveOnProfit() {
        assertReturnRate(12_000_000L, new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("손실이 나면 수익률이 음수로 계산된다")
    void returnRateIsNegativeOnLoss() {
        assertReturnRate(8_000_000L, new BigDecimal("-20.00"));
    }

    @Test
    @DisplayName("자산 변화가 없으면 수익률은 0%이다")
    void returnRateIsZeroWhenUnchanged() {
        assertReturnRate(10_000_000L, new BigDecimal("0.00"));
    }

    private void assertReturnRate(long finalCash, BigDecimal expectedReturnRate) {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 10_000_000L);
        EventGameState gameState = createGameState(finalCash, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 10000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(finalCash, 0L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(0, expectedReturnRate.compareTo(response.getReturnRate()));
    }

    @Test
    @DisplayName("초기 예금이 있고 DEPOSIT_CANCEL이 없으면 DEPOSIT_MATURITY(-5/-10/-5)가 최종 점수에 반영된다")
    void depositMaturityAppliedWhenDepositHeldWithoutCancel() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 1_000_000L);
        // cash 10% / stock 60% / deposit 30% -> 초기 배분 규칙(70%/50%/30% 임계값)과
        // 현금버퍼(25~50%)·위험예산(현금 25~40%) 유지 규칙 임계값을 모두 피해 MATURITY만 단독으로 관찰한다
        EventGameState gameState = createGameState(100_000L, 600_000L, BigDecimal.ZERO, 300_000L);
        ScenarioDto scenario = createFlatScenario(List.of(1000L, 1000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(100_000L, 600_000L, 300_000L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(0, new BigDecimal("45.00").compareTo(response.getRtScore()));
        assertEquals(0, new BigDecimal("40.00").compareTo(response.getLhScore()));
        assertEquals(0, new BigDecimal("45.00").compareTo(response.getRpScore()));
    }

    @Test
    @DisplayName("초기 예금이 있어도 DEPOSIT_CANCEL이 있으면 DEPOSIT_MATURITY가 반영되지 않는다")
    void depositMaturityNotAppliedWhenCancelled() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 1_000_000L);
        EventGameState gameState = createGameState(400_000L, 600_000L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(1000L, 1000L, 1000L));

        EventActionLogDto initialAllocation = createInitialAllocationLog(100_000L, 600_000L, 300_000L);
        EventActionLogDto cancelLog = new EventActionLogDto();
        cancelLog.setActionLogId(2L);
        cancelLog.setParticipantId(1L);
        cancelLog.setSessionId(10L);
        cancelLog.setScenarioId("SC001");
        cancelLog.setGameTick(1);
        cancelLog.setActionType("DEPOSIT_CANCEL");
        cancelLog.setAssetType("DEPOSIT");
        cancelLog.setActionAmount(300_000L);
        cancelLog.setMarketState("NORMAL");
        cancelLog.setDepositStatus("CANCELLED");
        cancelLog.setCurrentCash(400_000L);
        cancelLog.setCurrentStock(600_000L);
        cancelLog.setCurrentDeposit(0L);

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(initialAllocation, cancelLog));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        // DEPOSIT_MATURITY(-5/-10/-5)는 적용되지 않는다. 대신 해지 직후 2 Tick 내 현금을
        // 80% 이상 유지한 것으로 판단되어 DEPOSIT_CANCEL_CASH_RETENTION(-5/+10/-5)이 적용된다.
        assertEquals(0, new BigDecimal("45.00").compareTo(response.getRtScore()));
        assertEquals(0, new BigDecimal("60.00").compareTo(response.getLhScore()));
        assertEquals(0, new BigDecimal("45.00").compareTo(response.getRpScore()));
    }

    @Test
    @DisplayName("초기 예금이 0이면 DEPOSIT_MATURITY가 반영되지 않는다")
    void depositMaturityNotAppliedWhenNoInitialDeposit() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 1_000_000L);
        // cash 20% / stock 80% / deposit 0% -> stockRatio 80% >= 70%로 INITIAL_STOCK_ALLOCATION만 적용된다
        EventGameState gameState = createGameState(200_000L, 800_000L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(1000L, 1000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(200_000L, 800_000L, 0L)));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        // INITIAL_STOCK_ALLOCATION(+10/-5/+5)만 적용되어 (60,45,55)가 된다. MATURITY(-5/-10/-5)는 없다.
        assertEquals(0, new BigDecimal("60.00").compareTo(response.getRtScore()));
        assertEquals(0, new BigDecimal("45.00").compareTo(response.getLhScore()));
        assertEquals(0, new BigDecimal("55.00").compareTo(response.getRpScore()));
    }

    @Test
    @DisplayName("최종 RT/LH/RP가 PersonaClassifier를 통해 올바른 personaId로 매핑된다")
    void mapsFinalScoresToPersonaViaPersonaClassifier() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 1_000_000L);
        // (45,40,45) 전원 50 미만 -> LLL
        EventGameState gameState = createGameState(100_000L, 600_000L, BigDecimal.ZERO, 300_000L);
        ScenarioDto scenario = createFlatScenario(List.of(1000L, 1000L));

        stubUpToFinished(session, gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(100_000L, 600_000L, 300_000L)));
        when(assessmentMapper.getPersonaIdByAxisCode("LLL")).thenReturn(9L);
        when(personaMapper.findPersonaById(9L)).thenReturn(personaOf(9L, "안전 지향형"));

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(9L, response.getPersonaId());
        assertEquals("안전 지향형", response.getPersonaName());
    }

    @Test
    @DisplayName("두 번째 요청은 재계산 없이 첫 번째 요청에서 저장된 결과를 반환한다")
    void secondRequestReturnsFirstSavedResultWithoutRecalculating() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 10_000_000L);
        EventGameState gameState = createGameState(10_000_000L, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 10000L));

        EventGameResult saved = new EventGameResult(
                700L, 1L, 10L, 5L,
                10_000_000L, 10_000_000L, BigDecimal.ZERO,
                new BigDecimal("50.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
                1, 10000L, endAt, endAt
        );

        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant());
        when(eventGameResultMapper.findByParticipantId(1L))
                .thenReturn(null)
                .thenReturn(saved);
        when(eventSessionService.getSynchronizedSession(eq(10L), eq(NOW))).thenReturn(session);
        when(eventGameStateMapper.findByParticipantId(1L)).thenReturn(gameState);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(10_000_000L, 0L, 0L)));
        stubPersonaLookup();

        EventGameResultService service = createService(stubbedScenarioService(scenario));

        EventGameResultResponse first = service.getOrCreateResult(TOKEN, NOW);
        EventGameResultResponse second = service.getOrCreateResult(TOKEN, NOW);

        assertEquals(first.getFinalAsset(), second.getFinalAsset());
        verify(eventGameResultMapper, org.mockito.Mockito.times(1)).saveResult(any());
    }

    @Test
    @DisplayName("동시 요청으로 DB UNIQUE 제약 위반이 발생하면 기존 결과를 재조회해 반환한다")
    void returnsExistingResultWhenSaveThrowsDuplicateKeyException() {
        LocalDateTime startAt = NOW.minusSeconds(180);
        LocalDateTime endAt = NOW;
        EventSession session = createSession(EventSessionStatus.FINISHED, startAt, endAt, endAt, 10_000_000L);
        EventGameState gameState = createGameState(10_000_000L, 0L, BigDecimal.ZERO, 0L);
        ScenarioDto scenario = createFlatScenario(List.of(10000L, 10000L));

        EventGameResult concurrentlySaved = new EventGameResult(
                701L, 1L, 10L, 5L,
                10_000_000L, 10_000_000L, BigDecimal.ZERO,
                new BigDecimal("50.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
                1, 10000L, endAt, endAt
        );

        stubUpToFinished(session, gameState);
        when(eventGameResultMapper.findByParticipantId(1L))
                .thenReturn(null)
                .thenReturn(concurrentlySaved);
        when(eventActionLogMapper.getActionLogsByParticipantId(1L))
                .thenReturn(List.of(createInitialAllocationLog(10_000_000L, 0L, 0L)));
        when(eventGameResultMapper.saveResult(any())).thenThrow(new DuplicateKeyException("duplicate"));
        stubPersonaLookup();

        EventGameResultResponse response = createService(stubbedScenarioService(scenario))
                .getOrCreateResult(TOKEN, NOW);

        assertEquals(701L, concurrentlySaved.getResultId());
        assertEquals(10_000_000L, response.getFinalAsset());
    }

    private ScenarioService stubbedScenarioService(ScenarioDto scenario) {
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.getScenario("SC001")).thenReturn(scenario);
        return scenarioService;
    }

    private void stubPersonaLookup() {
        when(assessmentMapper.getPersonaIdByAxisCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        when(personaMapper.findPersonaById(1L)).thenReturn(personaOf(1L, "테스트 페르소나"));
    }

    private PersonaResponseDto personaOf(Long personaId, String name) {
        PersonaResponseDto persona = new PersonaResponseDto();
        persona.setPersonaId(personaId);
        persona.setPersonaName(name);
        return persona;
    }
}
