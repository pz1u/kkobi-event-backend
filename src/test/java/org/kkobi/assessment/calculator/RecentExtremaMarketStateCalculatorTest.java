package org.kkobi.assessment.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.enums.MarketState;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.service.ScenarioService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentExtremaMarketStateCalculatorTest {

    private final RecentExtremaMarketStateCalculator calculator =
            new RecentExtremaMarketStateCalculator();
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    @Test
    @DisplayName("최근 고점에서 5% 이상 하락한 구간을 급락장으로 판단한다")
    void classifiesRecentPeakDrawdownAsCrash() {
        assertEquals(MarketState.NORMAL, calculator.calculateMarketState(scenario, 19));
        assertEquals(MarketState.CRASH, calculator.calculateMarketState(scenario, 20));
        assertEquals(MarketState.CRASH, calculator.calculateMarketState(scenario, 24));
        assertEquals(MarketState.CRASH, calculator.calculateMarketState(scenario, 27));
        assertEquals(MarketState.NORMAL, calculator.calculateMarketState(scenario, 28));
    }

    @Test
    @DisplayName("최근 저점에서 3% 이상 반등하며 상승 중인 구간을 급등장으로 판단한다")
    void classifiesRecentLowReboundAsBull() {
        assertEquals(MarketState.BULL, calculator.calculateMarketState(scenario, 30));
        assertEquals(MarketState.BULL, calculator.calculateMarketState(scenario, 34));
        assertEquals(MarketState.NORMAL, calculator.calculateMarketState(scenario, 35));
        assertEquals(MarketState.NORMAL, calculator.calculateMarketState(scenario, 39));
        assertEquals(MarketState.BULL, calculator.calculateMarketState(scenario, 41));
        assertEquals(MarketState.BULL, calculator.calculateMarketState(scenario, 47));
        assertEquals(MarketState.NORMAL, calculator.calculateMarketState(scenario, 48));
    }
}
