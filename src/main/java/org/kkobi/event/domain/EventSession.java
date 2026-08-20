package org.kkobi.event.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.kkobi.event.enums.EventSessionStatus;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventSession {

    private Long sessionId;
    private EventSessionStatus status;
    private String scenarioId;
    private LocalDateTime countdownStartedAt;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationSeconds;
    private Long initialCash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
