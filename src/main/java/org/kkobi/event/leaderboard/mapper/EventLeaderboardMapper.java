package org.kkobi.event.leaderboard.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.leaderboard.domain.EventLeaderboardRow;

import java.util.List;

public interface EventLeaderboardMapper {

    // session의 확정된 결과(event_game_results)를 return_rate DESC 기준 RANK()로 정렬 조회
    List<EventLeaderboardRow> findRankings(@Param("sessionId") Long sessionId);
}
