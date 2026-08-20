package org.kkobi.event.game.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventGameResult {

    private Long resultId;
    private Long participantId;
    private Long sessionId;
    private Long personaId;

    private Long initialAsset;
    private Long finalAsset;
    private BigDecimal returnRate;

    private BigDecimal rtScore;
    private BigDecimal lhScore;
    private BigDecimal rpScore;

    private Integer finalTick;
    private Long finalPrice;

    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
