package org.kkobi.event.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyFinishedException;
import org.kkobi.event.exception.EventAlreadyStartedException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.game.constant.EventGameTiming;
import org.kkobi.event.mapper.EventSessionMapper;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.service.ScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class EventSessionServiceImpl implements EventSessionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int COUNTDOWN_SECONDS = 5;

    private final EventSessionMapper eventSessionMapper;
    private final ScenarioService scenarioService;

    @Override
    @Transactional
    public EventStatusResponse getEventStatus() {
        return getEventStatus(LocalDateTime.now(KST));
    }

    EventStatusResponse getEventStatus(LocalDateTime now) {
        EventSession session = eventSessionMapper.findCurrentSession();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }

        synchronizeStatus(session, now);

        int participantCount = eventSessionMapper.countParticipantsBySessionId(session.getSessionId());

        return buildStatusResponse(session, now, participantCount);
    }

    // 관리자 요청으로 행사를 시작 (WAITING → COUNTDOWN, 시간 확정)
    @Override
    @Transactional
    public EventStatusResponse startEvent() {
        return startEvent(LocalDateTime.now(KST));
    }

    EventStatusResponse startEvent(LocalDateTime now) {
        EventSession session = eventSessionMapper.findCurrentSessionForUpdate();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }
        if (session.getStatus() != EventSessionStatus.WAITING) {
            throw new EventAlreadyStartedException("이미 시작된 행사입니다.");
        }

        LocalDateTime countdownStartedAt = now;
        LocalDateTime startAt = now.plusSeconds(COUNTDOWN_SECONDS);
        ScenarioDto scenario = scenarioService.getScenario(session.getScenarioId());
        long totalPauseSeconds = EventGameTiming.calculateTotalPauseSeconds(scenario);
        LocalDateTime endAt = startAt.plusSeconds(
                session.getDurationSeconds() + totalPauseSeconds);

        int updatedRows = eventSessionMapper.startCountdown(
                session.getSessionId(), countdownStartedAt, startAt, endAt);
        if (updatedRows == 0) {
            throw new EventAlreadyStartedException("이미 시작된 행사입니다.");
        }

        session.setStatus(EventSessionStatus.COUNTDOWN);
        session.setCountdownStartedAt(countdownStartedAt);
        session.setStartAt(startAt);
        session.setEndAt(endAt);

        int participantCount = eventSessionMapper.countParticipantsBySessionId(session.getSessionId());

        return buildStatusResponse(session, now, participantCount);
    }

    // 관리자 요청으로 행사를 강제 종료 (COUNTDOWN/RUNNING → FINISHED)
    @Override
    @Transactional
    public EventStatusResponse finishEvent() {
        return finishEvent(LocalDateTime.now(KST));
    }

    EventStatusResponse finishEvent(LocalDateTime now) {
        EventSession session = eventSessionMapper.findCurrentSessionForUpdate();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }

        if (session.getStatus() == EventSessionStatus.WAITING) {
            throw new EventNotStartedException("아직 시작되지 않은 행사입니다.");
        }
        if (session.getStatus() == EventSessionStatus.FINISHED) {
            throw new EventAlreadyFinishedException("이미 종료된 행사입니다.");
        }

        // 이미 확정된 예정 종료 시각(endAt)은 그대로 보존하고, 실제 종료 시각(finishedAt)은 강제 종료 요청 시각으로 확정한다
        int updatedRows = eventSessionMapper.transitionToFinished(
                session.getSessionId(), session.getStatus(), now);
        if (updatedRows == 0) {
            throw new EventAlreadyFinishedException("이미 종료된 행사입니다.");
        }

        session.setStatus(EventSessionStatus.FINISHED);
        session.setFinishedAt(now);

        int participantCount = eventSessionMapper.countParticipantsBySessionId(session.getSessionId());

        return buildStatusResponse(session, now, participantCount);
    }

    // 세션의 최신 동기화 상태를 조회 (참가자 상태 조회 등에서 재사용)
    @Override
    @Transactional
    public EventSessionStatus getSynchronizedStatus(Long sessionId) {
        return getSynchronizedSession(sessionId, LocalDateTime.now(KST)).getStatus();
    }

    // 세션의 최신 동기화된 전체 정보를 조회 (행사 게임 Tick 계산 등에서 재사용)
    @Override
    @Transactional
    public EventSession getSynchronizedSession(Long sessionId, LocalDateTime now) {
        EventSession session = eventSessionMapper.findSessionById(sessionId);
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }

        synchronizeStatus(session, now);

        return session;
    }

    // 현재 서버 시간과 startAt/endAt을 비교해 필요한 경우에만 상태를 갱신한다
    private void synchronizeStatus(EventSession session, LocalDateTime now) {
        if (session.getStatus() == EventSessionStatus.COUNTDOWN
                && session.getStartAt() != null
                && !now.isBefore(session.getStartAt())) {
            int updatedRows = eventSessionMapper.transitionStatus(
                    session.getSessionId(), EventSessionStatus.COUNTDOWN, EventSessionStatus.RUNNING);
            if (updatedRows > 0) {
                session.setStatus(EventSessionStatus.RUNNING);
            }
        }

        if (session.getStatus() == EventSessionStatus.RUNNING
                && session.getEndAt() != null
                && !now.isBefore(session.getEndAt())) {
            // 정상 종료의 실제 기준 시각은 polling 요청 시각이 아니라 예정된 종료 시각(endAt)이다
            int updatedRows = eventSessionMapper.transitionToFinished(
                    session.getSessionId(), EventSessionStatus.RUNNING, session.getEndAt());
            if (updatedRows > 0) {
                session.setStatus(EventSessionStatus.FINISHED);
                session.setFinishedAt(session.getEndAt());
            }
        }
    }

    private EventStatusResponse buildStatusResponse(
            EventSession session,
            LocalDateTime now,
            int participantCount
    ) {
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
