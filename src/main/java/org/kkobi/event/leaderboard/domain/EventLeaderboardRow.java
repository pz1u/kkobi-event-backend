package org.kkobi.event.leaderboard.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventLeaderboardRow {

    private Long rank;
    private Long participantId;
    private String nickname;
    private Long finalAsset;
    private BigDecimal returnRate;
    private Long personaId;
    private String personaName;
}
