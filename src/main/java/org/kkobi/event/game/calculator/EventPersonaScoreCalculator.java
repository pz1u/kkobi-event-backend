package org.kkobi.event.game.calculator;

import org.kkobi.assessment.domain.AssessmentScore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 이벤트 전용 성향 점수 계산기 (확정 산식 v3.2).
// - 세 축 모두 clamp(20, 80): 결과 화면 표시 일관성을 위한 통일 범위
// - RP_PRIOR=45는 축 정의 상수("증거 없음 = 중립 −5")이며 Phase 2 조정 대상에서 제외된다.
//   조정은 rpRule의 base/p95/cap에서만 수행한다.
// - RP 반복 제어는 규칙별 log1p 감쇠 + cap이 담당하고 최종 clamp는 최후 방어선이다.
// - 무행동 참가자는 특별 분기 없이 산식 결과로 자연히 LHL이 나온다(테스트로 보장).
@Component
public class EventPersonaScoreCalculator {

    private static final BigDecimal BASELINE_SCORE = BigDecimal.valueOf(50);
    private static final BigDecimal RP_PRIOR_SCORE = BigDecimal.valueOf(45);
    private static final BigDecimal SCORE_FLOOR = BigDecimal.valueOf(20);
    private static final BigDecimal SCORE_CEILING = BigDecimal.valueOf(80);
    private static final int SCORE_SCALE = 2;

    // RT: 위험 감내 — 평균 주식 노출 + 급락 홀딩(+)/공포 풀매도(−)
    private static final BigDecimal RT_STOCK_WEIGHT = new BigDecimal("0.35");
    private static final int CRASH_HOLD_MAX_EPISODES = 2;
    private static final int CRASH_HOLD_POINTS_PER_EPISODE = 3;
    private static final int PANIC_SELL_MAX_COUNT = 2;
    private static final int PANIC_SELL_PENALTY_PER_COUNT = 3;

    // LH: 유동성 선호 — 평균 현금 비중, 매도·왕복(+), 회수 없는 순수 투입·즉시 재매수(−)
    // 가중치는 캘리브레이션(v3.4)에서 0.15로 확정: 고현금 저활동 참가자의 LH 구성 편중을 완화해
    // 현실 모델 독점률(LHL 54% -> 50% 미만)을 만족시키면서 P1~P7 마진을 모두 유지한다.
    private static final BigDecimal LH_CASH_WEIGHT = new BigDecimal("0.15");
    // netPhase 페널티는 세션 전반(10틱 이상)에 걸쳐 배분만 반복한 경우에 적용한다.
    // 짧은 구간의 소수 진입은 초기 진입으로 보아 페널티 대상에서 제외한다.
    private static final int NET_BUY_PHASE_MIN_SPAN_TICKS = 10;
    private static final int NET_BUY_PHASE_MAX = 4;
    private static final int NET_BUY_PHASE_POINTS = 2;
    private static final int SELL_TICK_CAP = 5;
    private static final int SELL_TICK_POINTS_PER_TICK = 2;
    private static final int QUICK_REBUY_MAX_COUNT = 2;
    private static final int QUICK_REBUY_PENALTY_PER_COUNT = 2;
    private static final int ROUND_TRIP_CAP = 3;
    private static final int ROUND_TRIP_POINTS_PER_TRIP = 3;

    // RP 규칙표 (base / p95 / cap). 세션 1회 한정 규칙은 p95=1·cap=1로 표현한다.
    // 캘리브레이션(v3.3): 양·음 경로 모두 판정 마진 3~5점을 확보하도록 cap을 증폭했다.
    private static final RpRule LOSS_AVERAGING_BUY_RULE = new RpRule(-2, 2, 1.5);      // 손실 물타기
    private static final RpRule CRASH_DIP_BUY_RULE = new RpRule(2, 3, 2.0);            // 급락 역발 매수
    private static final RpRule BULL_CHASE_BUY_RULE = new RpRule(2, 3, 2.5);           // 급등 추격 매수
    private static final RpRule GAIN_ADD_BUY_RULE = new RpRule(2, 3, 2.0);             // 수익 중 추가 매수
    private static final RpRule PLANNED_SPLIT_BUY_RULE = new RpRule(1, 6, 6.0);        // 정상장 분할 매수
    private static final RpRule PANIC_FULL_SELL_RULE = new RpRule(-2, 1, 1.0);         // 패닉 풀매도 (세션 1회)
    private static final RpRule QUICK_PROFIT_TAKE_RULE = new RpRule(-2, 3, 2.0);       // 신속 익절
    private static final RpRule BULL_PROFIT_SELL_RULE = new RpRule(-1, 2, 2.0);        // 급등 익절
    private static final RpRule REENTRY_ABSENCE_RULE = new RpRule(-1, 1, 1.0);         // 재진입 없음 (세션 1회)
    private static final RpRule ACCUMULATION_STREAK_RULE = new RpRule(2, 1, 1.0);      // 적립 연속 (세션 1회)

    public AssessmentScore calculate(EventSessionFeatures features) {
        if (features == null) {
            throw new IllegalArgumentException("이벤트 세션 특징은 필수입니다.");
        }
        return new AssessmentScore(
                clamp(rtScore(features)),
                clamp(lhScore(features)),
                clamp(rpScore(features)));
    }

    // RT = 50 + 0.35×(평균 주식 비중 − 50) + 급락 홀딩 보너스 − 공포 풀매도 페널티
    private BigDecimal rtScore(EventSessionFeatures features) {
        return BASELINE_SCORE.add(RT_STOCK_WEIGHT.multiply(features.avgStockRatio().subtract(BASELINE_SCORE)))
                .add(cappedPoints(
                        Math.min(features.crashHoldingEpisodes(), CRASH_HOLD_MAX_EPISODES),
                        CRASH_HOLD_POINTS_PER_EPISODE))
                .subtract(cappedPoints(
                        Math.min(features.panicFullSellCount(), PANIC_SELL_MAX_COUNT),
                        PANIC_SELL_PENALTY_PER_COUNT));
    }

    // LH = 50 + 0.20×(평균 현금 비중 − 50) − 순수 미회수 투입 + 매도 실행 − 즉시 재매수 + 왕복 완결
    // netPhase: 매수 틱에서 매도 틱을 뺀 "회수 없이 쌓인 투입"만 페널티로 본다.
    // 회전형(사고 팔기를 반복)은 상쇄되고, 세션 전반에 걸쳐 배분만 반복하면 강하게 깎인다.
    // 단, 매수 활동 구간(span)이 10틱 미만이면 초기 진입으로 보아 페널티를 적용하지 않는다.
    private BigDecimal lhScore(EventSessionFeatures features) {
        int netBuyPhases = 0;
        if (features.buyTickSpan() >= NET_BUY_PHASE_MIN_SPAN_TICKS) {
            netBuyPhases = Math.min(
                    Math.max(features.distinctBuyTicks() - features.distinctSellTicks(), 0),
                    NET_BUY_PHASE_MAX);
        }
        return BASELINE_SCORE.add(LH_CASH_WEIGHT.multiply(features.avgCashRatio().subtract(BASELINE_SCORE)))
                .subtract(cappedPoints(netBuyPhases, NET_BUY_PHASE_POINTS))
                .add(cappedPoints(Math.min(features.distinctSellTicks(), SELL_TICK_CAP), SELL_TICK_POINTS_PER_TICK))
                .subtract(cappedPoints(
                        Math.min(features.rebuyWithin2TicksCount(), QUICK_REBUY_MAX_COUNT),
                        QUICK_REBUY_PENALTY_PER_COUNT))
                .add(cappedPoints(Math.min(features.completedRoundTrips(), ROUND_TRIP_CAP), ROUND_TRIP_POINTS_PER_TRIP));
    }

    // RP = 45(증거 없음 prior) + Σ (base × log1p 감쇠 × cap)
    private BigDecimal rpScore(EventSessionFeatures features) {
        BigDecimal score = RP_PRIOR_SCORE;
        score = score.add(diminished(features.lossAveragingBuyCount(), LOSS_AVERAGING_BUY_RULE));
        score = score.add(diminished(features.crashDipBuyCount(), CRASH_DIP_BUY_RULE));
        score = score.add(diminished(features.bullChaseBuyCount(), BULL_CHASE_BUY_RULE));
        score = score.add(diminished(features.gainAddBuyCount(), GAIN_ADD_BUY_RULE));
        score = score.add(diminished(features.normalSplitBuyCount(), PLANNED_SPLIT_BUY_RULE));
        score = score.add(diminished(features.quickProfitTakeCount(), QUICK_PROFIT_TAKE_RULE));
        score = score.add(diminished(features.bullProfitTakeCount(), BULL_PROFIT_SELL_RULE));
        score = score.add(diminished(features.panicFullSellCount(), PANIC_FULL_SELL_RULE));
        score = score.add(diminished(features.reentryAbsenceCount(), REENTRY_ABSENCE_RULE));
        score = score.add(diminished(features.accumulationStreak() ? 1 : 0, ACCUMULATION_STREAK_RULE));
        return score;
    }

    // 기존 GameBehaviorAssessmentCalculator.diminish()와 동일한 log1p 체감 방식.
    // n이 p95에 도달하면 weight=1이 되어 이후 반복은 cap까지만 기여한다.
    private BigDecimal diminished(int count, RpRule rule) {
        if (count <= 0) {
            return BigDecimal.ZERO;
        }
        int effectiveCount = Math.min(count, rule.p95());
        double weight = Math.log1p(effectiveCount) / Math.log1p(rule.p95());
        return BigDecimal.valueOf(rule.base())
                .multiply(BigDecimal.valueOf(weight))
                .multiply(BigDecimal.valueOf(rule.cap()))
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal cappedPoints(int cappedCount, int pointsPerUnit) {
        return BigDecimal.valueOf((long) cappedCount * pointsPerUnit);
    }

    private BigDecimal clamp(BigDecimal score) {
        return score.max(SCORE_FLOOR).min(SCORE_CEILING).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private record RpRule(int base, int p95, double cap) {
    }
}
