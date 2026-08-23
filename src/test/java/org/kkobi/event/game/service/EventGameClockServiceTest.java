package org.kkobi.event.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.game.domain.EventGameClock;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.service.ScenarioService;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventGameClockServiceTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final int DURATION_SECONDS = 180;
    private static final int TOTAL_PAUSE_SECONDS = 20;

    private final EventGameClockService clockService = new EventGameClockService();
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    private EventSession createSession(EventSessionStatus status) {
        EventSession session = new EventSession();
        session.setSessionId(1L);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setStartAt(START_AT);
        session.setEndAt(START_AT.plusSeconds(DURATION_SECONDS + TOTAL_PAUSE_SECONDS));
        session.setDurationSeconds(DURATION_SECONDS);
        session.setInitialCash(10_000_000L);
        return session;
    }

    @Test
    @DisplayName("SC001 시나리오의 실제 tick 개수(53개, 0~52)를 동적으로 사용한다")
    void usesActualScenarioTickCount() {
        EventGameClock clock = clockService.calculateClock(
                createSession(EventSessionStatus.RUNNING), scenario, START_AT
        );

        assertEquals(53, clock.getTotalTickCount());
    }

    @Test
    @DisplayName("이벤트 뒤 두 Tick은 완만하게 움직인 뒤 본격적인 시장 반응이 시작된다")
    void eventReactionStartsAfterTwoReadingTicks() {
        assertEquals(1.9, scenario.getTicks().get(14).getChangeRate());
        assertEquals(2.0, scenario.getTicks().get(15).getChangeRate());
        assertEquals(2.2, scenario.getTicks().get(16).getChangeRate());
        assertEquals(4.2, scenario.getTicks().get(18).getChangeRate());

        assertEquals(-5.2, scenario.getTicks().get(35).getChangeRate());
        assertEquals(-5.3, scenario.getTicks().get(36).getChangeRate());
        assertEquals(-5.5, scenario.getTicks().get(37).getChangeRate());
        assertEquals(-7.8, scenario.getTicks().get(39).getChangeRate());
    }

    @Test
    @DisplayName("시나리오의 최저점과 최고점은 각각 -11.5%, +14.0%이다")
    void scenarioKeepsFinalVolatilityRange() {
        double minRate = scenario.getTicks().stream()
                .mapToDouble(tick -> tick.getChangeRate())
                .min()
                .orElseThrow();
        double maxRate = scenario.getTicks().stream()
                .mapToDouble(tick -> tick.getChangeRate())
                .max()
                .orElseThrow();

        assertEquals(-11.5, minRate);
        assertEquals(14.0, maxRate);
    }

    @Test
    @DisplayName("경과 시간이 0이면 Tick 0이다")
    void tickIsZeroAtStart() {
        EventGameClock clock = clockService.calculateClock(
                createSession(EventSessionStatus.RUNNING), scenario, START_AT
        );

        assertEquals(0, clock.getCurrentTick());
    }

    @Test
    @DisplayName("첫 브리핑 10초를 제외한 시장 경과 시간이 duration의 절반이면 Tick 26이다")
    void tickIsMidwayAtHalfDuration() {
        EventGameClock clock = clockService.calculateClock(
                createSession(EventSessionStatus.RUNNING),
                scenario,
                START_AT.plusSeconds(DURATION_SECONDS / 2 + 10)
        );

        assertEquals(26, clock.getCurrentTick());
    }

    @Test
    @DisplayName("이벤트 Tick에 도달하면 10초 동안 같은 Tick과 남은 시간을 유지하되 거래는 허용한다")
    void marketPausesForTenSecondsAtEventTick() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        long firstPauseStartMillis = divideCeil(14L * DURATION_SECONDS * 1000L, 53L);
        LocalDateTime pauseStartsAt = START_AT.plusNanos(firstPauseStartMillis * 1_000_000L);

        EventGameClock atPauseStart = clockService.calculateClock(session, scenario, pauseStartsAt);
        EventGameClock justBeforeResume = clockService.calculateClock(
                session, scenario, pauseStartsAt.plusSeconds(10).minusNanos(1_000_000));

        assertEquals(14, atPauseStart.getCurrentTick());
        assertTrue(atPauseStart.isMarketPaused());
        assertTrue(atPauseStart.isActionAllowed());
        assertEquals(pauseStartsAt.plusSeconds(10), atPauseStart.getMarketResumesAt());
        assertEquals(atPauseStart.getRemainingMilliseconds(), justBeforeResume.getRemainingMilliseconds());
        assertEquals(14, justBeforeResume.getCurrentTick());
        assertTrue(justBeforeResume.isMarketPaused());
    }

    @Test
    @DisplayName("뉴스 브리핑 10초가 끝나면 같은 Tick에서 시장과 거래가 다시 시작된다")
    void marketResumesAfterTenSecondBriefing() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        long firstPauseStartMillis = divideCeil(14L * DURATION_SECONDS * 1000L, 53L);
        LocalDateTime resumesAt = START_AT
                .plusNanos(firstPauseStartMillis * 1_000_000L)
                .plusSeconds(10);

        EventGameClock clock = clockService.calculateClock(session, scenario, resumesAt);

        assertEquals(14, clock.getCurrentTick());
        assertFalse(clock.isMarketPaused());
        assertTrue(clock.isActionAllowed());
        assertNull(clock.getMarketResumesAt());
    }

    @Test
    @DisplayName("종료 직전(endAt - 1ms)에는 마지막 Tick(52)에 도달한다")
    void tickReachesLastTickJustBeforeEnd() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        LocalDateTime justBeforeEnd = session.getEndAt().minusNanos(1_000_000);

        EventGameClock clock = clockService.calculateClock(session, scenario, justBeforeEnd);

        assertEquals(52, clock.getCurrentTick());
    }

    @Test
    @DisplayName("endAt 정각에는 Tick 계산보다 종료 판정이 우선되어 마지막 Tick을 반환한다")
    void tickIsLastTickExactlyAtEndAt() {
        EventSession session = createSession(EventSessionStatus.RUNNING);

        EventGameClock clock = clockService.calculateClock(session, scenario, session.getEndAt());

        assertEquals(52, clock.getCurrentTick());
    }

    @Test
    @DisplayName("endAt 이후에는 마지막 Tick을 유지한다")
    void tickStaysAtLastTickAfterEndAt() {
        EventSession session = createSession(EventSessionStatus.FINISHED);

        EventGameClock clock = clockService.calculateClock(
                session, scenario, session.getEndAt().plusSeconds(60)
        );

        assertEquals(52, clock.getCurrentTick());
    }

    @Test
    @DisplayName("startAt 정각에는 Tick 0이고, startAt 1ms 후에도 Tick 0을 유지한다")
    void tickStaysZeroJustAfterStartAt() {
        EventSession session = createSession(EventSessionStatus.RUNNING);

        EventGameClock atStart = clockService.calculateClock(session, scenario, session.getStartAt());
        EventGameClock justAfterStart = clockService.calculateClock(
                session, scenario, session.getStartAt().plusNanos(1_000_000)
        );

        assertEquals(0, atStart.getCurrentTick());
        assertEquals(0, justAfterStart.getCurrentTick());
    }

    @Test
    @DisplayName("동일한 세션과 동일한 서버 시각이면 여러 참가자라도 항상 같은 Tick이 계산된다")
    void sameSessionAndTimeAlwaysProduceSameTick() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        LocalDateTime now = START_AT.plusSeconds(90);

        EventGameClock first = clockService.calculateClock(session, scenario, now);
        EventGameClock second = clockService.calculateClock(session, scenario, now);

        assertEquals(first.getCurrentTick(), second.getCurrentTick());
    }

    @Test
    @DisplayName("남은 시간은 음수가 되지 않는다")
    void remainingSecondsNeverNegative() {
        EventSession session = createSession(EventSessionStatus.FINISHED);

        EventGameClock clock = clockService.calculateClock(
                session, scenario, session.getEndAt().plusSeconds(30)
        );

        assertEquals(0L, clock.getRemainingSeconds());
    }

    @Test
    @DisplayName("WAITING 상태에서는 게임 행동이 허용되지 않는다")
    void actionNotAllowedWhenWaiting() {
        assertFalse(clockService.isActionAllowed(createSession(EventSessionStatus.WAITING), START_AT));
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 게임 행동이 허용되지 않는다")
    void actionNotAllowedWhenCountdown() {
        assertFalse(clockService.isActionAllowed(createSession(EventSessionStatus.COUNTDOWN), START_AT));
    }

    @Test
    @DisplayName("RUNNING 상태이고 startAt <= now < endAt이면 게임 행동이 허용된다")
    void actionAllowedWhenRunningWithinWindow() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        assertTrue(clockService.isActionAllowed(session, session.getStartAt()));
        assertTrue(clockService.isActionAllowed(session, session.getStartAt().plusSeconds(90)));
    }

    @Test
    @DisplayName("status가 RUNNING이어도 서버 시각이 endAt 이후라면 게임 행동이 허용되지 않는다")
    void actionNotAllowedWhenPastEndAtEvenIfStillRunning() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        assertFalse(clockService.isActionAllowed(session, session.getEndAt()));
    }

    @Test
    @DisplayName("강제 종료로 endAt이 미래로 남아 있어도 status가 FINISHED면 게임 행동이 허용되지 않는다")
    void actionNotAllowedWhenForceFinishedEvenWithFutureEndAt() {
        EventSession session = createSession(EventSessionStatus.FINISHED);
        session.setEndAt(START_AT.plusSeconds(600));

        assertFalse(clockService.isActionAllowed(session, START_AT.plusSeconds(10)));
    }

    @Test
    @DisplayName("FINISHED 상태에서는 게임 행동이 허용되지 않는다")
    void actionNotAllowedWhenFinished() {
        assertFalse(clockService.isActionAllowed(createSession(EventSessionStatus.FINISHED), START_AT));
    }

    private long divideCeil(long dividend, long divisor) {
        return (dividend + divisor - 1L) / divisor;
    }
}
