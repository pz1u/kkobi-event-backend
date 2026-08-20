package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventParticipantMeResponse {

    private Long participantId;
    private Long sessionId;
    private String nickname;
    private String eventStatus;
}
