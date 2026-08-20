package org.kkobi.event.game.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventGameState {

    private Long gameStateId;
    private Long participantId;
    private Long sessionId;
    private String scenarioId;
    private Long initialCash;
    private Long cashBalance;
    private Long stockPrincipal;
    private BigDecimal stockQuantity;
    private Long depositAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
