package org.kkobi.event.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.domain.EventGameResult;
import org.kkobi.event.game.domain.EventGameState;
import org.kkobi.event.game.exception.EventNotFinishedException;
import org.kkobi.event.game.mapper.EventGameStateMapper;
import org.kkobi.event.game.service.EventGameResultService;
import org.kkobi.event.leaderboard.domain.EventLeaderboardRow;
import org.kkobi.event.leaderboard.dto.response.EventLeaderboardResponse;
import org.kkobi.event.leaderboard.mapper.EventLeaderboardMapper;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.service.EventSessionService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventLeaderboardServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0, 0);
    private static final String TOKEN = "token-123";
    private static final Long SESSION_ID = 10L;

    private final EventParticipantMapper eventParticipantMapper = mock(EventParticipantMapper.class);
    private final EventSessionService eventSessionService = mock(EventSessionService.class);
    private final EventGameStateMapper eventGameStateMapper = mock(EventGameStateMapper.class);
    private final EventGameResultService eventGameResultService = mock(EventGameResultService.class);
    private final EventLeaderboardMapper eventLeaderboardMapper = mock(EventLeaderboardMapper.class);

    private final EventLeaderboardService service = new EventLeaderboardService(
            eventParticipantMapper,
            eventSessionService,
            eventGameStateMapper,
            eventGameResultService,
            eventLeaderboardMapper
    );

    private EventParticipant createParticipant(Long participantId) {
        EventParticipant participant = new EventParticipant();
        participant.setParticipantId(participantId);
        participant.setSessionId(SESSION_ID);
        participant.setNickname("참가자" + participantId);
        participant.setParticipantToken(TOKEN);
        return participant;
    }

    private EventSession createSession(EventSessionStatus status) {
        EventSession session = new EventSession();
        session.setSessionId(SESSION_ID);
        session.setStatus(status);
        session.setScenarioId("SC001");
        session.setInitialCash(10_000_000L);
        return session;
    }

    private EventGameState createGameState(Long participantId) {
        EventGameState gameState = new EventGameState();
        gameState.setGameStateId(participantId + 100);
        gameState.setParticipantId(participantId);
        gameState.setSessionId(SESSION_ID);
        gameState.setScenarioId("SC001");
        return gameState;
    }

    private EventLeaderboardRow row(long rank, Long participantId, String nickname, String returnRate) {
        return new EventLeaderboardRow(
                rank, participantId, nickname, 11_000_000L, new BigDecimal(returnRate), 2L, "투자왕"
        );
    }

    @Test
    @DisplayName("WAITING 상태에서는 리더보드 조회가 거절된다")
    void rejectsWhenWaiting() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(1L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.WAITING));

        assertThrows(EventNotStartedException.class, () -> service.getLeaderboard(TOKEN, NOW));
    }

    @Test
    @DisplayName("COUNTDOWN 상태에서는 리더보드 조회가 거절된다")
    void rejectsWhenCountdown() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(1L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.COUNTDOWN));

        assertThrows(EventNotStartedException.class, () -> service.getLeaderboard(TOKEN, NOW));
    }

    @Test
    @DisplayName("RUNNING 상태에서는 리더보드 조회가 거절된다")
    void rejectsWhenRunning() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(1L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.RUNNING));

        assertThrows(EventNotFinishedException.class, () -> service.getLeaderboard(TOKEN, NOW));
    }

    @Test
    @DisplayName("유효하지 않은 participantToken은 거절된다")
    void rejectsInvalidToken() {
        when(eventParticipantMapper.findByParticipantToken("unknown")).thenReturn(null);

        assertThrows(InvalidParticipantTokenException.class, () -> service.getLeaderboard("unknown", NOW));
    }

    @Test
    @DisplayName("FINISHED 상태에서는 정상적으로 순위를 조회하고 요청자의 myRank/myReturnRate를 채운다")
    void returnsRankingsWithMyRank() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(12L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventGameStateMapper.findBySessionId(SESSION_ID)).thenReturn(List.of());
        when(eventLeaderboardMapper.findRankings(SESSION_ID)).thenReturn(List.of(
                row(1, 7L, "투자왕", "12.31"),
                row(2, 12L, "박지우", "10.22"),
                row(2, 19L, "개미왕", "10.22"),
                row(4, 3L, "투자초보", "8.91")
        ));

        EventLeaderboardResponse response = service.getLeaderboard(TOKEN, NOW);

        assertEquals(SESSION_ID, response.getSessionId());
        assertEquals(4, response.getParticipantCount());
        assertEquals(2, response.getMyRank());
        assertEquals(0, new BigDecimal("10.22").compareTo(response.getMyReturnRate()));
        assertEquals(4, response.getRankings().size());
        assertEquals(1, response.getRankings().get(0).getRank());
        assertEquals(2, response.getRankings().get(1).getRank());
        assertEquals(2, response.getRankings().get(2).getRank());
        assertEquals(4, response.getRankings().get(3).getRank());
    }

    @Test
    @DisplayName("게임 기록이 없는 요청 참가자는 다른 순위는 볼 수 있지만 myRank는 null이다")
    void myRankIsNullWhenRequesterHasNoGameRecord() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(99L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventGameStateMapper.findBySessionId(SESSION_ID)).thenReturn(List.of());
        when(eventLeaderboardMapper.findRankings(SESSION_ID)).thenReturn(List.of(
                row(1, 7L, "투자왕", "12.31")
        ));

        EventLeaderboardResponse response = service.getLeaderboard(TOKEN, NOW);

        assertNull(response.getMyRank());
        assertNull(response.getMyReturnRate());
        assertEquals(1, response.getRankings().size());
    }

    @Test
    @DisplayName("결과 화면을 조회하지 않은 참가자도 리더보드 조회 시 결과가 자동 확정된다")
    void confirmsResultsForAllGameParticipantsBeforeRanking() {
        EventSession session = createSession(EventSessionStatus.FINISHED);
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(1L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW))).thenReturn(session);

        EventGameState stateA = createGameState(1L);
        EventGameState stateB = createGameState(2L);
        EventGameState stateC = createGameState(3L);
        when(eventGameStateMapper.findBySessionId(SESSION_ID)).thenReturn(List.of(stateA, stateB, stateC));
        when(eventGameResultService.getOrCreateResult(any(), any())).thenReturn(mock(EventGameResult.class));
        when(eventLeaderboardMapper.findRankings(SESSION_ID)).thenReturn(List.of());

        service.getLeaderboard(TOKEN, NOW);

        verify(eventGameResultService, times(1)).getOrCreateResult(session, stateA);
        verify(eventGameResultService, times(1)).getOrCreateResult(session, stateB);
        verify(eventGameResultService, times(1)).getOrCreateResult(session, stateC);
    }

    @Test
    @DisplayName("게임을 전혀 진행하지 않은 참가자는 결과 확정 대상에서 제외된다")
    void skipsConfirmationWhenNoGameStatesExist() {
        when(eventParticipantMapper.findByParticipantToken(TOKEN)).thenReturn(createParticipant(1L));
        when(eventSessionService.getSynchronizedSession(eq(SESSION_ID), eq(NOW)))
                .thenReturn(createSession(EventSessionStatus.FINISHED));
        when(eventGameStateMapper.findBySessionId(SESSION_ID)).thenReturn(List.of());
        when(eventLeaderboardMapper.findRankings(SESSION_ID)).thenReturn(List.of());

        EventLeaderboardResponse response = service.getLeaderboard(TOKEN, NOW);

        verify(eventGameResultService, never()).getOrCreateResult(any(), any());
        assertEquals(0, response.getParticipantCount());
    }
}
