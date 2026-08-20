package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventParticipantJoinResponse {

    private Long participantId;
    private String nickname;
    private String participantToken;
    private Long sessionId;
    private String eventStatus;
}
