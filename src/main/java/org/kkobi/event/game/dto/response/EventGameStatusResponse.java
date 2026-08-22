package org.kkobi.event.game.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.kkobi.game.dto.ScenarioEventDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class EventGameStatusResponse {

    private final Long participantId;
    private final Long sessionId;
    private final String scenarioId;

    private final String eventStatus;

    private final LocalDateTime serverNow;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;

    private final long remainingSeconds;

    private final int currentTick;
    private final int totalTickCount;

    private final long currentPrice;
    private final List<ScenarioEventDto> events;

    private final long initialCash;
    private final long cashBalance;
    private final long stockPrincipal;
    private final BigDecimal stockQuantity;
    private final long depositAmount;
    // 원금(cost basis) 기준 합계: cashBalance + stockPrincipal + depositAmount
    private final long totalAssetPrincipal;
    // 현재 평가자산: cashBalance + depositAmount + stockQuantity * currentPrice
    private final long totalAssetValue;
    private final String depositStatus;
}
