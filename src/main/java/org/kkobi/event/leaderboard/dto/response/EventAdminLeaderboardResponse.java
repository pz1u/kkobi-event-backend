package org.kkobi.event.leaderboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 관리자용 리더보드 응답. 특정 참가자 관점의 myRank/myReturnRate는 필요 없다.
@Getter
@AllArgsConstructor
public class EventAdminLeaderboardResponse {

    private final Long sessionId;
    private final int participantCount;

    private final List<EventLeaderboardEntry> rankings;
}
