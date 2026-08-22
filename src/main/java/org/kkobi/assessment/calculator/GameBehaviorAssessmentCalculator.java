package org.kkobi.assessment.calculator;

import lombok.RequiredArgsConstructor;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.domain.RuleResult;
import org.kkobi.assessment.domain.ScoreDelta;
import org.kkobi.assessment.enums.BehaviorRuleCode;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.game.calculator.GamePriceRateCalculator;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class GameBehaviorAssessmentCalculator {

    private static final BigDecimal TEN = BigDecimal.valueOf(10);
    private static final BigDecimal TWENTY = BigDecimal.valueOf(20);
    private static final BigDecimal TWENTY_FIVE = BigDecimal.valueOf(25);
    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);
    private static final BigDecimal FORTY = BigDecimal.valueOf(40);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal SEVENTY = BigDecimal.valueOf(70);
    private static final BigDecimal EIGHTY = BigDecimal.valueOf(80);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MAINTENANCE_TICKS = 3;
    private static final int OPPORTUNITY_TICKS = 10;
    private static final int CANDIDATE_P95 = 4;
    private static final int BUY_GROUP_P95 = 19;
    private static final int SELL_GROUP_P95 = 9;
    private static final int STATE_GROUP_P95 = 8;
    private static final BigDecimal BUY_GROUP_CAP = BigDecimal.valueOf(2.5);
    private static final BigDecimal SELL_GROUP_CAP = BigDecimal.valueOf(2.5);
    private static final BigDecimal STATE_GROUP_CAP = BigDecimal.valueOf(1.5);

    private final AssetRatioCalculator assetRatioCalculator;
    private final MarketStateCalculator marketStateCalculator;
    private final GamePriceRateCalculator gamePriceRateCalculator;
    private final GameSecurityReturnCalculator gameSecurityReturnCalculator;
    private final RecentExtremaMarketStateCalculator recentExtremaMarketStateCalculator;

    public BehaviorAnalysisResult calculate(
            ScenarioDto scenario,
            List<ActionLogDto> actionLogs) {
        return calculate(scenario, actionLogs, false);
    }

    public BehaviorAnalysisResult calculateForEventGame(
            ScenarioDto scenario,
            List<ActionLogDto> actionLogs) {
        return calculate(scenario, actionLogs, true);
    }

    private BehaviorAnalysisResult calculate(
            ScenarioDto scenario,
            List<ActionLogDto> actionLogs,
            boolean useRecentExtremaMarketState) {
        if (scenario == null || actionLogs == null) {
            throw new IllegalArgumentException("게임 시나리오와 행동 로그는 필수입니다.");
        }
        List<ActionLogDto> sortedLogs = actionLogs.stream()
                .sorted(Comparator.comparing(
                                ActionLogDto::getGameTick,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ActionLogDto::getActionLogId,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        ))
                .toList();
        ActionLogDto initialAllocation = sortedLogs.stream()
                .filter(log -> "INITIAL_ALLOCATION".equals(log.getActionType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("초기 자산 배분 로그가 필요합니다."));

        List<RuleResult> oneTimeRules = new ArrayList<>();
        List<RuleResult> repeatedRules = new ArrayList<>();
        addInitialAllocationRules(initialAllocation, oneTimeRules);
        addActionRules(
                scenario,
                sortedLogs,
                repeatedRules,
                useRecentExtremaMarketState
        );
        addDepositDecisionRule(sortedLogs, initialAllocation, oneTimeRules);

        SequenceCounts sequenceCounts = calculateSequenceCounts(
                scenario,
                sortedLogs,
                initialAllocation,
                useRecentExtremaMarketState
        );
        addRepeatedRule(
                repeatedRules,
                BehaviorRuleCode.NORMAL_PLANNED_BUY,
                ScoreDelta.createScoreDelta(0, -5, 5),
                sequenceCounts.normalPlannedBuyCount(),
                "정상장에서 자산의 10~30%를 계획 매수했습니다."
        );
        addRepeatedRule(
                repeatedRules,
                BehaviorRuleCode.CRASH_HOLDING,
                ScoreDelta.createScoreDelta(5, 0, 0),
                sequenceCounts.crashHoldingCount(),
                "급락 구간에서 주식 노출의 50% 이상을 유지했습니다."
        );
        addRepeatedRule(
                repeatedRules,
                BehaviorRuleCode.CASH_BUFFER_MAINTENANCE,
                ScoreDelta.createScoreDelta(0, 5, 0),
                sequenceCounts.cashBufferCount(),
                "현금 비중 25~50%를 3 Tick 유지했습니다."
        );

        List<RuleResult> finalRules = new ArrayList<>(oneTimeRules);
        finalRules.addAll(calculateBalancedGroupRules(repeatedRules));
        addCandidateRule(
                finalRules,
                BehaviorRuleCode.RISK_BUDGET_MAINTENANCE,
                ScoreDelta.createScoreDelta(5, 5, -5),
                sequenceCounts.riskBudgetCount(),
                CANDIDATE_P95,
                "주식 50~70%와 현금 25~40%를 무거래 3 Tick 유지했습니다."
        );
        addCandidateRule(
                finalRules,
                BehaviorRuleCode.HHL_COMPOSITE,
                ScoreDelta.createScoreDelta(5, 5, -5),
                sequenceCounts.hhlCompositeCount(),
                1,
                "위험 예산 유지 후 급등장에서 추가 매수하지 않았습니다."
        );
        addCandidateRule(
                finalRules,
                BehaviorRuleCode.HLL_NO_CHASE,
                ScoreDelta.createScoreDelta(5, -5, -5),
                sequenceCounts.hllNoChaseCount(),
                1,
                "고위험 노출 상태에서 급등 기회를 추격하지 않았습니다."
        );
        addCandidateRule(
                finalRules,
                BehaviorRuleCode.LHH_COMPLETED_OPPORTUNITY,
                ScoreDelta.createScoreDelta(0, 0, 5),
                sequenceCounts.lhhCompletedOpportunityCount(),
                1,
                "유동성을 보존하며 계획 매수 후 수익 매도를 완료했습니다."
        );
        addCandidateRule(
                finalRules,
                BehaviorRuleCode.NORMAL_PARTIAL_SELL,
                ScoreDelta.createScoreDelta(0, 5, 0),
                sequenceCounts.normalPartialSellCount(),
                1,
                "정상장에서 부분 매도 후 적정 현금을 2 Tick 유지했습니다."
        );
        return new BehaviorAnalysisResult(finalRules);
    }

    private void addInitialAllocationRules(
            ActionLogDto initialAllocation,
            List<RuleResult> rules) {
        BigDecimal stockRatio = stockRatio(initialAllocation);
        BigDecimal depositRatio = ratio(
                initialAllocation.getCurrentDeposit(),
                totalPrincipal(initialAllocation)
        );
        BigDecimal cashRatio = cashRatio(initialAllocation);
        if (stockRatio.compareTo(SEVENTY) >= 0) {
            rules.add(rule(
                    BehaviorRuleCode.INITIAL_STOCK_ALLOCATION,
                    10, -5, 5,
                    "초기 주식 비중이 70% 이상입니다."
            ));
        }
        if (depositRatio.compareTo(FIFTY) >= 0) {
            rules.add(rule(
                    BehaviorRuleCode.INITIAL_DEPOSIT_ALLOCATION,
                    -10, -5, -5,
                    "초기 예금 비중이 50% 이상입니다."
            ));
        }
        if (cashRatio.compareTo(THIRTY) >= 0) {
            rules.add(rule(
                    BehaviorRuleCode.INITIAL_CASH_ALLOCATION,
                    -5, 10, -5,
                    "초기 현금 비중이 30% 이상입니다."
            ));
        }
    }

    private void addActionRules(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            List<RuleResult> rules,
            boolean useRecentExtremaMarketState) {
        Set<String> appliedTickRules = new HashSet<>();
        for (int index = 0; index < logs.size(); index++) {
            ActionLogDto log = logs.get(index);
            if (!"STOCK".equals(log.getAssetType())
                    && !"SECURITY".equals(log.getAssetType())) {
                continue;
            }
            if ("BUY".equals(log.getActionType())) {
                addBuyRule(
                        scenario,
                        logs,
                        index,
                        rules,
                        appliedTickRules,
                        useRecentExtremaMarketState
                );
            } else if ("SELL".equals(log.getActionType())) {
                addSellRule(
                        scenario,
                        logs,
                        index,
                        rules,
                        appliedTickRules,
                        useRecentExtremaMarketState
                );
            }
        }
    }

    private void addBuyRule(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            int index,
            List<RuleResult> rules,
            Set<String> appliedTickRules,
            boolean useRecentExtremaMarketState) {
        ActionLogDto log = logs.get(index);
        BigDecimal buyRatio = actionRatio(log);
        if (buyRatio.compareTo(TEN) < 0) {
            return;
        }
        BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                scenario,
                log.getGameTick(),
                logs.subList(0, index)
        );
        RuleResult result;
        if (returnRate != null && returnRate.compareTo(BigDecimal.valueOf(-15)) <= 0) {
            result = buyRatio.compareTo(THIRTY) >= 0
                    ? rule(BehaviorRuleCode.LOSS_AVERAGING_BUY, 15, -10, 0,
                    "손실률 -15% 이하에서 30% 이상 추가 매수했습니다.")
                    : rule(BehaviorRuleCode.LOSS_AVERAGING_BUY, 10, -5, 0,
                    "손실률 -15% 이하에서 10~30% 추가 매수했습니다.");
        } else if (marketState(scenario, log, useRecentExtremaMarketState) == MarketState.CRASH) {
            result = buyRatio.compareTo(THIRTY) >= 0
                    ? rule(BehaviorRuleCode.CRASH_BUY, 15, -10, 0,
                    "급락장에서 30% 이상 매수했습니다.")
                    : rule(BehaviorRuleCode.CRASH_BUY, 10, -5, 0,
                    "급락장에서 10~30% 매수했습니다.");
        } else if (marketState(scenario, log, useRecentExtremaMarketState) == MarketState.BULL) {
            result = buyRatio.compareTo(THIRTY) >= 0
                    ? rule(BehaviorRuleCode.BULL_BUY, 10, -10, 5,
                    "급등장에서 30% 이상 추세 매수했습니다.")
                    : rule(BehaviorRuleCode.BULL_BUY, 0, -5, 10,
                    "급등장에서 10~30% 추세 매수했습니다.");
        } else {
            return;
        }
        addOncePerTick(rules, appliedTickRules, log, result);
    }

    private void addSellRule(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            int index,
            List<RuleResult> rules,
            Set<String> appliedTickRules,
            boolean useRecentExtremaMarketState) {
        ActionLogDto log = logs.get(index);
        BigDecimal sellRatio = sellRatio(log);
        if (sellRatio.compareTo(TWENTY) < 0) {
            return;
        }
        BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                scenario,
                log.getGameTick(),
                logs.subList(0, index)
        );
        RuleResult result = null;
        if (marketState(scenario, log, useRecentExtremaMarketState) == MarketState.CRASH) {
            if (safe(log.getCurrentStock()) == 0L) {
                result = rule(BehaviorRuleCode.CRASH_FULL_SELL, -15, 10, -5,
                        "급락장에서 보유 주식을 전량 매도했습니다.");
            } else if (sellRatio.compareTo(FIFTY) >= 0) {
                result = rule(BehaviorRuleCode.CRASH_FULL_SELL, -10, 5, -5,
                        "급락장에서 보유 주식의 50% 이상을 매도했습니다.");
            } else {
                result = rule(BehaviorRuleCode.CRASH_FULL_SELL, 0, 5, -5,
                        "급락장에서 보유 주식의 20~50%를 매도했습니다.");
            }
        } else if (returnRate != null
                && returnRate.compareTo(BigDecimal.valueOf(-10)) <= 0) {
            if (safe(log.getCurrentStock()) == 0L) {
                result = rule(BehaviorRuleCode.LOSS_CUT_SELL, -15, 10, -10,
                        "손실률 -10% 이하에서 전량 손절했습니다.");
            } else if (sellRatio.compareTo(FIFTY) >= 0) {
                result = rule(BehaviorRuleCode.LOSS_CUT_SELL, -10, 10, -5,
                        "손실률 -10% 이하에서 50% 이상 손절했습니다.");
            } else {
                result = rule(BehaviorRuleCode.LOSS_CUT_SELL, -5, 5, -5,
                        "손실률 -10% 이하에서 20~50% 손절했습니다.");
            }
        } else if (marketState(scenario, log, useRecentExtremaMarketState) == MarketState.BULL
                && returnRate != null
                && returnRate.signum() > 0) {
            result = sellRatio.compareTo(FIFTY) >= 0
                    ? rule(BehaviorRuleCode.BULL_PROFIT_SELL, 0, 10, 0,
                    "급등장에서 보유 주식의 50% 이상을 수익 실현했습니다.")
                    : rule(BehaviorRuleCode.BULL_PROFIT_SELL, 0, 5, 5,
                    "급등장에서 보유 주식의 20~50%를 수익 실현했습니다.");
        }
        if (result != null) {
            addOncePerTick(rules, appliedTickRules, log, result);
        }
    }

    private void addDepositDecisionRule(
            List<ActionLogDto> logs,
            ActionLogDto initialAllocation,
            List<RuleResult> rules) {
        ActionLogDto cancellation = logs.stream()
                .filter(log -> "DEPOSIT_CANCEL".equals(log.getActionType()))
                .findFirst()
                .orElse(null);
        if (cancellation == null) {
            if (safe(initialAllocation.getCurrentDeposit()) > 0L) {
                rules.add(rule(
                        BehaviorRuleCode.DEPOSIT_MATURITY,
                        -5, -10, -5,
                        "예금을 만기까지 유지했습니다."
                ));
            }
            return;
        }
        long cancelledAmount = safe(cancellation.getActionAmount());
        long accumulatedBuyAmount = logs.stream()
                .filter(log -> log.getGameTick() != null)
                .filter(log -> log.getGameTick() >= cancellation.getGameTick())
                .filter(log -> log.getGameTick() <= cancellation.getGameTick() + 2)
                .filter(log -> "BUY".equals(log.getActionType()))
                .filter(log -> actionRatio(log).compareTo(TEN) >= 0)
                .mapToLong(log -> safe(log.getActionAmount()))
                .sum();
        if (cancelledAmount > 0L
                && ratio(accumulatedBuyAmount, cancelledAmount).compareTo(FIFTY) >= 0) {
            rules.add(rule(
                    BehaviorRuleCode.DEPOSIT_CANCEL_AND_SECURITY_BUY,
                    5, -10, 5,
                    "예금 해지 후 2 Tick 안에 해지 금액의 50% 이상을 매수했습니다."
            ));
            return;
        }
        long minimumCash = logs.stream()
                .filter(log -> log.getGameTick() != null)
                .filter(log -> log.getGameTick() >= cancellation.getGameTick())
                .filter(log -> log.getGameTick() <= cancellation.getGameTick() + 2)
                .map(ActionLogDto::getCurrentCash)
                .mapToLong(this::safe)
                .min()
                .orElse(safe(cancellation.getCurrentCash()));
        if (ratio(minimumCash, safe(cancellation.getCurrentCash())).compareTo(EIGHTY) >= 0) {
            rules.add(rule(
                    BehaviorRuleCode.DEPOSIT_CANCEL_CASH_RETENTION,
                    -5, 10, -5,
                    "예금 해지 후 2 Tick 동안 현금의 80% 이상을 유지했습니다."
            ));
        }
    }

    private SequenceCounts calculateSequenceCounts(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            ActionLogDto initialAllocation,
            boolean useRecentExtremaMarketState) {
        Map<Integer, List<ActionLogDto>> logsByTick = logs.stream()
                .filter(log -> log.getGameTick() != null)
                .collect(java.util.stream.Collectors.groupingBy(ActionLogDto::getGameTick));
        long cash = safe(initialAllocation.getCurrentCash());
        long stock = safe(initialAllocation.getCurrentStock());
        long deposit = safe(initialAllocation.getCurrentDeposit());
        int cashBufferTicks = 0;
        int riskBudgetTicks = 0;
        int cashBufferCount = 0;
        int riskBudgetCount = 0;
        boolean cashBufferApplied = false;
        boolean riskBudgetApplied = false;
        boolean riskBudgetEstablished = false;
        boolean crashEpisode = false;
        long crashStartStock = 0L;
        int crashHoldingCount = 0;
        boolean bullEpisode = false;
        boolean hhlQualified = false;
        boolean hllQualified = false;
        boolean bullBought = false;
        long bullStartStock = 0L;
        int hhlCount = 0;
        int hllCount = 0;

        List<ScenarioTickDto> ticks = scenario.getTicks().stream()
                .filter(tick -> tick.getTick() >= 0 && tick.getTick() < scenario.getTotalTicks())
                .sorted(Comparator.comparingInt(ScenarioTickDto::getTick))
                .toList();
        for (ScenarioTickDto tick : ticks) {
            MarketState state = marketStateAt(
                    scenario,
                    tick.getTick(),
                    useRecentExtremaMarketState
            );
            if (state == MarketState.CRASH && !crashEpisode) {
                crashEpisode = true;
                crashStartStock = stock;
            } else if (state != MarketState.CRASH && crashEpisode) {
                if (retainsStock(crashStartStock, stock, FIFTY)) {
                    crashHoldingCount++;
                }
                crashEpisode = false;
            }
            if (state == MarketState.BULL && !bullEpisode) {
                bullEpisode = true;
                hhlQualified = riskBudgetEstablished && hasRiskBudget(cash, stock, deposit);
                hllQualified = hasHllAllocation(cash, stock, deposit);
                bullStartStock = stock;
                bullBought = false;
            } else if (state != MarketState.BULL && bullEpisode) {
                if (!bullBought && retainsStock(bullStartStock, stock, EIGHTY)) {
                    hhlCount = hhlQualified ? 1 : hhlCount;
                    hllCount = hllQualified ? 1 : hllCount;
                }
                bullEpisode = false;
            }

            boolean securityTraded = false;
            for (ActionLogDto log : logsByTick.getOrDefault(tick.getTick(), List.of())) {
                if (("BUY".equals(log.getActionType()) || "SELL".equals(log.getActionType()))
                        && ("STOCK".equals(log.getAssetType())
                        || "SECURITY".equals(log.getAssetType()))) {
                    securityTraded = true;
                }
                if (bullEpisode && "BUY".equals(log.getActionType())) {
                    bullBought = true;
                }
                cash = safe(log.getCurrentCash());
                stock = safe(log.getCurrentStock());
                deposit = safe(log.getCurrentDeposit());
            }

            if (hasCashBuffer(cash, stock, deposit)) {
                cashBufferTicks++;
                if (!cashBufferApplied && cashBufferTicks >= MAINTENANCE_TICKS) {
                    cashBufferCount++;
                    cashBufferApplied = true;
                }
            } else {
                cashBufferTicks = 0;
                cashBufferApplied = false;
            }
            if (!securityTraded && hasRiskBudget(cash, stock, deposit)) {
                riskBudgetTicks++;
                if (!riskBudgetApplied && riskBudgetTicks >= MAINTENANCE_TICKS) {
                    riskBudgetCount++;
                    riskBudgetApplied = true;
                }
                riskBudgetEstablished = riskBudgetTicks >= MAINTENANCE_TICKS;
            } else {
                riskBudgetTicks = 0;
                riskBudgetApplied = false;
                riskBudgetEstablished = false;
            }
        }
        if (crashEpisode && retainsStock(crashStartStock, stock, FIFTY)) {
            crashHoldingCount++;
        }
        if (bullEpisode && !bullBought && retainsStock(bullStartStock, stock, EIGHTY)) {
            hhlCount = hhlQualified ? 1 : hhlCount;
            hllCount = hllQualified ? 1 : hllCount;
        }
        return new SequenceCounts(
                crashHoldingCount,
                countNormalPlannedBuys(scenario, logs, useRecentExtremaMarketState),
                cashBufferCount,
                riskBudgetCount,
                hhlCount,
                hllCount,
                countLhhCompletedOpportunities(scenario, logs, useRecentExtremaMarketState),
                countNormalPartialSells(scenario, logs, useRecentExtremaMarketState)
        );
    }

    private int countNormalPlannedBuys(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            boolean useRecentExtremaMarketState) {
        int count = 0;
        for (int index = 0; index < logs.size(); index++) {
            ActionLogDto log = logs.get(index);
            if (!"BUY".equals(log.getActionType())
                    || marketState(scenario, log, useRecentExtremaMarketState) != MarketState.NORMAL) {
                continue;
            }
            BigDecimal buyRatio = actionRatio(log);
            BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                    scenario,
                    log.getGameTick(),
                    logs.subList(0, index)
            );
            if (buyRatio.compareTo(TEN) >= 0
                    && buyRatio.compareTo(THIRTY) < 0
                    && (returnRate == null
                    || returnRate.compareTo(BigDecimal.valueOf(-15)) > 0)) {
                count++;
            }
        }
        return count;
    }

    private int countLhhCompletedOpportunities(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            boolean useRecentExtremaMarketState) {
        for (int buyIndex = 0; buyIndex < logs.size(); buyIndex++) {
            ActionLogDto buy = logs.get(buyIndex);
            if (!isTargetedLhhBuy(
                    scenario,
                    logs,
                    buyIndex,
                    useRecentExtremaMarketState
            )) {
                continue;
            }
            for (int sellIndex = buyIndex + 1; sellIndex < logs.size(); sellIndex++) {
                ActionLogDto sell = logs.get(sellIndex);
                if (sell.getGameTick() > buy.getGameTick() + OPPORTUNITY_TICKS) {
                    break;
                }
                if (!"SELL".equals(sell.getActionType()) || !hasLhhAllocation(sell)) {
                    continue;
                }
                BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                        scenario,
                        sell.getGameTick(),
                        logs.subList(0, sellIndex)
                );
                if (returnRate != null && returnRate.signum() > 0) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private boolean isTargetedLhhBuy(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            int index,
            boolean useRecentExtremaMarketState) {
        ActionLogDto log = logs.get(index);
        if (!"BUY".equals(log.getActionType())
                || marketState(scenario, log, useRecentExtremaMarketState) != MarketState.NORMAL
                || actionRatio(log).compareTo(TEN) < 0
                || actionRatio(log).compareTo(THIRTY) >= 0
                || !hasLhhAllocation(log)) {
            return false;
        }
        BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                scenario,
                log.getGameTick(),
                logs.subList(0, index)
        );
        return returnRate == null || returnRate.compareTo(BigDecimal.valueOf(-15)) > 0;
    }

    private int countNormalPartialSells(
            ScenarioDto scenario,
            List<ActionLogDto> logs,
            boolean useRecentExtremaMarketState) {
        for (int index = 0; index < logs.size(); index++) {
            ActionLogDto sell = logs.get(index);
            if (!"SELL".equals(sell.getActionType())
                    || marketState(scenario, sell, useRecentExtremaMarketState) != MarketState.NORMAL) {
                continue;
            }
            BigDecimal returnRate = gameSecurityReturnCalculator.calculateCurrentReturnRate(
                    scenario,
                    sell.getGameTick(),
                    logs.subList(0, index)
            );
            BigDecimal sellRatio = sellRatio(sell);
            if (returnRate != null && returnRate.compareTo(BigDecimal.valueOf(-10)) <= 0) {
                continue;
            }
            if (sellRatio.compareTo(TWENTY) < 0 || sellRatio.compareTo(FIFTY) >= 0) {
                continue;
            }
            if (preSellStockRatio(sell).compareTo(SEVENTY) >= 0) {
                continue;
            }
            BigDecimal postCashRatio = cashRatio(sell);
            if (postCashRatio.compareTo(TWENTY_FIVE) < 0
                    || postCashRatio.compareTo(FIFTY) >= 0) {
                continue;
            }
            if (retainsCashAfterSell(logs, index, sell)) {
                return 1;
            }
        }
        return 0;
    }

    private boolean retainsCashAfterSell(
            List<ActionLogDto> logs,
            int sellIndex,
            ActionLogDto sell) {
        long cashAfterSell = safe(sell.getCurrentCash());
        long minimumCash = cashAfterSell;
        for (int index = sellIndex + 1; index < logs.size(); index++) {
            ActionLogDto next = logs.get(index);
            if (next.getGameTick() > sell.getGameTick() + 2) {
                break;
            }
            minimumCash = Math.min(minimumCash, safe(next.getCurrentCash()));
        }
        return cashAfterSell > 0L
                && ratio(minimumCash, cashAfterSell).compareTo(EIGHTY) >= 0;
    }

    private List<RuleResult> calculateBalancedGroupRules(List<RuleResult> repeatedRules) {
        EnumMap<RuleGroup, ScoreDelta> sums = new EnumMap<>(RuleGroup.class);
        EnumMap<RuleGroup, Integer> counts = new EnumMap<>(RuleGroup.class);
        EnumMap<RuleGroup, BehaviorRuleCode> representativeCodes =
                new EnumMap<>(RuleGroup.class);
        for (RuleResult result : repeatedRules) {
            RuleGroup group = RuleGroup.from(result.getRuleCode());
            if (group == null) {
                continue;
            }
            sums.merge(group, result.getScoreDelta(), ScoreDelta::addScoreDelta);
            counts.merge(group, 1, Integer::sum);
            representativeCodes.putIfAbsent(group, result.getRuleCode());
        }
        List<RuleResult> results = new ArrayList<>();
        sums.forEach((group, sum) -> {
            int count = counts.get(group);
            ScoreDelta average = divide(sum, BigDecimal.valueOf(count));
            ScoreDelta contribution = diminish(average, count, group.p95())
                    .multiplyScoreDelta(group.cap());
            results.add(new RuleResult(
                    representativeCodes.get(group),
                    contribution,
                    group.description()
            ));
        });
        return results;
    }

    private void addRepeatedRule(
            List<RuleResult> rules,
            BehaviorRuleCode code,
            ScoreDelta score,
            int count,
            String reason) {
        for (int index = 0; index < count; index++) {
            rules.add(new RuleResult(code, score, reason));
        }
    }

    private void addCandidateRule(
            List<RuleResult> rules,
            BehaviorRuleCode code,
            ScoreDelta score,
            int count,
            int p95,
            String reason) {
        if (count <= 0) {
            return;
        }
        rules.add(new RuleResult(code, diminish(score, count, p95), reason));
    }

    private ScoreDelta diminish(ScoreDelta score, int count, int p95) {
        int effectiveCount = Math.min(count, p95);
        BigDecimal weight = BigDecimal.valueOf(
                Math.log1p(effectiveCount) / Math.log1p(p95)
        );
        return score.multiplyScoreDelta(weight);
    }

    private ScoreDelta divide(ScoreDelta score, BigDecimal divisor) {
        return new ScoreDelta(
                score.getRtDelta().divide(divisor, 8, RoundingMode.HALF_UP),
                score.getLhDelta().divide(divisor, 8, RoundingMode.HALF_UP),
                score.getRpDelta().divide(divisor, 8, RoundingMode.HALF_UP)
        );
    }

    private void addOncePerTick(
            List<RuleResult> rules,
            Set<String> appliedTickRules,
            ActionLogDto log,
            RuleResult result) {
        String key = log.getGameTick() + ":" + result.getRuleCode();
        if (appliedTickRules.add(key)) {
            rules.add(result);
        }
    }

    private RuleResult rule(
            BehaviorRuleCode code,
            int rt,
            int lh,
            int rp,
            String reason) {
        return new RuleResult(code, ScoreDelta.createScoreDelta(rt, lh, rp), reason);
    }

    private MarketState marketState(
            ScenarioDto scenario,
            ActionLogDto log,
            boolean useRecentExtremaMarketState) {
        if (useRecentExtremaMarketState) {
            return recentExtremaMarketStateCalculator.calculateMarketState(
                    scenario,
                    log.getGameTick()
            );
        }
        if (log.getMarketState() != null) {
            return MarketState.getMarketState(log.getMarketState());
        }
        return marketStateAt(scenario, log.getGameTick(), false);
    }

    private MarketState marketStateAt(
            ScenarioDto scenario,
            int tick,
            boolean useRecentExtremaMarketState) {
        if (useRecentExtremaMarketState) {
            return recentExtremaMarketStateCalculator.calculateMarketState(scenario, tick);
        }
        return marketStateCalculator.calculateMarketState(
                gamePriceRateCalculator.calculateTickPriceChangeRate(scenario, tick),
                null
        );
    }

    private boolean hasCashBuffer(long cash, long stock, long deposit) {
        BigDecimal ratio = ratio(cash, Math.addExact(Math.addExact(cash, stock), deposit));
        return ratio.compareTo(TWENTY_FIVE) >= 0 && ratio.compareTo(FIFTY) < 0;
    }

    private boolean hasRiskBudget(long cash, long stock, long deposit) {
        long total = Math.addExact(Math.addExact(cash, stock), deposit);
        BigDecimal stockRatio = ratio(stock, total);
        BigDecimal cashRatio = ratio(cash, total);
        return stockRatio.compareTo(FIFTY) >= 0
                && stockRatio.compareTo(SEVENTY) < 0
                && cashRatio.compareTo(TWENTY_FIVE) >= 0
                && cashRatio.compareTo(FORTY) < 0;
    }

    private boolean hasHllAllocation(long cash, long stock, long deposit) {
        long total = Math.addExact(Math.addExact(cash, stock), deposit);
        return ratio(stock, total).compareTo(SIXTY) >= 0
                && ratio(cash, total).compareTo(TWENTY) < 0
                && ratio(deposit, total).compareTo(TWENTY) < 0;
    }

    private boolean hasLhhAllocation(ActionLogDto log) {
        long total = totalPrincipal(log);
        long liquidAssets = Math.addExact(
                safe(log.getCurrentCash()),
                safe(log.getCurrentDeposit())
        );
        return ratio(liquidAssets, total).compareTo(SEVENTY) >= 0
                && stockRatio(log).compareTo(THIRTY) <= 0;
    }

    private boolean retainsStock(
            long startStock,
            long endStock,
            BigDecimal minimumRatio) {
        return startStock > 0L
                && ratio(endStock, startStock).compareTo(minimumRatio) >= 0;
    }

    private BigDecimal actionRatio(ActionLogDto log) {
        return ratio(safe(log.getActionAmount()), totalPrincipal(log));
    }

    private BigDecimal sellRatio(ActionLogDto log) {
        long preSellStock = Math.addExact(
                safe(log.getCurrentStock()),
                safe(log.getActionAmount())
        );
        return ratio(safe(log.getActionAmount()), preSellStock);
    }

    private BigDecimal preSellStockRatio(ActionLogDto log) {
        long preSellCash = safe(log.getCurrentCash()) - safe(log.getActionAmount());
        long preSellStock = Math.addExact(
                safe(log.getCurrentStock()),
                safe(log.getActionAmount())
        );
        if (preSellCash < 0L) {
            return ONE_HUNDRED;
        }
        long total = Math.addExact(
                Math.addExact(preSellCash, preSellStock),
                safe(log.getCurrentDeposit())
        );
        return ratio(preSellStock, total);
    }

    private BigDecimal cashRatio(ActionLogDto log) {
        return assetRatioCalculator.calculateCashRatio(
                safe(log.getCurrentCash()),
                safe(log.getCurrentStock()),
                safe(log.getCurrentDeposit())
        );
    }

    private BigDecimal stockRatio(ActionLogDto log) {
        return ratio(safe(log.getCurrentStock()), totalPrincipal(log));
    }

    private long totalPrincipal(ActionLogDto log) {
        return Math.addExact(
                Math.addExact(safe(log.getCurrentCash()), safe(log.getCurrentStock())),
                safe(log.getCurrentDeposit())
        );
    }

    private BigDecimal ratio(Long amount, long total) {
        return ratio(safe(amount), total);
    }

    private BigDecimal ratio(long amount, long total) {
        if (amount <= 0L || total <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(amount)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private enum RuleGroup {
        BUY(BUY_GROUP_P95, BUY_GROUP_CAP, "매수 계열 로그 감쇠·2.5회분 상한"),
        SELL(SELL_GROUP_P95, SELL_GROUP_CAP, "매도 계열 로그 감쇠·2.5회분 상한"),
        STATE(STATE_GROUP_P95, STATE_GROUP_CAP, "상태 유지 계열 로그 감쇠·1.5회분 상한");

        private final int p95;
        private final BigDecimal cap;
        private final String description;

        RuleGroup(int p95, BigDecimal cap, String description) {
            this.p95 = p95;
            this.cap = cap;
            this.description = description;
        }

        int p95() {
            return p95;
        }

        BigDecimal cap() {
            return cap;
        }

        String description() {
            return description;
        }

        static RuleGroup from(BehaviorRuleCode code) {
            return switch (code) {
                case CRASH_BUY, BULL_BUY, LOSS_AVERAGING_BUY, NORMAL_PLANNED_BUY -> BUY;
                case CRASH_FULL_SELL, BULL_PROFIT_SELL, LOSS_CUT_SELL -> SELL;
                case CRASH_HOLDING, CASH_BUFFER_MAINTENANCE -> STATE;
                default -> null;
            };
        }
    }

    private record SequenceCounts(
            int crashHoldingCount,
            int normalPlannedBuyCount,
            int cashBufferCount,
            int riskBudgetCount,
            int hhlCompositeCount,
            int hllNoChaseCount,
            int lhhCompletedOpportunityCount,
            int normalPartialSellCount) {
    }
}
