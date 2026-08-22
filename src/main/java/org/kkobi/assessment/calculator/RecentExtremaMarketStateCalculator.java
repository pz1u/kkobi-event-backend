package org.kkobi.assessment.calculator;

import org.kkobi.assessment.enums.MarketState;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Component
public class RecentExtremaMarketStateCalculator {

    private static final int LOOKBACK_TICKS = 5;
    private static final BigDecimal CRASH_DRAWDOWN_RATE = BigDecimal.valueOf(-5);
    private static final BigDecimal BULL_REBOUND_RATE = BigDecimal.valueOf(3);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public MarketState calculateMarketState(ScenarioDto scenario, int gameTick) {
        ScenarioTickDto currentTick = findTick(scenario, gameTick);
        ScenarioTickDto previousTick = findPreviousTick(scenario, gameTick);
        List<ScenarioTickDto> recentTicks = getRecentTicks(scenario, gameTick);

        long recentHigh = recentTicks.stream()
                .mapToLong(ScenarioTickDto::getPrice)
                .max()
                .orElse(currentTick.getPrice());
        long recentLow = recentTicks.stream()
                .mapToLong(ScenarioTickDto::getPrice)
                .min()
                .orElse(currentTick.getPrice());

        BigDecimal drawdownRate = calculateRate(currentTick.getPrice(), recentHigh);
        BigDecimal reboundRate = calculateRate(currentTick.getPrice(), recentLow);
        boolean isRising = previousTick != null
                && currentTick.getPrice() > previousTick.getPrice();

        if (isRising && reboundRate.compareTo(BULL_REBOUND_RATE) >= 0) {
            return MarketState.BULL;
        }
        if (drawdownRate.compareTo(CRASH_DRAWDOWN_RATE) <= 0) {
            return MarketState.CRASH;
        }
        return MarketState.NORMAL;
    }

    private List<ScenarioTickDto> getRecentTicks(ScenarioDto scenario, int gameTick) {
        int firstTick = Math.max(0, gameTick - LOOKBACK_TICKS);
        return scenario.getTicks().stream()
                .filter(tick -> tick.getTick() >= firstTick && tick.getTick() <= gameTick)
                .toList();
    }

    private ScenarioTickDto findTick(ScenarioDto scenario, int gameTick) {
        validateScenario(scenario);
        return scenario.getTicks().stream()
                .filter(tick -> tick.getTick() == gameTick)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "게임 시나리오 tick을 찾을 수 없습니다: " + gameTick
                ));
    }

    private ScenarioTickDto findPreviousTick(ScenarioDto scenario, int gameTick) {
        return scenario.getTicks().stream()
                .filter(tick -> tick.getTick() < gameTick)
                .max(Comparator.comparingInt(ScenarioTickDto::getTick))
                .orElse(null);
    }

    private BigDecimal calculateRate(long currentPrice, long referencePrice) {
        if (referencePrice <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(currentPrice - referencePrice)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(referencePrice), 8, RoundingMode.HALF_UP);
    }

    private void validateScenario(ScenarioDto scenario) {
        if (scenario == null || scenario.getTicks() == null || scenario.getTicks().isEmpty()) {
            throw new IllegalArgumentException("게임 시나리오 tick 정보는 필수입니다.");
        }
    }
}
