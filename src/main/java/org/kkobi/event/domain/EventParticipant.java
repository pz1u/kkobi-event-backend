package org.kkobi.event.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventParticipant {

    private Long participantId;
    private Long sessionId;
    private String nickname;
    private String participantToken;
    private LocalDateTime joinedAt;
    private LocalDateTime updatedAt;
}
