package org.kkobi.event.game.calculator;

import java.math.BigDecimal;

// 이벤트 성향 판정 경계값 묶음. 기본값은 확정 산식(v3.2) 값이며,
// Phase 2 시뮬레이션 캘리브레이션(분할매수 하한 5% vs 10% 민감도 등)에서만 변경한다.
public record EventPersonaThresholds(
        BigDecimal splitBuyMinRatio,
        BigDecimal splitBuyMaxRatio,
        BigDecimal lossAveragingReturnRate
) {

    public static EventPersonaThresholds defaults() {
        return new EventPersonaThresholds(
                new BigDecimal("5"),
                new BigDecimal("30"),
                new BigDecimal("-15"));
    }

    // 분할매수 하한만 바꿔 민감도를 비교한다(예: 10%는 회원용 관례값).
    public EventPersonaThresholds withSplitBuyMinRatio(int splitBuyMinRatioPercent) {
        return new EventPersonaThresholds(
                BigDecimal.valueOf(splitBuyMinRatioPercent),
                splitBuyMaxRatio,
                lossAveragingReturnRate);
    }
}
