package org.kkobi.event.game.service;

import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.game.constant.EventGameTiming;
import org.kkobi.event.game.domain.EventGameClock;
import org.kkobi.game.dto.ScenarioDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

// 서버 시간과 EventSession, 시나리오 tick 데이터를 기준으로 현재 Tick과 남은 시간을 계산한다.
// 프론트 로컬 타이머(gameTick, pause/resume)는 신뢰하지 않고 startAt/endAt만을 기준으로 삼는다.
@Service
public class EventGameClockService {

    public EventGameClock calculateClock(
            EventSession session,
            ScenarioDto scenario,
            LocalDateTime now) {
        int totalTickCount = getTotalTickCount(scenario);
        int lastTickIndex = totalTickCount - 1;
        MarketTimeline timeline = calculateMarketTimeline(
                session, scenario, totalTickCount, now);
        long remainingMilliseconds = calculateRemainingMilliseconds(session, now, timeline);

        return new EventGameClock(
                calculateCurrentTick(
                        session, totalTickCount, lastTickIndex, now,
                        timeline.effectiveElapsedMilliseconds()),
                totalTickCount,
                divideCeil(remainingMilliseconds, 1000L),
                remainingMilliseconds,
                isActionAllowed(session, now),
                timeline.marketPaused(),
                timeline.marketResumesAt()
        );
    }

    // RUNNING 상태이고 startAt <= now < endAt인 경우에만 게임 행동을 허용한다.
    // 강제 종료로 endAt이 미래로 남아 있을 수 있으므로 status == RUNNING을 반드시 함께 확인한다.
    public boolean isActionAllowed(EventSession session, LocalDateTime now) {
        return session.getStatus() == EventSessionStatus.RUNNING
                && session.getStartAt() != null
                && !now.isBefore(session.getStartAt())
                && session.getEndAt() != null
                && now.isBefore(session.getEndAt());
    }

    private int getTotalTickCount(ScenarioDto scenario) {
        if (scenario == null || scenario.getTicks() == null || scenario.getTicks().isEmpty()) {
            throw new IllegalArgumentException("게임 시나리오 tick 정보는 필수입니다.");
        }
        return scenario.getTicks().size();
    }

    private int calculateCurrentTick(
            EventSession session,
            int totalTickCount,
            int lastTickIndex,
            LocalDateTime now,
            long effectiveElapsedMilliseconds) {
        LocalDateTime startAt = session.getStartAt();
        LocalDateTime endAt = session.getEndAt();
        if (startAt == null || endAt == null || now.isBefore(startAt)) {
            return 0;
        }
        // 종료 시각에 도달했다면 Tick 계산보다 종료 판정을 우선한다.
        if (!now.isBefore(endAt)) {
            return lastTickIndex;
        }

        long durationMillis = session.getDurationSeconds() * 1000L;
        int currentTick = (int) ((effectiveElapsedMilliseconds * totalTickCount) / durationMillis);
        return Math.min(Math.max(currentTick, 0), lastTickIndex);
    }

    private MarketTimeline calculateMarketTimeline(
            EventSession session,
            ScenarioDto scenario,
            int totalTickCount,
            LocalDateTime now) {
        LocalDateTime startAt = session.getStartAt();
        if (startAt == null || now.isBefore(startAt)) {
            return new MarketTimeline(0L, false, null);
        }

        long durationMilliseconds = session.getDurationSeconds() * 1000L;
        long wallElapsedMilliseconds = Math.max(0L, Duration.between(startAt, now).toMillis());
        long completedPauseMilliseconds = 0L;
        long pauseDurationMilliseconds = EventGameTiming.NEWS_BRIEFING_SECONDS * 1000L;

        for (int eventTick : EventGameTiming.getPauseTicks(scenario)) {
            long eventMarketElapsedMilliseconds = divideCeil(
                    (long) eventTick * durationMilliseconds,
                    totalTickCount);
            long pauseStartsAtMilliseconds =
                    eventMarketElapsedMilliseconds + completedPauseMilliseconds;
            if (wallElapsedMilliseconds < pauseStartsAtMilliseconds) {
                break;
            }

            long pauseEndsAtMilliseconds = pauseStartsAtMilliseconds + pauseDurationMilliseconds;
            if (wallElapsedMilliseconds < pauseEndsAtMilliseconds) {
                return new MarketTimeline(
                        eventMarketElapsedMilliseconds,
                        true,
                        startAt.plusNanos(pauseEndsAtMilliseconds * 1_000_000L));
            }
            completedPauseMilliseconds += pauseDurationMilliseconds;
        }

        long effectiveElapsedMilliseconds = Math.min(
                durationMilliseconds,
                Math.max(0L, wallElapsedMilliseconds - completedPauseMilliseconds));
        return new MarketTimeline(effectiveElapsedMilliseconds, false, null);
    }

    private long calculateRemainingMilliseconds(
            EventSession session,
            LocalDateTime now,
            MarketTimeline timeline) {
        LocalDateTime endAt = session.getEndAt();
        if (endAt == null || !now.isBefore(endAt)) {
            return 0L;
        }
        long durationMilliseconds = session.getDurationSeconds() * 1000L;
        return Math.max(0L, durationMilliseconds - timeline.effectiveElapsedMilliseconds());
    }

    private long divideCeil(long dividend, long divisor) {
        if (dividend == 0L) {
            return 0L;
        }
        return (dividend + divisor - 1L) / divisor;
    }

    private record MarketTimeline(
            long effectiveElapsedMilliseconds,
            boolean marketPaused,
            LocalDateTime marketResumesAt) {
    }
}
