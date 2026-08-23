package org.kkobi.event.game.constant;

import org.kkobi.game.dto.ScenarioDto;

import java.util.List;

public final class EventGameTiming {

    public static final int NEWS_BRIEFING_SECONDS = 10;

    private EventGameTiming() {
    }

    public static List<Integer> getPauseTicks(ScenarioDto scenario) {
        if (scenario == null
                || scenario.getTicks() == null
                || scenario.getTicks().isEmpty()
                || scenario.getEvents() == null) {
            return List.of();
        }

        int lastTickIndex = scenario.getTicks().size() - 1;
        return scenario.getEvents().stream()
                .map(event -> event.getTick())
                .filter(tick -> tick >= 0 && tick <= lastTickIndex)
                .distinct()
                .sorted()
                .toList();
    }

    public static long calculateTotalPauseSeconds(ScenarioDto scenario) {
        return (long) getPauseTicks(scenario).size() * NEWS_BRIEFING_SECONDS;
    }
}
