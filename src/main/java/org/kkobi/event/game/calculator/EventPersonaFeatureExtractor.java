package org.kkobi.event.game.calculator;

import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// 이벤트 성향 점수의 입력 특징을 행동 로그에서 재구성해 추출한다.
// - 로그 정렬: gameTick 오름차순 → action_log_id 오름차순 (동일 틱 복수 거래 처리 순서)
// - 틱별 스냅샷: 해당 틱의 모든 행동을 반영한 뒤 틱 종료 비중을 한 번만 집계
// - 강제 종료: finalTick까지만 계산한다
// - 수량·원금 재구성은 StockQuantityPolicy(잔고 갱신과 동일 산식)를 사용한다
@Component
public class EventPersonaFeatureExtractor {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final int RATIO_SNAPSHOT_SCALE = 8;
    private static final int RATIO_AVERAGE_SCALE = 4;
    private static final BigDecimal RETENTION_HALF = new BigDecimal("0.5");
    private static final int REBUY_WINDOW_TICKS = 2;
    private static final int REENTRY_MIN_OBSERVABLE_TICKS = 2;
    private static final int QUICK_TAKE_MAX_GAP_TICKS = 10;
    private static final int ACCUMULATION_MIN_BUY_TICKS = 3;
    // 캘리브레이션(v3.5): 세션 내 짧게라도 흩어진 진입을 적립 증거로 인정해
    // 저활동 참가자의 성향 분화(LHH/LLH 경로)를 확보한다.
    private static final int ACCUMULATION_MIN_SPAN_TICKS = 6;

    private final RecentExtremaMarketStateCalculator recentExtremaMarketStateCalculator;
    private final GameSecurityReturnCalculator gameSecurityReturnCalculator;
    private final EventPersonaThresholds thresholds;

    @Autowired
    public EventPersonaFeatureExtractor(
            RecentExtremaMarketStateCalculator recentExtremaMarketStateCalculator,
            GameSecurityReturnCalculator gameSecurityReturnCalculator) {
        this(recentExtremaMarketStateCalculator, gameSecurityReturnCalculator, EventPersonaThresholds.defaults());
    }

    EventPersonaFeatureExtractor(
            RecentExtremaMarketStateCalculator recentExtremaMarketStateCalculator,
            GameSecurityReturnCalculator gameSecurityReturnCalculator,
            EventPersonaThresholds thresholds) {
        this.recentExtremaMarketStateCalculator = recentExtremaMarketStateCalculator;
        this.gameSecurityReturnCalculator = gameSecurityReturnCalculator;
        this.thresholds = thresholds;
    }

    public EventExtractionResult extract(ScenarioDto scenario, List<ActionLogDto> actionLogs, int finalTick) {
        validate(scenario, actionLogs, finalTick);

        List<ActionLogDto> sortedLogs = sortLogs(actionLogs);
        ActionLogDto initialAllocation = findInitialAllocation(sortedLogs);
        Map<Integer, Long> priceByTick = priceByTick(scenario);
        Map<Integer, List<LogContext>> contextsByTick = buildContextsByTick(scenario, sortedLogs);

        long cash = safe(initialAllocation.getCurrentCash());
        long principal = safe(initialAllocation.getCurrentStock());
        long deposit = safe(initialAllocation.getCurrentDeposit());
        BigDecimal quantity = BigDecimal.ZERO;

        // 급락 에피소드 판정용 직전 틱 종료 값 (tick 0 시작이면 초기 배분 상태: 현금 100%라 자동 미인정)
        BigDecimal prevCloseQuantity = BigDecimal.ZERO;
        BigDecimal prevCloseStockRatio = BigDecimal.ZERO;
        boolean inCrash = false;
        BigDecimal crashStartQuantity = BigDecimal.ZERO;
        BigDecimal crashStartRatio = BigDecimal.ZERO;
        int crashHoldingEpisodes = 0;

        int panicFullSellCount = 0;
        int quickProfitTakeCount = 0;
        int bullProfitTakeCount = 0;
        int lossAveragingBuyCount = 0;
        int crashDipBuyCount = 0;
        int bullChaseBuyCount = 0;
        int gainAddBuyCount = 0;
        int normalSplitBuyCount = 0;
        Set<String> countedRulesByTick = new HashSet<>();

        BigDecimal openQuantity = BigDecimal.ZERO; // 미청산 매수 수량 (왕복 완결 판정)
        int completedRoundTrips = 0;
        Long lastBuyTick = null;

        TreeSet<Integer> buyTicks = new TreeSet<>();
        Set<Integer> sellTicks = new TreeSet<>();
        List<long[]> buyEvents = new ArrayList<>(); // [tick, amount]
        List<long[]> sellEvents = new ArrayList<>();
        Set<Integer> normalMarketBuyTicks = new TreeSet<>();

        BigDecimal sumStockRatio = BigDecimal.ZERO;
        BigDecimal sumCashRatio = BigDecimal.ZERO;
        int countedTicks = 0;

        for (int tick = 0; tick <= finalTick; tick++) {
            MarketState state = recentExtremaMarketStateCalculator.calculateMarketState(scenario, tick);

            if (state == MarketState.CRASH && !inCrash) {
                inCrash = true;
                crashStartQuantity = prevCloseQuantity;
                crashStartRatio = prevCloseStockRatio;
            } else if (state != MarketState.CRASH && inCrash) {
                if (holdsThroughCrash(crashStartRatio, crashStartQuantity, quantity)) {
                    crashHoldingEpisodes++;
                }
                inCrash = false;
            }

            for (LogContext context : contextsByTick.getOrDefault(tick, List.of())) {
                ActionLogDto log = context.log();
                String type = log.getActionType();
                String assetType = log.getAssetType();
                boolean isStockTrade = ("BUY".equals(type) || "SELL".equals(type))
                        && ("STOCK".equals(assetType) || "SECURITY".equals(assetType));
                if (!isStockTrade && !"DEPOSIT_CANCEL".equals(type)) {
                    continue;
                }

                long amount = safe(log.getActionAmount());
                if ("BUY".equals(type)) {
                    BigDecimal boughtQuantity = StockQuantityPolicy.calculateQuantity(amount, priceAt(priceByTick, tick));
                    cash -= amount;
                    principal += amount;
                    quantity = quantity.add(boughtQuantity);
                    openQuantity = openQuantity.add(boughtQuantity);
                    buyTicks.add(tick);
                    buyEvents.add(new long[]{tick, amount});
                    lastBuyTick = (long) tick;
                    if (state == MarketState.NORMAL) {
                        normalMarketBuyTicks.add(tick);
                    }
                    int slot = classifyBuy(context.returnRate(), buyRatioOf(log), state, tick, countedRulesByTick);
                    if (slot == 0) {
                        lossAveragingBuyCount++;
                    } else if (slot == 1) {
                        crashDipBuyCount++;
                    } else if (slot == 2) {
                        bullChaseBuyCount++;
                    } else if (slot == 3) {
                        gainAddBuyCount++;
                    } else if (slot == 4) {
                        normalSplitBuyCount++;
                    }
                } else if ("SELL".equals(type)) {
                    BigDecimal soldQuantity = StockQuantityPolicy.calculateQuantity(amount, priceAt(priceByTick, tick));
                    principal -= StockQuantityPolicy.calculateProportionalPrincipal(principal, soldQuantity, quantity);
                    quantity = quantity.subtract(soldQuantity);
                    cash += amount;
                    openQuantity = openQuantity.subtract(soldQuantity);
                    // UI는 정수 주 단위로만 팔므로 정상 흐름의 잔여는 정확히 0이다.
                    // 허용치는 scale=8 반올림 먼지 방어선(StockQuantityPolicy.QUANTITY_TOLERANCE)이며,
                    // 0.9주 같은 진짜 미청산 포지션은 완결로 집계되지 않는다.
                    if (openQuantity.abs().compareTo(StockQuantityPolicy.QUANTITY_TOLERANCE) <= 0) {
                        completedRoundTrips++;
                        openQuantity = BigDecimal.ZERO;
                    }
                    sellTicks.add(tick);
                    sellEvents.add(new long[]{tick, amount});
                    // 전량 매도 판정도 동일 허용치를 사용해 왕복 완결과 일관되게 처리한다.
                    boolean fullLiquidation = quantity.abs()
                            .compareTo(StockQuantityPolicy.QUANTITY_TOLERANCE) <= 0;
                    if (fullLiquidation && state == MarketState.CRASH) {
                        panicFullSellCount++;
                    } else {
                        String rule = classifySell(
                                context.returnRate(),
                                state,
                                tick,
                                lastBuyTick,
                                countedRulesByTick);
                        if ("QUICK_PROFIT_TAKE".equals(rule)) {
                            quickProfitTakeCount++;
                        } else if ("BULL_PROFIT_SELL".equals(rule)) {
                            bullProfitTakeCount++;
                        }
                    }
                } else { // DEPOSIT_CANCEL
                    deposit -= amount;
                    cash += amount;
                }
            }

            // 틱 종료 스냅샷: 모든 행동을 반영한 뒤 한 번만 집계한다
            long price = priceAt(priceByTick, tick);
            BigDecimal stockValue = quantity.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP);
            BigDecimal totalAsset = stockValue.add(BigDecimal.valueOf(Math.addExact(cash, deposit)));
            if (totalAsset.signum() > 0) {
                sumStockRatio = sumStockRatio.add(
                        stockValue.multiply(HUNDRED).divide(totalAsset, RATIO_SNAPSHOT_SCALE, RoundingMode.HALF_UP));
                sumCashRatio = sumCashRatio.add(
                        totalAsset.subtract(stockValue).multiply(HUNDRED).divide(totalAsset, RATIO_SNAPSHOT_SCALE, RoundingMode.HALF_UP));
                prevCloseStockRatio = stockValue.multiply(HUNDRED)
                        .divide(totalAsset, RATIO_SNAPSHOT_SCALE, RoundingMode.HALF_UP);
            }
            countedTicks++;
            prevCloseQuantity = quantity;
        }

        if (inCrash && holdsThroughCrash(crashStartRatio, crashStartQuantity, quantity)) {
            crashHoldingEpisodes++;
        }

        int rebuyWithin2TicksCount = countQuickRebuys(buyEvents, sellEvents);
        int reentryAbsenceCount = countReentryAbsences(sellTicks, buyTicks, finalTick);
        boolean accumulationStreak = isAccumulationStreak(normalMarketBuyTicks, panicFullSellCount);

        BigDecimal avgStockRatio = countedTicks > 0
                ? sumStockRatio.divide(BigDecimal.valueOf(countedTicks), RATIO_AVERAGE_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgCashRatio = countedTicks > 0
                ? sumCashRatio.divide(BigDecimal.valueOf(countedTicks), RATIO_AVERAGE_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int buyTickSpan = buyTicks.isEmpty() ? 0 : buyTicks.last() - buyTicks.first();

        EventSessionFeatures features = new EventSessionFeatures(
                avgStockRatio,
                avgCashRatio,
                buyTickSpan,
                crashHoldingEpisodes,
                panicFullSellCount,
                buyTicks.size(),
                sellTicks.size(),
                rebuyWithin2TicksCount,
                completedRoundTrips,
                reentryAbsenceCount,
                accumulationStreak,
                lossAveragingBuyCount,
                crashDipBuyCount,
                bullChaseBuyCount,
                gainAddBuyCount,
                normalSplitBuyCount,
                quickProfitTakeCount,
                bullProfitTakeCount
        );
        return new EventExtractionResult(features, quantity, principal);
    }

    // LH용(rebuyWithin2Ticks): 각 SELL별 [t, t+2] 내 BUY 합계 ≥ 매도금액×50% → +1 (매도별 최대 1회).
    // RP용(reentryAbsence)과 목적·윈도우·금액 조건이 다르므로 이 결과를 절대 재사용하지 않는다.
    private int countQuickRebuys(List<long[]> buyEvents, List<long[]> sellEvents) {
        int count = 0;
        for (long[] sell : sellEvents) {
            long windowEnd = sell[0] + REBUY_WINDOW_TICKS;
            long rebuyAmount = 0L;
            for (long[] buy : buyEvents) {
                if (buy[0] >= sell[0] && buy[0] <= windowEnd) {
                    rebuyAmount += buy[1];
                }
            }
            if (rebuyAmount * 2 >= sell[1]) {
                count++;
            }
        }
        return count;
    }

    // RP용(reentryAbsence): 매도 t에 대해 [t+1, finalTick] 내 BUY 0회 ∧ 관찰 여유(finalTick − t ≥ 2).
    // 동일 틱 재매수는 윈도우 밖이라 "사후 재진입 없음"으로 인정된다 — rebuyWithin2Ticks와 갈리는 지점.
    private int countReentryAbsences(Set<Integer> sellTicks, Set<Integer> buyTicks, int finalTick) {
        for (Integer sellTick : sellTicks) {
            if (finalTick - sellTick < REENTRY_MIN_OBSERVABLE_TICKS) {
                continue;
            }
            boolean hasLaterReentry = buyTicks.stream().anyMatch(buyTick -> buyTick > sellTick);
            if (!hasLaterReentry) {
                return 1;
            }
        }
        return 0;
    }

    // 적립 연속 보너스: 정상장 BUY 서로 다른 틱 ≥3 ∧ 첫~마지막 간격 ≥10틱 ∧ 패닉 풀매도 0회
    private boolean isAccumulationStreak(Set<Integer> normalMarketBuyTicks, int panicFullSellCount) {
        if (panicFullSellCount > 0 || normalMarketBuyTicks.size() < ACCUMULATION_MIN_BUY_TICKS) {
            return false;
        }
        Integer first = null;
        Integer last = null;
        for (Integer tick : normalMarketBuyTicks) {
            if (first == null) {
                first = tick;
            }
            last = tick;
        }
        return first != null && last != null && last - first >= ACCUMULATION_MIN_SPAN_TICKS;
    }

    // BUY 우선순위(first-match-wins): 물타기 → 급락 역발 → 급등 추격 → 수익 중 추가 → 정상장 분할.
    // 한 행동에는 기본 규칙 하나만 적용하며, 동일 틱 동일 규칙은 1회만 집계한다.
    private int classifyBuy(
            BigDecimal returnRate,
            BigDecimal buyRatio,
            MarketState state,
            int tick,
            Set<String> countedRulesByTick) {
        String ruleKey = null;
        int slot = -1;
        if (returnRate != null && returnRate.compareTo(thresholds.lossAveragingReturnRate()) <= 0) {
            ruleKey = "LOSS_AVERAGING";
            slot = 0;
        } else if (state == MarketState.CRASH) {
            ruleKey = "CRASH_DIP_BUY";
            slot = 1;
        } else if (state == MarketState.BULL) {
            ruleKey = "BULL_CHASE_BUY";
            slot = 2;
        } else if (returnRate != null && returnRate.signum() > 0) {
            ruleKey = "GAIN_ADD_BUY";
            slot = 3;
        } else if (state == MarketState.NORMAL
                && buyRatio.compareTo(thresholds.splitBuyMinRatio()) >= 0
                && buyRatio.compareTo(thresholds.splitBuyMaxRatio()) < 0) {
            ruleKey = "PLANNED_SPLIT_BUY";
            slot = 4;
        }
        return ruleKey != null && countedRulesByTick.add(tick + ":" + ruleKey) ? slot : -1;
    }

    // SELL 우선순위(first-match-wins): 패닉 풀매도(호출부 선처리) → 신속 익절 → 급등 익절. 그 외 RP 중립.
    private String classifySell(
            BigDecimal returnRate,
            MarketState state,
            int tick,
            Long lastBuyTick,
            Set<String> countedRulesByTick) {
        String ruleKey = null;
        if (lastBuyTick != null
                && tick - lastBuyTick <= QUICK_TAKE_MAX_GAP_TICKS
                && returnRate != null
                && returnRate.signum() > 0) {
            ruleKey = "QUICK_PROFIT_TAKE";
        } else if (state == MarketState.BULL && returnRate != null && returnRate.signum() > 0) {
            ruleKey = "BULL_PROFIT_SELL";
        }
        if (ruleKey == null || !countedRulesByTick.add(tick + ":" + ruleKey)) {
            return null;
        }
        return ruleKey;
    }

    // 급락 홀딩 인정: 에피소드 시작 직전 틱 종료 노출 ≥50% ∧ 종료 시점 수량 ≥ 시작 수량×50%
    private boolean holdsThroughCrash(BigDecimal startRatio, BigDecimal startQuantity, BigDecimal endQuantity) {
        return startRatio.compareTo(FIFTY) >= 0
                && startQuantity.signum() > 0
                && endQuantity.multiply(TWO).compareTo(startQuantity) >= 0;
    }

    // 매수 비율: 행동 금액 / 행동 후 총 원금 ×100 (기존 회원용 계산기의 totalPrincipal 관례와 동일)
    private BigDecimal buyRatioOf(ActionLogDto log) {
        long amount = safe(log.getActionAmount());
        long total = Math.addExact(Math.addExact(safe(log.getCurrentCash()), safe(log.getCurrentStock())),
                safe(log.getCurrentDeposit()));
        if (amount <= 0L || total <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(amount).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    // 정렬 순서 기준 각 매매 로그의 직전 포지션 수익률을 미리 계산해 틱별로 묶어 둔다.
    private Map<Integer, List<LogContext>> buildContextsByTick(ScenarioDto scenario, List<ActionLogDto> sortedLogs) {
        List<LogContext> contexts = new ArrayList<>(sortedLogs.size());
        for (int index = 0; index < sortedLogs.size(); index++) {
            ActionLogDto log = sortedLogs.get(index);
            BigDecimal returnRate = null;
            if (("BUY".equals(log.getActionType()) || "SELL".equals(log.getActionType()))
                    && ("STOCK".equals(log.getAssetType()) || "SECURITY".equals(log.getAssetType()))) {
                returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                        scenario, log.getGameTick(), sortedLogs.subList(0, index));
            }
            contexts.add(new LogContext(log, returnRate));
        }
        return contexts.stream()
                .filter(context -> context.log().getGameTick() != null)
                .collect(Collectors.groupingBy(context -> context.log().getGameTick()));
    }

    private void validate(ScenarioDto scenario, List<ActionLogDto> actionLogs, int finalTick) {
        if (scenario == null || actionLogs == null) {
            throw new IllegalArgumentException("게임 시나리오와 행동 로그는 필수입니다.");
        }
        if (scenario.getTicks() == null || scenario.getTicks().isEmpty()) {
            throw new IllegalArgumentException("게임 시나리오 tick 정보는 필수입니다.");
        }
        if (finalTick < 0) {
            throw new IllegalArgumentException("finalTick은 0 이상이어야 합니다.");
        }
    }

    private List<ActionLogDto> sortLogs(List<ActionLogDto> logs) {
        return logs.stream()
                .sorted(Comparator.comparing(
                                ActionLogDto::getGameTick,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ActionLogDto::getActionLogId,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        ))
                .toList();
    }

    private ActionLogDto findInitialAllocation(List<ActionLogDto> sortedLogs) {
        return sortedLogs.stream()
                .filter(log -> "INITIAL_ALLOCATION".equals(log.getActionType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("초기 자산 배분 로그가 필요합니다."));
    }

    private Map<Integer, Long> priceByTick(ScenarioDto scenario) {
        return scenario.getTicks().stream()
                .collect(Collectors.toMap(tick -> tick.getTick(), tick -> tick.getPrice(), (first, second) -> first));
    }

    private long priceAt(Map<Integer, Long> priceByTick, int tick) {
        Long price = priceByTick.get(tick);
        if (price == null) {
            throw new IllegalArgumentException("게임 시나리오 tick을 찾을 수 없습니다: " + tick);
        }
        return price;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private record LogContext(ActionLogDto log, BigDecimal returnRate) {
    }
}
