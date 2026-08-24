package org.kkobi.event.game.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.service.ScenarioService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 재진입 이중 정의 분리 검증 매트릭스(T1~T7)와 왕복 완결(RT-A~F) 단위 테스트.
// 두 필드가 서로 다른 계산 경로에서 나오는지 각 행의 값을 개별 assert한다.
class EventPersonaFeatureExtractorTest {

    private static final long INITIAL_CASH = 10_000_000L;

    private final EventPersonaFeatureExtractor extractor = new EventPersonaFeatureExtractor(
            new RecentExtremaMarketStateCalculator(),
            new GameSecurityReturnCalculator()
    );
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    private ActionLogDto initialAllocation() {
        ActionLogDto dto = new ActionLogDto();
        dto.setActionLogId(0L);
        dto.setGameTick(0);
        dto.setActionType("INITIAL_ALLOCATION");
        dto.setAssetType("ALL");
        dto.setActionAmount(INITIAL_CASH);
        dto.setCurrentCash(INITIAL_CASH);
        dto.setCurrentStock(0L);
        dto.setCurrentDeposit(0L);
        return dto;
    }

    private ActionLogDto trade(long id, int tick, String type, long amount) {
        ActionLogDto dto = new ActionLogDto();
        dto.setActionLogId(id);
        dto.setGameTick(tick);
        dto.setActionType(type);
        dto.setAssetType("STOCK");
        dto.setActionAmount(amount);
        // 잔액 필드는 분할매수 비율 판정에만 쓰인다. 0으로 두면 비율 판정이 결정론적으로 중립이 된다.
        dto.setCurrentCash(0L);
        dto.setCurrentStock(0L);
        dto.setCurrentDeposit(0L);
        return dto;
    }

    private EventExtractionResult extract(int finalTick, ActionLogDto... trades) {
        java.util.ArrayList<ActionLogDto> logs = new java.util.ArrayList<>();
        logs.add(initialAllocation());
        logs.addAll(java.util.Arrays.asList(trades));
        return extractor.extract(scenario, logs, finalTick);
    }

    @Test
    @DisplayName("T1: 매도 후 무행동(잔여 >=2틱) -> rebuy 0 / absence 1")
    void t1_sellThenIdle() {
        EventExtractionResult result = extract(8,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 5, "SELL", 2_000_000L));

        assertEquals(0, result.features().rebuyWithin2TicksCount());
        assertEquals(1, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T2: 매도 후 t+1~t+2 재매수 >=50% -> rebuy 1 / absence 0")
    void t2_quickRebuy() {
        EventExtractionResult result = extract(8,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 4, "SELL", 2_000_000L),
                trade(3L, 6, "BUY", 1_500_000L));

        assertEquals(1, result.features().rebuyWithin2TicksCount());
        assertEquals(0, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T3: 동일 틱 소액(<50%) 재매수 후 없음 -> rebuy 0 / absence 1 (윈도우 시작점 차이로 갈림)")
    void t3_sameTickSmallRebuy() {
        EventExtractionResult result = extract(7,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 4, "SELL", 2_000_000L),
                trade(3L, 4, "BUY", 800_000L));

        assertEquals(0, result.features().rebuyWithin2TicksCount());
        assertEquals(1, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T4: 동일 틱 재매수 >=50% 이후 없음 -> rebuy 1 / absence 1 (양쪽 모두 발화)")
    void t4_sameTickLargeRebuy() {
        EventExtractionResult result = extract(7,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 4, "SELL", 2_000_000L),
                trade(3L, 4, "BUY", 1_200_000L));

        assertEquals(1, result.features().rebuyWithin2TicksCount());
        assertEquals(1, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T5: 매도 후 3틱 뒤 재매수 -> rebuy 0 (윈도우 밖) / absence 0 (재진입 존재)")
    void t5_lateRebuy() {
        EventExtractionResult result = extract(9,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 4, "SELL", 2_000_000L),
                trade(3L, 7, "BUY", 2_000_000L));

        assertEquals(0, result.features().rebuyWithin2TicksCount());
        assertEquals(0, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T6: 종료 직전 매도 후 무행동(잔여 <2틱) -> rebuy 0 / absence 0 (관찰 여유 가드)")
    void t6_noObservationMargin() {
        EventExtractionResult result = extract(8,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 7, "SELL", 2_000_000L));

        assertEquals(0, result.features().rebuyWithin2TicksCount());
        assertEquals(0, result.features().reentryAbsenceCount());
    }

    @Test
    @DisplayName("T7: 매도 2회(첫째 빠른 재매수 + 둘째 종료 직전) -> rebuy 1 (매도별 집계) / absence 0 (세션 가드)")
    void t7_multipleSells() {
        EventExtractionResult result = extract(8,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 4, "SELL", 2_000_000L),
                trade(3L, 5, "BUY", 1_500_000L),
                trade(4L, 7, "SELL", 1_000_000L));

        assertEquals(1, result.features().rebuyWithin2TicksCount());
        assertEquals(0, result.features().reentryAbsenceCount());
    }

    // ── 왕복 완결(RT): 프론트(eventGameTrade.js)는 정수 주 단위로만 주문하므로
    // amount = N × price 꼴이고 수량은 항상 정수다. 전량 매도 잔여는 정확히 0이다. ──

    @Test
    @DisplayName("RT-A: 정수 주 단위 단순 왕복 -> 잔여 정확히 0, 왕복 1회 인정")
    void rtA_simpleRoundTrip() {
        // BUY 99주 x 20,080 = 1_987_920 / SELL 99주 x 19,960 = 1_976_040
        EventExtractionResult result = extract(5,
                trade(1L, 2, "BUY", 99L * 20080),
                trade(2L, 3, "SELL", 99L * 19960));

        assertEquals(1, result.features().completedRoundTrips());
    }

    @Test
    @DisplayName("RT-B: 부분 매도 후 잔여 전량 매도 -> 두 번째 매도에서 왕복 완결 1회")
    void rtB_partialThenFullExit() {
        // BUY 99주 -> SELL 30주(잔여 69주 미청산) -> SELL 69주(잔여 0)
        EventExtractionResult result = extract(6,
                trade(1L, 2, "BUY", 99L * 20080),
                trade(2L, 3, "SELL", 30L * 19960),
                trade(3L, 4, "SELL", 69L * 20140));

        assertEquals(1, result.features().completedRoundTrips());
    }

    @Test
    @DisplayName("RT-C: 복수 매수 누적 후 단건 매도로 청산 -> 왕복 1회")
    void rtC_accumulatedBuysSingleSell() {
        // BUY 49주 + 51주 = 100주 -> SELL 100주
        EventExtractionResult result = extract(6,
                trade(1L, 2, "BUY", 49L * 20080),
                trade(2L, 3, "BUY", 51L * 19960),
                trade(3L, 4, "SELL", 100L * 20140));

        assertEquals(1, result.features().completedRoundTrips());
    }

    @Test
    @DisplayName("RT-D/E: 청산되지 않은 포지션은 미완료 -> 왕복 0회")
    void rtD_openPositionNotComplete() {
        EventExtractionResult partialExit = extract(5,
                trade(1L, 2, "BUY", 99L * 20080),
                trade(2L, 3, "SELL", 30L * 19960));
        EventExtractionResult neverSold = extract(5,
                trade(1L, 2, "BUY", 99L * 20080));

        assertEquals(0, partialExit.features().completedRoundTrips());
        assertEquals(0, neverSold.features().completedRoundTrips());
    }

    @Test
    @DisplayName("RT-F: 왕복 3회 완성 -> raw 카운트는 3 (상한 적용은 점수 계산기 몫)")
    void rtF_threeRoundTripsRawCount() {
        EventExtractionResult result = extract(8,
                // 1왕복: 99주(t2) -> 99주(t3)
                trade(1L, 2, "BUY", 99L * 20080),
                trade(2L, 3, "SELL", 99L * 19960),
                // 2왕복: 50주(t4) -> 50주(t5)
                trade(3L, 4, "BUY", 50L * 20140),
                trade(4L, 5, "SELL", 50L * 20220),
                // 3왕복: 80주(t6) -> 80주(t7)
                trade(5L, 6, "BUY", 80L * 20120),
                trade(6L, 7, "SELL", 80L * 20280));

        assertEquals(3, result.features().completedRoundTrips());
    }

    @Test
    @DisplayName("RT-G: API 직접 호출로 생긴 소수점 포지션의 부분 청산은 완결로 인정하지 않는다")
    void rtG_fractionalPositionNotComplete() {
        // 금액 기반 매수(API 조작 시나리오): 2_000_000 / 20,080 = 99.60159363주
        // 이후 정수 99주만 매도하면 잔여 0.60159363주 >= 허용치 -> 미완료
        EventExtractionResult result = extract(5,
                trade(1L, 2, "BUY", 2_000_000L),
                trade(2L, 3, "SELL", 99L * 19960));

        assertEquals(0, result.features().completedRoundTrips());
        assertEquals(0, result.finalQuantity()
                .compareTo(new java.math.BigDecimal("99.60159363").subtract(java.math.BigDecimal.valueOf(99))));
    }

    @Test
    @DisplayName("급락 틱에서 정수 주 전량 매도 -> panicFullSellCount 1회 집계(허용치 일관 적용)")
    void panicFullSellDetectedDuringCrash() {
        // SC001 실제 가격: t10=20460, t22=18960(급락 구간 t20~27)
        // BUY t10 100주 x 20,460 = 2_046_000 / SELL t22 100주 x 18,960 = 1_896_000
        EventExtractionResult result = extract(25,
                trade(1L, 10, "BUY", 100L * 20460),
                trade(2L, 22, "SELL", 100L * 18960));

        assertEquals(1, result.features().panicFullSellCount());
        assertEquals(0, result.features().crashHoldingEpisodes());
    }
}
