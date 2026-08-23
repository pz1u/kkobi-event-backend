package org.kkobi.event.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventAlreadyStartedException;
import org.kkobi.event.game.mapper.EventActionLogMapper;
import org.kkobi.event.game.mapper.EventGameResultMapper;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.mapper.EventSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

// 관리자의 행사 리허설/재테스트 초기화를 처리한다.
// 현재 session row는 유지한 채 참가/진행 데이터만 삭제하고 상태를 WAITING으로 되돌린다.
@Service
@RequiredArgsConstructor
public class EventAdminService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int EVENT_DURATION_SECONDS = 175;

    private final EventSessionMapper eventSessionMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventActionLogMapper eventActionLogMapper;
    private final EventGameResultMapper eventGameResultMapper;

    @Transactional
    public EventStatusResponse resetEvent() {
        return resetEvent(LocalDateTime.now(KST));
    }

    EventStatusResponse resetEvent(LocalDateTime now) {
        // FOR UPDATE로 세션 row를 잠가 동시 reset 요청을 직렬화한다
        EventSession session = eventSessionMapper.findCurrentSessionForUpdate();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }
        if (session.getStatus() == EventSessionStatus.COUNTDOWN
                || session.getStatus() == EventSessionStatus.RUNNING) {
            throw new EventAlreadyStartedException(
                    "게임이 진행 중이면 초기화할 수 없습니다. 먼저 종료(finish)한 뒤 다시 시도해주세요.");
        }

        Long sessionId = session.getSessionId();
        // FK 참조 순서상 자식 테이블(event_game_results/event_action_logs/event_game_states)을
        // event_participants보다 먼저 삭제한다.
        eventGameResultMapper.deleteBySessionId(sessionId);
        eventActionLogMapper.deleteBySessionId(sessionId);
        eventGameStateMapper.deleteBySessionId(sessionId);
        eventParticipantMapper.deleteBySessionId(sessionId);

        int updatedRows = eventSessionMapper.resetSession(sessionId, EVENT_DURATION_SECONDS);
        if (updatedRows == 0) {
            throw new EventAlreadyStartedException(
                    "게임이 진행 중이면 초기화할 수 없습니다. 먼저 종료(finish)한 뒤 다시 시도해주세요.");
        }

        return new EventStatusResponse(
                sessionId,
                EventSessionStatus.WAITING.name(),
                session.getScenarioId(),
                now,
                null,
                null,
                null,
                EVENT_DURATION_SECONDS,
                session.getInitialCash(),
                0
        );
    }
}
