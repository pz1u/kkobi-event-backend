package org.kkobi.assessment.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.domain.BehaviorAnalysisResult;
import org.kkobi.assessment.domain.BehaviorContext;
import org.kkobi.assessment.domain.BehaviorEvent;
import org.kkobi.assessment.domain.RuleResult;
import org.kkobi.assessment.domain.ScoreDelta;
import org.kkobi.assessment.enums.BehaviorActionType;
import org.kkobi.assessment.enums.BehaviorAssetType;
import org.kkobi.assessment.enums.BehaviorRuleCode;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameBehaviorSimulatorTest {

    private static final long RANDOM_SEED = 20260815L;

    private final ScenarioService scenarioService = new ScenarioService();
    private final GameBehaviorSimulator gameBehaviorSimulator =
            new GameBehaviorSimulator();

    @Test
    @DisplayName("생성된 게임 행동을 기존 성향 계산 엔진으로 분석한다.")
    void simulateGameBehaviorAssessment() {
        ScenarioDto scenario = scenarioService.getScenario("SC001");
        SimulatedGamePortfolio initialPortfolio = createInitialPortfolio();

        GameBehaviorSimulationResult result = gameBehaviorSimulator.simulateGame(
                1L,
                scenario,
                initialPortfolio,
                RANDOM_SEED
        );

        assertEquals(BigDecimal.valueOf(20).setScale(2), result.getInitialCashRatio());
        assertEquals(BigDecimal.valueOf(70).setScale(2), result.getInitialStockRatio());
        assertEquals(BigDecimal.valueOf(10).setScale(2), result.getInitialDepositRatio());
        assertEquals(
                1,
                result.getRuleApplicationCounts().get(BehaviorRuleCode.INITIAL_STOCK_ALLOCATION)
        );
        assertTrue(result.getTradeCount() > 0);
        assertTrue(result.getRuleApplicationCounts().values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum() > 0);
        assertTrue(result.isDepositCancelled() ^ result.isDepositMatured());
        assertScoreRange(result.getFinalRtScore());
        assertScoreRange(result.getFinalLhScore());
        assertScoreRange(result.getFinalRpScore());
        assertEquals(
                new PersonaClassifier().calculatePersona(new AssessmentScore(
                        result.getFinalRtScore(),
                        result.getFinalLhScore(),
                        result.getFinalRpScore()
                )),
                result.getPersonaType()
        );
    }

    @Test
    @DisplayName("동일한 Seed는 동일한 최종 점수와 성향을 생성한다.")
    void simulateSameAssessmentWithSameRandomSeed() {
        ScenarioDto scenario = scenarioService.getScenario("SC001");

        GameBehaviorSimulationResult firstResult = gameBehaviorSimulator.simulateGame(
                1L,
                scenario,
                createInitialPortfolio(),
                RANDOM_SEED
        );
        GameBehaviorSimulationResult secondResult = gameBehaviorSimulator.simulateGame(
                2L,
                scenario,
                createInitialPortfolio(),
                RANDOM_SEED
        );

        assertEquals(firstResult.getFinalRtScore(), secondResult.getFinalRtScore());
        assertEquals(firstResult.getFinalLhScore(), secondResult.getFinalLhScore());
        assertEquals(firstResult.getFinalRpScore(), secondResult.getFinalRpScore());
        assertEquals(firstResult.getPersonaType(), secondResult.getPersonaType());
        assertEquals(
                firstResult.getRuleApplicationCounts(),
                secondResult.getRuleApplicationCounts()
        );
    }

    @Test
    @DisplayName("시뮬레이션은 전달받은 초기 자산 객체를 변경하지 않는다.")
    void protectInitialPortfolio() {
        SimulatedGamePortfolio initialPortfolio = createInitialPortfolio();

        gameBehaviorSimulator.simulateGame(
                1L,
                scenarioService.getScenario("SC001"),
                initialPortfolio,
                RANDOM_SEED
        );

        assertEquals(2_000_000L, initialPortfolio.getCurrentCash());
        assertEquals(7_000_000L, initialPortfolio.getCurrentStockPrincipal());
        assertEquals(1_000_000L, initialPortfolio.getCurrentDeposit());
        assertEquals(350, initialPortfolio.getCurrentStockQuantity());
        assertFalse(initialPortfolio.isDepositCancelled());
        assertFalse(initialPortfolio.isDepositMatured());
    }

    @Test
    @DisplayName("급락 구간 종료 시 기존 주식의 50% 이상을 유지한 구간만 계산한다.")
    void calculateCrashHoldingEpisodeCount() {
        ScenarioDto scenario = scenarioService.getScenario("SC001");
        setScenarioPrice(scenario, 18, 19_420L);
        setScenarioPrice(scenario, 19, 16_860L);
        setScenarioPrice(scenario, 20, 14_920L);
        setScenarioPrice(scenario, 21, 16_360L);
        int maintainedCount = gameBehaviorSimulator.calculateCrashHoldingEpisodeCount(
                scenario,
                100,
                List.of()
        );
        SimulatedGameAction majoritySoldAction = new SimulatedGameAction(
                19,
                BehaviorActionType.SELL,
                BehaviorAssetType.SECURITY,
                1_000_000L,
                60,
                16_860L,
                null,
                null,
                1_000_000L,
                800_000L,
                0L,
                40
        );
        int reducedCount = gameBehaviorSimulator.calculateCrashHoldingEpisodeCount(
                scenario,
                100,
                List.of(majoritySoldAction)
        );

        assertTrue(maintainedCount > 0);
        assertTrue(reducedCount < maintainedCount);
    }

    private void setScenarioPrice(ScenarioDto scenario, int tick, long price) {
        ScenarioTickDto scenarioTick = scenario.getTicks()
                .stream()
                .filter(candidate -> candidate.getTick() == tick)
                .findFirst()
                .orElseThrow();
        scenarioTick.setPrice(price);
        scenarioTick.setChangeRate(((double) price / scenario.getBasePrice() - 1) * 100);
    }

    @Test
    @DisplayName("평범장 총자산 10~29% 매수는 물타기가 아닐 때 Tick당 한 번 계산한다.")
    void calculateNormalPlannedBuyCount() {
        BehaviorContext firstBuy = createNormalBuyContext(1, 2_000_000L);
        BehaviorContext duplicateTickBuy = createNormalBuyContext(1, 1_500_000L);
        BehaviorContext smallBuy = createNormalBuyContext(2, 900_000L);
        BehaviorContext lossAveragingBuy = createNormalBuyContext(3, 2_000_000L);
        BehaviorAnalysisResult emptyResult = new BehaviorAnalysisResult(List.of());
        BehaviorAnalysisResult lossAveragingResult = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.LOSS_AVERAGING_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "손실 종목 추가 매수"
                )
        ));

        int count = gameBehaviorSimulator.calculateNormalPlannedBuyCount(
                List.of(firstBuy, duplicateTickBuy, smallBuy, lossAveragingBuy),
                List.of(emptyResult, emptyResult, emptyResult, lossAveragingResult)
        );

        assertEquals(1, count);
    }

    @Test
    @DisplayName("현금 비중 25% 이상 50% 미만을 3 Tick 연속 유지하면 한 번 반영한다.")
    void calculateCashBufferMaintenanceCount() {
        ScenarioDto threeTickScenario = createScenario(3);
        ScenarioDto twoTickScenario = createScenario(2);

        assertEquals(1, gameBehaviorSimulator.calculateCashBufferMaintenanceCount(
                threeTickScenario,
                2_500_000L,
                7_500_000L,
                0L,
                List.of()
        ));
        assertEquals(1, gameBehaviorSimulator.calculateCashBufferMaintenanceCount(
                threeTickScenario,
                4_999_000L,
                5_001_000L,
                0L,
                List.of()
        ));
        assertEquals(0, gameBehaviorSimulator.calculateCashBufferMaintenanceCount(
                threeTickScenario,
                5_000_000L,
                5_000_000L,
                0L,
                List.of()
        ));
        assertEquals(0, gameBehaviorSimulator.calculateCashBufferMaintenanceCount(
                twoTickScenario,
                3_000_000L,
                7_000_000L,
                0L,
                List.of()
        ));
    }

    @Test
    @DisplayName("현금 완충 비중은 서로 분리된 연속 유지 구간마다 한 번 계산한다.")
    void calculateCashBufferMaintenanceEpisodeCount() {
        ScenarioDto scenario = createScenario(7);
        List<SimulatedGameAction> actions = List.of(
                createCashStateAction(3, 6_000_000L, 4_000_000L),
                createCashStateAction(4, 3_000_000L, 7_000_000L)
        );

        int count = gameBehaviorSimulator.calculateCashBufferMaintenanceEpisodeCount(
                scenario,
                3_000_000L,
                7_000_000L,
                0L,
                actions
        );

        assertEquals(2, count);
    }

    @Test
    @DisplayName("반복 행동 점수는 행동 비율과 k=1 관측 신뢰도로 보정한다.")
    void calculateOpportunityWeightedScore() {
        ScoreDelta oneOfOne = gameBehaviorSimulator.calculateOpportunityWeightedScore(
                ScoreDelta.createScoreDelta(15, -5, 0),
                1
        );
        ScoreDelta sevenOfTen = gameBehaviorSimulator.calculateOpportunityWeightedScore(
                ScoreDelta.createScoreDelta(105, -35, 0),
                10
        );
        ScoreDelta tenOfTen = gameBehaviorSimulator.calculateOpportunityWeightedScore(
                ScoreDelta.createScoreDelta(150, -50, 0),
                10
        );

        assertScoreDelta(oneOfOne, "7.50", "-2.50", "0.00");
        assertScoreDelta(sevenOfTen, "9.55", "-3.18", "0.00");
        assertScoreDelta(tenOfTen, "13.64", "-4.55", "0.00");
    }

    @Test
    @DisplayName("후보 규칙 반복 점수는 P95까지 로그 비율로 증가하고 이후 최대 점수로 제한한다.")
    void calculateLogDiminishingCandidateScore() {
        ScoreDelta maximumScore = ScoreDelta.createScoreDelta(5, 0, 0);

        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        0,
                        4
                ),
                "0.00",
                "0.00",
                "0.00"
        );
        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        1,
                        4
                ),
                "2.15",
                "0.00",
                "0.00"
        );
        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        2,
                        4
                ),
                "3.41",
                "0.00",
                "0.00"
        );
        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        3,
                        4
                ),
                "4.31",
                "0.00",
                "0.00"
        );
        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        4,
                        4
                ),
                "5.00",
                "0.00",
                "0.00"
        );
        assertScoreDelta(
                gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                        maximumScore,
                        10,
                        4
                ),
                "5.00",
                "0.00",
                "0.00"
        );
    }

    @Test
    @DisplayName("로그 감쇠는 후보 규칙의 음수와 양수 축 방향을 유지한다.")
    void preserveLogDiminishingCandidateScoreDirection() {
        ScoreDelta scoreDelta = gameBehaviorSimulator.calculateLogDiminishingCandidateScore(
                ScoreDelta.createScoreDelta(0, -5, 5),
                1,
                9
        );

        assertScoreDelta(scoreDelta, "0.00", "-1.51", "1.51");
    }

    @Test
    @DisplayName("1회성 규칙은 유지하고 반복 규칙은 규칙별 P95 로그 감쇠를 적용한다.")
    void calculateLogDiminishingRuleContributions() {
        BehaviorAnalysisResult oneTimeResult = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.INITIAL_STOCK_ALLOCATION,
                        ScoreDelta.createScoreDelta(10, -5, 5),
                        "초기 주식 배분"
                )
        ));
        BehaviorAnalysisResult firstCrashBuy = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.CRASH_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "급락 매수"
                )
        ));
        BehaviorAnalysisResult secondCrashBuy = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.CRASH_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "급락 매수"
                )
        ));

        var contributions = gameBehaviorSimulator.calculateLogDiminishingRuleContributions(
                List.of(oneTimeResult, firstCrashBuy, secondCrashBuy)
        );

        assertScoreDelta(
                contributions.get(BehaviorRuleCode.INITIAL_STOCK_ALLOCATION),
                "10.00",
                "-5.00",
                "5.00"
        );
        assertScoreDelta(
                contributions.get(BehaviorRuleCode.CRASH_BUY),
                "6.83",
                "-3.41",
                "0.00"
        );
    }

    @Test
    @DisplayName("반복 규칙은 매수·매도·상태 유지 계열별로 한 번만 로그 감쇠한다.")
    void calculateLogDiminishingRuleGroupScores() {
        BehaviorAnalysisResult oneTimeResult = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.INITIAL_STOCK_ALLOCATION,
                        ScoreDelta.createScoreDelta(10, -5, 5),
                        "초기 주식 배분"
                )
        ));
        BehaviorAnalysisResult firstCrashBuy = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.CRASH_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "급락 매수"
                )
        ));
        BehaviorAnalysisResult secondCrashBuy = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.CRASH_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "급락 매수"
                )
        ));

        List<ScoreDelta> scores = gameBehaviorSimulator.calculateLogDiminishingRuleGroupScores(
                List.of(oneTimeResult, firstCrashBuy, secondCrashBuy),
                4,
                0,
                0
        );

        assertEquals(3, scores.size());
        assertScoreDelta(scores.get(0), "10.00", "-5.00", "5.00");
        assertTrue(scores.stream().anyMatch(scoreDelta ->
                new BigDecimal("3.67").compareTo(scoreDelta.getRtDelta()) == 0
                        && new BigDecimal("-1.83").compareTo(scoreDelta.getLhDelta()) == 0));
        assertTrue(scores.stream().anyMatch(scoreDelta ->
                new BigDecimal("3.66").compareTo(scoreDelta.getRtDelta()) == 0
                        && BigDecimal.ZERO.compareTo(scoreDelta.getLhDelta()) == 0));
    }

    @Test
    @DisplayName("권장 중간안은 매수매도 2.5회분과 상태 유지 1.5회분까지 허용한다.")
    void calculateBalancedLogDiminishingRuleGroupScores() {
        BehaviorAnalysisResult oneTimeResult = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.INITIAL_STOCK_ALLOCATION,
                        ScoreDelta.createScoreDelta(10, -5, 5),
                        "초기 주식 배분"
                )
        ));
        BehaviorAnalysisResult crashBuyResult = new BehaviorAnalysisResult(List.of(
                new RuleResult(
                        BehaviorRuleCode.CRASH_BUY,
                        ScoreDelta.createScoreDelta(10, -5, 0),
                        "급락 매수"
                )
        ));

        List<ScoreDelta> scores = gameBehaviorSimulator.calculateLogDiminishingRuleGroupScores(
                List.of(oneTimeResult, crashBuyResult, crashBuyResult),
                4,
                0,
                0,
                true
        );

        assertEquals(3, scores.size());
        assertScoreDelta(scores.get(0), "10.00", "-5.00", "5.00");
        assertTrue(scores.stream().anyMatch(scoreDelta ->
                new BigDecimal("9.18").compareTo(scoreDelta.getRtDelta()) == 0
                        && new BigDecimal("-4.58").compareTo(scoreDelta.getLhDelta()) == 0));
        assertTrue(scores.stream().anyMatch(scoreDelta ->
                new BigDecimal("5.49").compareTo(scoreDelta.getRtDelta()) == 0
                        && BigDecimal.ZERO.compareTo(scoreDelta.getLhDelta()) == 0));
    }

    private SimulatedGamePortfolio createInitialPortfolio() {
        return new SimulatedGamePortfolio(
                2_000_000L,
                7_000_000L,
                1_000_000L,
                350
        );
    }

    private BehaviorContext createNormalBuyContext(int gameTick, long actionAmount) {
        BehaviorEvent behaviorEvent = new BehaviorEvent();
        behaviorEvent.setGameTick(gameTick);
        behaviorEvent.setActionType(BehaviorActionType.BUY);
        behaviorEvent.setAssetType(BehaviorAssetType.SECURITY);
        behaviorEvent.setActionAmount(actionAmount);
        behaviorEvent.setCurrentCash(10_000_000L - actionAmount);
        behaviorEvent.setCurrentStockPrincipal(actionAmount);
        behaviorEvent.setCurrentDeposit(0L);

        BehaviorContext behaviorContext = new BehaviorContext();
        behaviorContext.setCurrentEvent(behaviorEvent);
        behaviorContext.setMarketState(MarketState.NORMAL);
        return behaviorContext;
    }

    private SimulatedGameAction createCashStateAction(
            int gameTick,
            long currentCash,
            long currentStockPrincipal) {
        return new SimulatedGameAction(
                gameTick,
                BehaviorActionType.BUY,
                BehaviorAssetType.SECURITY,
                1L,
                1,
                1L,
                BigDecimal.ZERO,
                null,
                currentCash,
                currentStockPrincipal,
                0L,
                1
        );
    }

    private void assertScoreDelta(
            ScoreDelta scoreDelta,
            String expectedRt,
            String expectedLh,
            String expectedRp) {
        assertEquals(0, new BigDecimal(expectedRt).compareTo(scoreDelta.getRtDelta()));
        assertEquals(0, new BigDecimal(expectedLh).compareTo(scoreDelta.getLhDelta()));
        assertEquals(0, new BigDecimal(expectedRp).compareTo(scoreDelta.getRpDelta()));
    }

    private ScenarioDto createScenario(int totalTicks) {
        ScenarioDto scenario = new ScenarioDto();
        scenario.setTotalTicks(totalTicks);
        java.util.ArrayList<ScenarioTickDto> ticks = new java.util.ArrayList<>();
        for (int tick = 0; tick < totalTicks; tick++) {
            ScenarioTickDto scenarioTick = new ScenarioTickDto();
            scenarioTick.setTick(tick);
            scenarioTick.setPrice(20_000L);
            ticks.add(scenarioTick);
        }
        scenario.setTicks(ticks);
        return scenario;
    }

    private void assertScoreRange(BigDecimal score) {
        assertTrue(score.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(score.compareTo(BigDecimal.valueOf(100)) <= 0);
    }
}
