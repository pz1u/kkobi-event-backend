package org.kkobi.event.leaderboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@AllArgsConstructor
public class EventLeaderboardResponse {

    private final Long sessionId;
    private final int participantCount;

    private final Integer myRank;
    private final BigDecimal myReturnRate;

    private final List<EventLeaderboardEntry> rankings;
}
