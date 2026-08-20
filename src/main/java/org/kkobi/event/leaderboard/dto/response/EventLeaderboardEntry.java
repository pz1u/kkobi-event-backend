package org.kkobi.event.leaderboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class EventLeaderboardEntry {

    private final int rank;
    private final Long participantId;
    private final String nickname;
    private final long finalAsset;
    private final BigDecimal returnRate;
    private final Long personaId;
    private final String personaName;
}
