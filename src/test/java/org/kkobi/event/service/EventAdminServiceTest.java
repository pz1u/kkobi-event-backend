package org.kkobi.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyStartedException;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameResultMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.mapper.EventSessionMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventAdminServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);

    private final EventSessionMapper eventSessionMapper = mock(EventSessionMapper.class);
    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventGameStateMapper eventGameStateMapper = mock(EventGameStateMapper.class);
    private final EventActionLogMapper eventActionLogMapper = mock(EventActionLogMapper.class);
    private final EventGameResultMapper eventGameResultMapper = mock(EventGameResultMapper.class);

    private final EventAdminService service = new EventAdminService(
            eventSessionMapper,
            eventParticipantMapper,
            eventGameStateMapper,
            eventActionLogMapper,
            eventGameResultMapper
    );

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
    @DisplayName("FINISHED 세션은 초기화에 성공하고 상태/시간 필드가 모두 null로 되돌아간다")
    void resetSucceedsWhenFinished() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventSessionMapper.resetSession(1L)).thenReturn(1);

        EventStatusResponse response = service.resetEvent(NOW);

        assertEquals(1L, response.getSessionId());
        assertEquals("WAITING", response.getStatus());
        assertNull(response.getCountdownStartedAt());
        assertNull(response.getStartAt());
        assertNull(response.getEndAt());
        assertEquals(0, response.getParticipantCount());
    }

    @Test
    @DisplayName("reset은 event_game_results/event_action_logs/event_game_states/event_participants를 sessionId 기준으로 삭제한다")
    void resetDeletesAllDependentDataBySessionId() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventSessionMapper.resetSession(1L)).thenReturn(1);

        service.resetEvent(NOW);

        verify(eventGameResultMapper, times(1)).deleteBySessionId(1L);
        verify(eventActionLogMapper, times(1)).deleteBySessionId(1L);
        verify(eventGameStateMapper, times(1)).deleteBySessionId(1L);
        verify(eventParticipantMapper, times(1)).deleteBySessionId(1L);
    }

    @Test
    @DisplayName("reset 후에도 scenarioId, durationSeconds, initialCash는 유지된다")
    void resetPreservesSessionConfig() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventSessionMapper.resetSession(1L)).thenReturn(1);

        EventStatusResponse response = service.resetEvent(NOW);

        assertEquals("SC001", response.getScenarioId());
        assertEquals(180, response.getDurationSeconds());
        assertEquals(10_000_000L, response.getInitialCash());
    }

    @Test
    @DisplayName("WAITING 상태에서 참가자가 이미 등록되어 있어도 초기화에 성공하고 참가자는 모두 제거된다")
    void resetSucceedsWhenWaitingWithExistingParticipants() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(EventSessionStatus.WAITING));
        when(eventSessionMapper.resetSession(1L)).thenReturn(1);

        EventStatusResponse response = service.resetEvent(NOW);

        assertEquals("WAITING", response.getStatus());
        assertEquals(0, response.getParticipantCount());
        verify(eventParticipantMapper, times(1)).deleteBySessionId(1L);
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 초기화가 거절된다")
    void resetRejectedWhenCountdown() {
        assertResetRejected(EventSessionStatus.COUNTDOWN);
    }

    @Test
    @DisplayName("RUNNING 상태에서는 초기화가 거절된다")
    void resetRejectedWhenRunning() {
        assertResetRejected(EventSessionStatus.RUNNING);
    }

    private void assertResetRejected(EventSessionStatus status) {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(status));

        assertThrows(EventAlreadyStartedException.class, () -> service.resetEvent(NOW));

        verify(eventGameResultMapper, never()).deleteBySessionId(any());
        verify(eventActionLogMapper, never()).deleteBySessionId(any());
        verify(eventGameStateMapper, never()).deleteBySessionId(any());
        verify(eventParticipantMapper, never()).deleteBySessionId(any());
        verify(eventSessionMapper, never()).resetSession(any());
    }

    @Test
    @DisplayName("진행 중인 행사가 없으면 초기화가 거절된다")
    void resetRejectedWhenNoSession() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.resetEvent(NOW));
    }

    @Test
    @DisplayName("동시 reset 요청으로 세션 UPDATE가 0건이면 초기화 실패로 처리한다")
    void resetRejectedWhenConcurrentUpdateAffectsNoRows() {
        when(eventSessionMapper.findCurrentSessionForUpdate()).thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventSessionMapper.resetSession(1L)).thenReturn(0);

        assertThrows(EventAlreadyStartedException.class, () -> service.resetEvent(NOW));
    }
}
