package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventStatusResponse {

    private Long sessionId;
    private String status;
    private String scenarioId;
    private LocalDateTime serverNow;
    private LocalDateTime countdownStartedAt;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationSeconds;
    private Long initialCash;
    private Integer participantCount;
}
