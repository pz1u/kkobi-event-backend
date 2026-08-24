package org.kkobi.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyFinishedException;
import org.kkobi.event.exception.EventAlreadyStartedException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.mapper.EventSessionMapper;
import org.kkobi.game.service.ScenarioService;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventSessionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);

    private final EventSessionMapper eventSessionMapper = mock(EventSessionMapper.class);
    private final EventSessionServiceImpl service = new EventSessionServiceImpl(
            eventSessionMapper, new ScenarioService());

    private EventSession createSession(EventSessionStatus status) {
        EventSession session = new EventSession();
        session.setSessionId(1L);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setDurationSeconds(180);
        session.setInitialCash(10_000_000L);
        return session;
    }

    @Test
    @DisplayName("행사 상태 조회 시 serverNow와 participantCount를 함께 반환한다")
    void getEventStatusReturnsServerNowAndParticipantCount() {
        EventSession session = createSession(EventSessionStatus.WAITING);

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(12);

        EventStatusResponse response = service.getEventStatus(NOW);

        assertEquals(1L, response.getSessionId());
        assertEquals("WAITING", response.getStatus());
        assertEquals("SC001", response.getScenarioId());
        assertEquals(NOW, response.getServerNow());
        assertEquals(180, response.getDurationSeconds());
        assertEquals(10_000_000L, response.getInitialCash());
        assertEquals(12, response.getParticipantCount());
    }

    @Test
    @DisplayName("WAITING 상태에서 시작하면 COUNTDOWN으로 전환되고 시간이 확정된다")
    void startEventSucceedsWhenWaiting() {
        EventSession session = createSession(EventSessionStatus.WAITING);

        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(session);
        when(eventSessionMapper.startCountdown(eq(1L), eq(NOW), eq(NOW.plusSeconds(5)), eq(NOW.plusSeconds(205))))
                .thenReturn(1);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(0);

        EventStatusResponse response = service.startEvent(NOW);

        assertEquals("COUNTDOWN", response.getStatus());
        assertEquals(NOW, response.getCountdownStartedAt());
        assertEquals(NOW.plusSeconds(5), response.getStartAt());
        assertEquals(NOW.plusSeconds(205), response.getEndAt());
        assertEquals(180, response.getDurationSeconds());
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 중복 시작이 거절된다")
    void startEventRejectedWhenCountdown() {
        assertStartRejected(EventSessionStatus.COUNTDOWN);
    }

    @Test
    @DisplayName("RUNNING 상태에서는 중복 시작이 거절된다")
    void startEventRejectedWhenRunning() {
        assertStartRejected(EventSessionStatus.RUNNING);
    }

    @Test
    @DisplayName("FINISHED 상태에서는 중복 시작이 거절된다")
    void startEventRejectedWhenFinished() {
        assertStartRejected(EventSessionStatus.FINISHED);
    }

    private void assertStartRejected(EventSessionStatus status) {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(status));

        assertThrows(EventAlreadyStartedException.class, () -> service.startEvent(NOW));
        verify(eventSessionMapper, never())
                .startCountdown(eq(1L), any(), any(), any());
    }

    @Test
    @DisplayName("동시 START 요청으로 DB UPDATE가 0건이면 이미 시작된 것으로 처리한다")
    void startEventRejectedWhenConcurrentUpdateAffectsNoRows() {
        EventSession session = createSession(EventSessionStatus.WAITING);
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(session);
        when(eventSessionMapper.startCountdown(eq(1L), any(), any(), any())).thenReturn(0);

        assertThrows(EventAlreadyStartedException.class, () -> service.startEvent(NOW));
    }

    @Test
    @DisplayName("COUNTDOWN 중 startAt 이전이면 상태를 유지한다")
    void getEventStatusKeepsCountdownBeforeStartAt() {
        EventSession session = createSession(EventSessionStatus.COUNTDOWN);
        session.setCountdownStartedAt(NOW.minusSeconds(2));
        session.setStartAt(NOW.plusSeconds(3));
        session.setEndAt(NOW.plusSeconds(183));

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(5);

        EventStatusResponse response = service.getEventStatus(NOW);

        assertEquals("COUNTDOWN", response.getStatus());
        verify(eventSessionMapper, never())
                .transitionStatus(eq(1L), any(), any());
    }

    @Test
    @DisplayName("COUNTDOWN 중 startAt에 도달하면 RUNNING으로 갱신한다")
    void getEventStatusTransitionsCountdownToRunningAtStartAt() {
        EventSession session = createSession(EventSessionStatus.COUNTDOWN);
        session.setCountdownStartedAt(NOW.minusSeconds(5));
        session.setStartAt(NOW);
        session.setEndAt(NOW.plusSeconds(180));

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.transitionStatus(1L, EventSessionStatus.COUNTDOWN, EventSessionStatus.RUNNING))
                .thenReturn(1);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(5);

        EventStatusResponse response = service.getEventStatus(NOW);

        assertEquals("RUNNING", response.getStatus());
        verify(eventSessionMapper, times(1))
                .transitionStatus(1L, EventSessionStatus.COUNTDOWN, EventSessionStatus.RUNNING);
    }

    @Test
    @DisplayName("RUNNING 중 endAt 이전이면 상태를 유지한다")
    void getEventStatusKeepsRunningBeforeEndAt() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        session.setCountdownStartedAt(NOW.minusSeconds(60));
        session.setStartAt(NOW.minusSeconds(55));
        session.setEndAt(NOW.plusSeconds(10));

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(20);

        EventStatusResponse response = service.getEventStatus(NOW);

        assertEquals("RUNNING", response.getStatus());
        verify(eventSessionMapper, never())
                .transitionStatus(eq(1L), any(), any());
    }

    @Test
    @DisplayName("RUNNING 중 endAt에 도달하면 FINISHED로 갱신한다")
    void getEventStatusTransitionsRunningToFinishedAtEndAt() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        session.setCountdownStartedAt(NOW.minusSeconds(185));
        session.setStartAt(NOW.minusSeconds(180));
        session.setEndAt(NOW);

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.transitionToFinished(1L, EventSessionStatus.RUNNING, NOW))
                .thenReturn(1);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(20);

        EventStatusResponse response = service.getEventStatus(NOW);

        assertEquals("FINISHED", response.getStatus());
        verify(eventSessionMapper, times(1))
                .transitionToFinished(1L, EventSessionStatus.RUNNING, NOW);
    }

    @Test
    @DisplayName("정상 종료 시 finishedAt은 polling 요청 시각이 아니라 예정된 종료 시각(endAt)으로 확정된다")
    void getEventStatusSetsFinishedAtToEndAtNotPollingTime() {
        EventSession session = createSession(EventSessionStatus.RUNNING);
        LocalDateTime endAt = NOW.minusSeconds(1);
        session.setCountdownStartedAt(endAt.minusSeconds(185));
        session.setStartAt(endAt.minusSeconds(180));
        session.setEndAt(endAt);

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.transitionToFinished(1L, EventSessionStatus.RUNNING, endAt))
                .thenReturn(1);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(20);

        // 실제 polling 요청 시각(NOW)은 endAt(NOW - 1초)보다 늦지만, finishedAt은 endAt으로 고정되어야 한다
        service.getEventStatus(NOW);

        assertEquals(endAt, session.getFinishedAt());
        verify(eventSessionMapper, never())
                .transitionToFinished(eq(1L), eq(EventSessionStatus.RUNNING), eq(NOW));
    }

    @Test
    @DisplayName("COUNTDOWN 상태는 강제 종료할 수 있다")
    void finishEventSucceedsWhenCountdown() {
        assertFinishSucceeds(EventSessionStatus.COUNTDOWN);
    }

    @Test
    @DisplayName("RUNNING 상태는 강제 종료할 수 있다")
    void finishEventSucceedsWhenRunning() {
        assertFinishSucceeds(EventSessionStatus.RUNNING);
    }

    private void assertFinishSucceeds(EventSessionStatus status) {
        EventSession session = createSession(status);
        session.setEndAt(NOW.plusSeconds(60));

        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(session);
        when(eventSessionMapper.transitionToFinished(1L, status, NOW)).thenReturn(1);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(3);

        EventStatusResponse response = service.finishEvent(NOW);

        assertEquals("FINISHED", response.getStatus());
        // 예정된 endAt은 강제 종료 시에도 덮어쓰지 않고 보존한다
        assertEquals(NOW.plusSeconds(60), response.getEndAt());
        // 강제 종료의 실제 종료 시각(finishedAt)은 endAt이 아니라 강제 종료 요청을 처리한 서버 시각이다
        assertEquals(NOW, session.getFinishedAt());
    }

    @Test
    @DisplayName("WAITING 상태는 강제 종료가 거절된다")
    void finishEventRejectedWhenWaiting() {
        when(eventSessionMapper.findCurrentSessionForUpdate())
                .thenReturn(createSession(EventSessionStatus.WAITING));

        assertThrows(EventNotStartedException.class, () -> service.finishEvent(NOW));
    }

    @Test
    @DisplayName("이미 FINISHED된 행사는 강제 종료가 거절된다")
    void finishEventRejectedWhenAlreadyFinished() {
        when(eventSessionMapper.findCurrentSessionForUpdate())
                .thenReturn(createSession(EventSessionStatus.FINISHED));

        assertThrows(EventAlreadyFinishedException.class, () -> service.finishEvent(NOW));
    }
}
