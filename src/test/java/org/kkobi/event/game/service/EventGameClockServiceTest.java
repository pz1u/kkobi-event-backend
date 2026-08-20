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
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventGameClockServiceTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final int DURATION_SECONDS = 180;

    private final EventGameClockService clockService = new EventGameClockService();
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    private EventSession createSession(EventSessionStatus status) {
        EventSession session = new EventSession();
        session.setSessionId(1L);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setStartAt(START_AT);
        session.setEndAt(START_AT.plusSeconds(DURATION_SECONDS));
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
    @DisplayName("경과 시간이 0이면 Tick 0이다")
    void tickIsZeroAtStart() {
        EventGameClock clock = clockService.calculateClock(
                createSession(EventSessionStatus.RUNNING), scenario, START_AT
        );

        assertEquals(0, clock.getCurrentTick());
    }

    @Test
    @DisplayName("경과 시간이 duration의 절반이면 floor(elapsed*totalTick/duration) 공식에 따라 Tick 26이다")
    void tickIsMidwayAtHalfDuration() {
        EventGameClock clock = clockService.calculateClock(
                createSession(EventSessionStatus.RUNNING),
                scenario,
                START_AT.plusSeconds(DURATION_SECONDS / 2)
        );

        assertEquals(26, clock.getCurrentTick());
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
}
