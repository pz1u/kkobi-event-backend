package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EventWaitingParticipantResponse {

    private final Long participantId;
    private final String nickname;
}
