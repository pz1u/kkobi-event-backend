package org.kkobi.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.request.EventParticipantJoinRequest;
import org.kkobi.event.dto.response.EventParticipantJoinResponse;
import org.kkobi.event.dto.response.EventParticipantMeResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.DuplicateNicknameException;
import org.kkobi.event.exception.EventJoinNotAllowedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.mapper.EventSessionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventParticipantServiceTest {

    private final EventSessionMapper eventSessionMapper = mock(EventSessionMapper.class);
    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventParticipantServiceImpl service =
            new EventParticipantServiceImpl(eventSessionMapper, eventParticipantMapper);

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
    @DisplayName("WAITING 상태에서는 참가에 성공하고 participantToken을 발급한다")
    void joinSucceedsWhenWaiting() {
        when(eventSessionMapper.findCurrentSession()).thenReturn(createSession(EventSessionStatus.WAITING));
        when(eventParticipantMapper.existsBySessionIdAndNickname(1L, "박지우")).thenReturn(false);

        EventParticipantJoinRequest request = new EventParticipantJoinRequest();
        request.setNickname("박지우");

        EventParticipantJoinResponse response = service.join(request);

        assertEquals("박지우", response.getNickname());
        assertEquals(1L, response.getSessionId());
        assertEquals("WAITING", response.getEventStatus());
        assertNotNull(response.getParticipantToken());

        verify(eventParticipantMapper, times(1)).saveParticipant(any(EventParticipant.class));
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 신규 참가가 거절된다")
    void joinRejectedWhenCountdown() {
        assertJoinRejected(EventSessionStatus.COUNTDOWN);
    }

    @Test
    @DisplayName("RUNNING 상태에서는 신규 참가가 거절된다")
    void joinRejectedWhenRunning() {
        assertJoinRejected(EventSessionStatus.RUNNING);
    }

    @Test
    @DisplayName("FINISHED 상태에서는 신규 참가가 거절된다")
    void joinRejectedWhenFinished() {
        assertJoinRejected(EventSessionStatus.FINISHED);
    }

    private void assertJoinRejected(EventSessionStatus status) {
        when(eventSessionMapper.findCurrentSession()).thenReturn(createSession(status));

        EventParticipantJoinRequest request = new EventParticipantJoinRequest();
        request.setNickname("박지우");

        assertThrows(EventJoinNotAllowedException.class, () -> service.join(request));
        verify(eventParticipantMapper, times(0)).saveParticipant(any(EventParticipant.class));
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임은 거절된다")
    void joinRejectsDuplicateNickname() {
        when(eventSessionMapper.findCurrentSession()).thenReturn(createSession(EventSessionStatus.WAITING));
        when(eventParticipantMapper.existsBySessionIdAndNickname(1L, "박지우")).thenReturn(true);

        EventParticipantJoinRequest request = new EventParticipantJoinRequest();
        request.setNickname("박지우");

        assertThrows(DuplicateNicknameException.class, () -> service.join(request));
        verify(eventParticipantMapper, times(0)).saveParticipant(any(EventParticipant.class));
    }

    @Test
    @DisplayName("유효한 participantToken으로 참가자 정보를 조회한다")
    void getMeReturnsParticipantForValidToken() {
        EventParticipant participant = new EventParticipant();
        participant.setParticipantId(1L);
        participant.setSessionId(1L);
        participant.setNickname("박지우");
        participant.setParticipantToken("token-123");

        when(eventParticipantMapper.findByParticipantToken("token-123")).thenReturn(participant);
        when(eventSessionMapper.findSessionById(1L)).thenReturn(createSession(EventSessionStatus.RUNNING));

        EventParticipantMeResponse response = service.getMe("token-123");

        assertEquals(1L, response.getParticipantId());
        assertEquals(1L, response.getSessionId());
        assertEquals("박지우", response.getNickname());
        assertEquals("RUNNING", response.getEventStatus());
    }

    @Test
    @DisplayName("존재하지 않는 participantToken은 거절된다")
    void getMeRejectsUnknownToken() {
        when(eventParticipantMapper.findByParticipantToken("unknown")).thenReturn(null);

        assertThrows(InvalidParticipantTokenException.class, () -> service.getMe("unknown"));
    }

    @Test
    @DisplayName("participantToken이 없으면 거절된다")
    void getMeRejectsMissingToken() {
        assertThrows(InvalidParticipantTokenException.class, () -> service.getMe(null));
        assertThrows(InvalidParticipantTokenException.class, () -> service.getMe(" "));
    }
}
