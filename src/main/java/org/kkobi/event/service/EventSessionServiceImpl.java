package org.kkobi.event.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.mapper.EventSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class EventSessionServiceImpl implements EventSessionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EventSessionMapper eventSessionMapper;

    @Override
    @Transactional(readOnly = true)
    public EventStatusResponse getEventStatus() {
        return getEventStatus(LocalDateTime.now(KST));
    }

    EventStatusResponse getEventStatus(LocalDateTime now) {
        EventSession session = eventSessionMapper.findCurrentSession();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }

        int participantCount = eventSessionMapper.countParticipantsBySessionId(session.getSessionId());

        return new EventStatusResponse(
                session.getSessionId(),
                session.getStatus().name(),
                session.getScenarioId(),
                now,
                session.getCountdownStartedAt(),
                session.getStartAt(),
                session.getEndAt(),
                session.getDurationSeconds(),
                session.getInitialCash(),
                participantCount
        );
    }
}
