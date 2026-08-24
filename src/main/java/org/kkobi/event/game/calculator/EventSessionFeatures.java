package org.kkobi.event.game.calculator;

import java.math.BigDecimal;

// 이벤트 게임 성향 점수 산식의 입력 특징.
// 모든 카운트는 extractor가 raw 값을 저장하고 상한(min)은 점수 계산기가 적용한다.
public record EventSessionFeatures(
        BigDecimal avgStockRatio,
        BigDecimal avgCashRatio,
        int buyTickSpan,
        int crashHoldingEpisodes,
        int panicFullSellCount,
        int distinctBuyTicks,
        int distinctSellTicks,
        int rebuyWithin2TicksCount,
        int completedRoundTrips,
        int reentryAbsenceCount,
        boolean accumulationStreak,
        int lossAveragingBuyCount,
        int crashDipBuyCount,
        int bullChaseBuyCount,
        int gainAddBuyCount,
        int normalSplitBuyCount,
        int quickProfitTakeCount,
        int bullProfitTakeCount
) {
}
