package org.kkobi.event.game.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class EventGameActionResponse {

    private final Long actionLogId;
    private final int gameTick;
    private final String actionType;
    private final String assetType;
    private final long actionAmount;
    private final long cashBalance;
    private final long stockPrincipal;
    private final BigDecimal stockQuantity;
    private final long depositAmount;
    private final long totalAssetPrincipal;
    private final long totalAssetValue;
    private final String marketState;
    private final String depositStatus;
    private final BigDecimal rtScoreDelta;
    private final BigDecimal lhScoreDelta;
    private final BigDecimal rpScoreDelta;
}
