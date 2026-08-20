package org.kkobi.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.mapper.EventSessionMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventSessionServiceTest {

    private final EventSessionMapper eventSessionMapper = mock(EventSessionMapper.class);
    private final EventSessionServiceImpl service = new EventSessionServiceImpl(eventSessionMapper);

    @Test
    @DisplayName("행사 상태 조회 시 serverNow와 participantCount를 함께 반환한다")
    void getEventStatusReturnsServerNowAndParticipantCount() {
        EventSession session = new EventSession();
        session.setSessionId(1L);
        session.setStatus(EventSessionStatus.WAITING);
        session.setScenarioId("SC001");
        session.setDurationSeconds(180);
        session.setInitialCash(10_000_000L);

        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 11, 30, 0);

        when(eventSessionMapper.findCurrentSession()).thenReturn(session);
        when(eventSessionMapper.countParticipantsBySessionId(1L)).thenReturn(12);

        EventStatusResponse response = service.getEventStatus(now);

        assertEquals(1L, response.getSessionId());
        assertEquals("WAITING", response.getStatus());
        assertEquals("SC001", response.getScenarioId());
        assertEquals(now, response.getServerNow());
        assertEquals(180, response.getDurationSeconds());
        assertEquals(10_000_000L, response.getInitialCash());
        assertEquals(12, response.getParticipantCount());
    }
}
