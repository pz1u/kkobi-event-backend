package org.kkobi.event.leaderboard.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.exception.EventNotFinishedException;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.game.service.EventGameResultService;
import org.kkobi.event.leaderboard.domain.EventLeaderboardRow;
import org.kkobi.event.leaderboard.dto.response.EventAdminLeaderboardResponse;
import org.kkobi.event.leaderboard.dto.response.EventLeaderboardEntry;
import org.kkobi.event.leaderboard.dto.response.EventLeaderboardResponse;
import org.kkobi.event.leaderboard.mapper.EventLeaderboardMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.mapper.EventSessionMapper;
import org.kkobi.event.service.EventSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 행사 최종 리더보드를 조회한다. 순위는 오직 event_game_results.return_rate(저장된 snapshot)
// 기준이며, 리더보드에서 수익률/성향을 다시 계산하지 않는다.
// 결과 화면(/api/event/result)을 열지 않은 참가자의 결과는 조회 전에 EventGameResultService의
// 결과 확정 로직을 그대로 재사용해 일괄 확정한다.
@Service
@RequiredArgsConstructor
public class EventLeaderboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionMapper eventSessionMapper;
    private final EventSessionService eventSessionService;
    private final EventGameStateMapper eventGameStateMapper;
    private final EventGameResultService eventGameResultService;
    private final EventLeaderboardMapper eventLeaderboardMapper;

    @Transactional
    public EventLeaderboardResponse getLeaderboard(String participantToken) {
        return getLeaderboard(participantToken, LocalDateTime.now(KST));
    }

    EventLeaderboardResponse getLeaderboard(String participantToken, LocalDateTime now) {
        EventParticipant requester = findParticipant(participantToken);

        EventSession session = eventSessionService.getSynchronizedSession(requester.getSessionId(), now);
        validateFinished(session);

        confirmAllResults(session);

        List<EventLeaderboardRow> rows = eventLeaderboardMapper.findRankings(session.getSessionId());
        EventLeaderboardRow myRow = rows.stream()
                .filter(row -> row.getParticipantId().equals(requester.getParticipantId()))
                .findFirst()
                .orElse(null);

        List<EventLeaderboardEntry> rankings = rows.stream()
                .map(this::toEntry)
                .toList();

        return new EventLeaderboardResponse(
                session.getSessionId(),
                rows.size(),
                myRow == null ? null : myRow.getRank().intValue(),
                myRow == null ? null : myRow.getReturnRate(),
                rankings
        );
    }

    // 관리자용 리더보드 조회. participantToken 없이 현재 세션 기준으로 순위를 조회하며
    // myRank/myReturnRate는 응답에 포함하지 않는다. 순위 계산 로직은 재사용하고 복사하지 않는다.
    @Transactional
    public EventAdminLeaderboardResponse getLeaderboardForAdmin() {
        return getLeaderboardForAdmin(LocalDateTime.now(KST));
    }

    EventAdminLeaderboardResponse getLeaderboardForAdmin(LocalDateTime now) {
        EventSession currentSession = eventSessionMapper.findCurrentSession();
        if (currentSession == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }

        EventSession session = eventSessionService.getSynchronizedSession(currentSession.getSessionId(), now);
        validateFinished(session);

        confirmAllResults(session);

        List<EventLeaderboardRow> rows = eventLeaderboardMapper.findRankings(session.getSessionId());
        List<EventLeaderboardEntry> rankings = rows.stream()
                .map(this::toEntry)
                .toList();

        return new EventAdminLeaderboardResponse(session.getSessionId(), rows.size(), rankings);
    }

    // FINISHED 시점에 실제 게임을 진행한 참가자(event_game_state 존재) 전원의 결과를
    // 결과 화면 조회 여부와 무관하게 확정한다. 이미 결과가 있으면 재계산하지 않는다.
    private void confirmAllResults(EventSession session) {
        List<EventGameState> gameStates = eventGameStateMapper.findBySessionId(session.getSessionId());
        for (EventGameState gameState : gameStates) {
            eventGameResultService.getOrCreateResult(session, gameState);
        }
    }

    private EventParticipant findParticipant(String participantToken) {
        if (participantToken == null || participantToken.isBlank()) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }
        EventParticipant participant = eventParticipantMapper.findByParticipantToken(participantToken);
        if (participant == null) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }
        return participant;
    }

    private void validateFinished(EventSession session) {
        if (session.getStatus() == EventSessionStatus.WAITING
                || session.getStatus() == EventSessionStatus.COUNTDOWN) {
            throw new EventNotStartedException("게임이 아직 시작되지 않았습니다.");
        }
        if (session.getStatus() == EventSessionStatus.RUNNING) {
            throw new EventNotFinishedException("게임이 아직 종료되지 않았습니다.");
        }
    }

    private EventLeaderboardEntry toEntry(EventLeaderboardRow row) {
        return new EventLeaderboardEntry(
                row.getRank().intValue(),
                row.getParticipantId(),
                row.getNickname(),
                row.getFinalAsset(),
                row.getReturnRate(),
                row.getPersonaId(),
                row.getPersonaName()
        );
    }
}
