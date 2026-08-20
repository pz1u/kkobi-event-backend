package org.kkobi.event.game.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
public class EventActionLogDto {

    private Long actionLogId;
    private Long participantId;
    private Long sessionId;
    private String scenarioId;
    private Integer gameTick;
    private String actionType;
    private String assetType;
    private Long actionAmount;
    private String marketState;
    private String depositStatus;
    private Long currentCash;
    private Long currentStock;
    private Long currentDeposit;
    private BigDecimal rtScoreDelta;
    private BigDecimal lhScoreDelta;
    private BigDecimal rpScoreDelta;
    private Timestamp createdAt;
}
