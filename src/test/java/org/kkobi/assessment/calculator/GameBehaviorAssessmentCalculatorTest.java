package org.kkobi.assessment.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.enums.BehaviorRuleCode;
import org.kkobi.game.calculator.GamePriceRateCalculator;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameBehaviorAssessmentCalculatorTest {

    private final GameBehaviorAssessmentCalculator calculator =
            new GameBehaviorAssessmentCalculator(
                    new AssetRatioCalculator(),
                    new MarketStateCalculator(),
                    new GamePriceRateCalculator(new SecurityPriceRateCalculator()),
                    new GameSecurityReturnCalculator(),
                    new RecentExtremaMarketStateCalculator()
            );

    @Test
    @DisplayName("위험 예산을 유지한 뒤 급등장을 추격하지 않으면 HHL 규칙을 적용한다.")
    void appliesRiskBudgetAndHhlCompositeRules() {
        ScenarioDto scenario = createScenario(
                List.of(0.0, 0.0, 0.0, 0.0, 4.0, 4.0),
                List.of(100L, 100L, 100L, 100L, 104L, 104L)
        );
        List<ActionLogDto> logs = List.of(createLog(
                1L,
                0,
                "INITIAL_ALLOCATION",
                "ALL",
                0L,
                300_000L,
                600_000L,
                100_000L
        ));

        BehaviorAnalysisResult result = calculator.calculate(scenario, logs);

        assertTrue(hasRule(result, BehaviorRuleCode.RISK_BUDGET_MAINTENANCE));
        assertTrue(hasRule(result, BehaviorRuleCode.HHL_COMPOSITE));
    }

    @Test
    @DisplayName("고위험 노출 상태에서 급등장을 추격하지 않으면 HLL 규칙을 적용한다.")
    void appliesHllNoChaseRule() {
        ScenarioDto scenario = createScenario(
                List.of(0.0, 4.0, 4.0),
                List.of(100L, 104L, 104L)
        );
        List<ActionLogDto> logs = List.of(createLog(
                1L,
                0,
                "INITIAL_ALLOCATION",
                "ALL",
                0L,
                100_000L,
                800_000L,
                100_000L
        ));

        BehaviorAnalysisResult result = calculator.calculate(scenario, logs);

        assertTrue(hasRule(result, BehaviorRuleCode.HLL_NO_CHASE));
    }

    @Test
    @DisplayName("유동성 배분에서 계획 매수 후 수익 매도하면 LHH 규칙을 적용한다.")
    void appliesLhhCompletedOpportunityRule() {
        ScenarioDto scenario = createScenario(
                List.of(0.0, 0.0, 0.0),
                List.of(100L, 100L, 110L)
        );
        List<ActionLogDto> logs = List.of(
                createLog(1L, 0, "INITIAL_ALLOCATION", "ALL", 0L,
                        400_000L, 200_000L, 400_000L),
                createLog(2L, 1, "BUY", "STOCK", 100_000L,
                        300_000L, 300_000L, 400_000L),
                createLog(3L, 2, "SELL", "STOCK", 100_000L,
                        400_000L, 200_000L, 400_000L)
        );

        BehaviorAnalysisResult result = calculator.calculate(scenario, logs);

        assertTrue(hasRule(result, BehaviorRuleCode.LHH_COMPLETED_OPPORTUNITY));
    }

    @Test
    @DisplayName("정상장 부분 매도 후 적정 현금을 유지하면 유동성 확보 규칙을 적용한다.")
    void appliesNormalPartialSellRule() {
        ScenarioDto scenario = createScenario(
                List.of(0.0, 0.0, 0.0, 0.0),
                List.of(100L, 100L, 100L, 100L)
        );
        List<ActionLogDto> logs = List.of(
                createLog(1L, 0, "INITIAL_ALLOCATION", "ALL", 0L,
                        200_000L, 500_000L, 300_000L),
                createLog(2L, 1, "SELL", "STOCK", 200_000L,
                        400_000L, 300_000L, 300_000L)
        );

        BehaviorAnalysisResult result = calculator.calculate(scenario, logs);

        assertTrue(hasRule(result, BehaviorRuleCode.NORMAL_PARTIAL_SELL));
    }

    @Test
    @DisplayName("반복 매수 점수는 로그 감쇠 후 평균 점수 2.5회분으로 제한한다.")
    void capsRepeatedBuyGroupScore() {
        List<Double> changeRates = new ArrayList<>();
        List<Long> prices = new ArrayList<>();
        List<ActionLogDto> logs = new ArrayList<>();
        for (int tick = 0; tick <= 25; tick++) {
            changeRates.add(0.0);
            prices.add(100L);
        }
        logs.add(createLog(1L, 0, "INITIAL_ALLOCATION", "ALL", 0L,
                200_000L, 400_000L, 400_000L));
        for (int tick = 1; tick <= 25; tick++) {
            ActionLogDto buy = createLog((long) tick + 1, tick, "BUY", "STOCK", 100_000L,
                    200_000L, 400_000L, 400_000L);
            buy.setMarketState("BULL");
            logs.add(buy);
        }

        BehaviorAnalysisResult result = calculator.calculate(
                createScenario(changeRates, prices),
                logs
        );

        org.kkobi.assessment.domain.RuleResult buyGroup = result.getAppliedRules().stream()
                .filter(rule -> rule.getRuleCode() == BehaviorRuleCode.BULL_BUY)
                .findFirst()
                .orElseThrow();
        assertEquals(0, buyGroup.getScoreDelta().getLhDelta()
                .compareTo(new java.math.BigDecimal("-12.5")));
        assertEquals(0, buyGroup.getScoreDelta().getRpDelta()
                .compareTo(new java.math.BigDecimal("25.0")));
    }

    @Test
    @DisplayName("이벤트 게임은 저장된 단일 Tick 상태보다 최근 고점 낙폭 판정을 우선한다")
    void eventGameUsesRecentExtremaMarketState() {
        ScenarioDto scenario = new ScenarioService().getScenario("SC001");
        ActionLogDto crashBuy = createLog(
                2L,
                20,
                "BUY",
                "STOCK",
                2_000_000L,
                8_000_000L,
                2_000_000L,
                0L
        );
        crashBuy.setMarketState("NORMAL");
        List<ActionLogDto> logs = List.of(
                createLog(
                        1L,
                        0,
                        "INITIAL_ALLOCATION",
                        "ALL",
                        0L,
                        10_000_000L,
                        0L,
                        0L
                ),
                crashBuy
        );

        BehaviorAnalysisResult memberGameResult = calculator.calculate(scenario, logs);
        BehaviorAnalysisResult eventGameResult = calculator.calculateForEventGame(scenario, logs);

        assertFalse(hasRule(memberGameResult, BehaviorRuleCode.CRASH_BUY));
        assertTrue(hasRule(eventGameResult, BehaviorRuleCode.CRASH_BUY));
    }

    private boolean hasRule(
            BehaviorAnalysisResult result,
            BehaviorRuleCode ruleCode) {
        return result.getAppliedRules().stream()
                .anyMatch(rule -> rule.getRuleCode() == ruleCode);
    }

    private ScenarioDto createScenario(
            List<Double> changeRates,
            List<Long> prices) {
        ScenarioDto scenario = new ScenarioDto();
        scenario.setScenarioId("SC001");
        scenario.setTotalTicks(changeRates.size());
        List<ScenarioTickDto> ticks = new ArrayList<>();
        for (int tickIndex = 0; tickIndex < changeRates.size(); tickIndex++) {
            ScenarioTickDto tick = new ScenarioTickDto();
            tick.setTick(tickIndex);
            tick.setChangeRate(changeRates.get(tickIndex));
            tick.setPrice(prices.get(tickIndex));
            ticks.add(tick);
        }
        scenario.setTicks(ticks);
        return scenario;
    }

    private ActionLogDto createLog(
            Long id,
            int tick,
            String actionType,
            String assetType,
            long actionAmount,
            long cash,
            long stock,
            long deposit) {
        ActionLogDto log = new ActionLogDto();
        log.setActionLogId(id);
        log.setUserId(1L);
        log.setGameTick(tick);
        log.setActionType(actionType);
        log.setAssetType(assetType);
        log.setActionAmount(actionAmount);
        log.setCurrentCash(cash);
        log.setCurrentStock(stock);
        log.setCurrentDeposit(deposit);
        return log;
    }
}
