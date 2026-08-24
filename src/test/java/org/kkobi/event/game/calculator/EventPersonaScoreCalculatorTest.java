package org.kkobi.event.game.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.enums.PersonaType;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.service.ScenarioService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 이벤트 성향 점수 산식 검증.
// - 무행동 참가자는 특별 분기 없이 산식 결과로 자연히 LHL이 나와야 한다(요구사항: 하드코딩 금지).
// - RP 반복 감쇠(log1p)와 세션 1회 상한, 축 clamp 동작을 확인한다.
class EventPersonaScoreCalculatorTest {

    private final EventPersonaFeatureExtractor extractor = new EventPersonaFeatureExtractor(
            new RecentExtremaMarketStateCalculator(),
            new GameSecurityReturnCalculator()
    );
    private final EventPersonaScoreCalculator scoreCalculator = new EventPersonaScoreCalculator();
    private final PersonaClassifier personaClassifier = new PersonaClassifier();
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    @Test
    @DisplayName("무행동: 특별 분기 없이 산식만으로 32.50/65.00/45.00 -> LHL 자연 산출")
    void noActionNaturallyClassifiesAsLhl() {
        ActionLogDto initial = new ActionLogDto();
        initial.setActionLogId(0L);
        initial.setGameTick(0);
        initial.setActionType("INITIAL_ALLOCATION");
        initial.setAssetType("ALL");
        initial.setActionAmount(10_000_000L);
        initial.setCurrentCash(10_000_000L);
        initial.setCurrentStock(0L);
        initial.setCurrentDeposit(0L);

        EventSessionFeatures features =
                extractor.extract(scenario, List.of(initial), 51).features();

        assertEquals(0, features.avgStockRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, features.avgCashRatio().compareTo(new BigDecimal("100")));

        AssessmentScore score = scoreCalculator.calculate(features);
        assertEquals(0, score.getRtScore().compareTo(new BigDecimal("32.50")));
        assertEquals(0, score.getLhScore().compareTo(new BigDecimal("57.50")));
        assertEquals(0, score.getRpScore().compareTo(new BigDecimal("45.00")));

        assertEquals(PersonaType.LHL, personaClassifier.calculatePersona(score));
    }

    @Test
    @DisplayName("RP 반복 감쇠: 정상장 분할매수 20회 반복해도 기여는 cap인 +6 한정 (45+6=51)")
    void splitBuyRepetitionCappedByDiminish() {
        EventSessionFeatures features = features(
                bd("0"), bd("100"),
                /*buyTickSpan*/30,
                /*crashHold*/0, /*panicSell*/0,
                /*buyTicks*/20, /*sellTicks*/0, /*quickRebuy*/0, /*roundTrips*/0, /*reentryAbsence*/0,
                false,
                /*lossAvg*/0, /*crashDip*/0, /*bullChase*/0, /*gainAdd*/0, /*split*/20,
                /*quickTake*/0, /*bullTake*/0);

        AssessmentScore score = scoreCalculator.calculate(features);
        assertEquals(0, score.getRpScore().compareTo(new BigDecimal("51.00")));
    }

    @Test
    @DisplayName("RP 세션 1회 규칙: 패닉 풀매도를 여러 번 해도 페널티는 -2 한정 (45-2=43)")
    void panicFullSellCountedOncePerSession() {
        EventSessionFeatures features = features(
                bd("40"), bd("60"),
                /*buyTickSpan*/30,
                /*crashHold*/0, /*panicSell*/3,
                3, 3, 0, 0, 0,
                false,
                0, 0, 0, 0, 0,
                0, 0);

        AssessmentScore score = scoreCalculator.calculate(features);
        assertEquals(0, score.getRpScore().compareTo(new BigDecimal("43.00")));
    }

    @Test
    @DisplayName("축 범위 통일: v3.3 상수의 이론 범위는 20~80 내부이며 clamp는 최후 방어선으로만 존재한다")
    void allAxesShareTheSameClampRange() {
        // 상단: RT=50+0.35*50+6=73.50, LH=50+7.5+10+9(왕복 pts3)=76.50, RP=45+(4+5+4+6+2)=66.00 -> 클램프 미작동
        EventSessionFeatures extremeHigh = features(
                bd("100"), bd("100"),
                /*buyTickSpan*/30,
                /*crashHold*/5, /*panicSell*/0,
                0, 10, 0, 5, 0,
                true,
                0, 9, 9, 9, 20,
                0, 0);

        AssessmentScore high = scoreCalculator.calculate(extremeHigh);
        assertWithinClamp(high);
        assertEquals(0, high.getRtScore().compareTo(new BigDecimal("73.50")));
        assertEquals(0, high.getLhScore().compareTo(new BigDecimal("76.50")));
        assertEquals(0, high.getRpScore().compareTo(new BigDecimal("66.00")));

        // 하단: RT=50-17.5-6=26.50, LH=50-7.5-8-4=30.50, RP=45-(3+4+2+2+1)=33.00 -> 하한도 미작동
        EventSessionFeatures extremeLow = features(
                bd("0"), bd("0"),
                /*buyTickSpan*/30,
                /*crashHold*/0, /*panicSell*/9,
                9, 0, 9, 0, /*reentryAbsence*/1,
                false,
                9, 0, 0, 0, 0,
                9, 9);

        AssessmentScore low = scoreCalculator.calculate(extremeLow);
        assertWithinClamp(low);
        assertEquals(0, low.getRtScore().compareTo(new BigDecimal("26.50")));
        assertEquals(0, low.getLhScore().compareTo(new BigDecimal("30.50")));
        assertEquals(0, low.getRpScore().compareTo(new BigDecimal("33.00")));
    }

    private void assertWithinClamp(AssessmentScore score) {
        assertEquals(true, score.getRtScore().compareTo(new BigDecimal("20")) >= 0
                && score.getRtScore().compareTo(new BigDecimal("80")) <= 0);
        assertEquals(true, score.getLhScore().compareTo(new BigDecimal("20")) >= 0
                && score.getLhScore().compareTo(new BigDecimal("80")) <= 0);
        assertEquals(true, score.getRpScore().compareTo(new BigDecimal("20")) >= 0
                && score.getRpScore().compareTo(new BigDecimal("80")) <= 0);
    }

    // EventSessionFeatures 컴포넌트 순서대로 생성한다.
    private EventSessionFeatures features(
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
            int bullProfitTakeCount) {
        return new EventSessionFeatures(
                avgStockRatio,
                avgCashRatio,
                buyTickSpan,
                crashHoldingEpisodes,
                panicFullSellCount,
                distinctBuyTicks,
                distinctSellTicks,
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
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
