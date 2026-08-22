package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class EventWaitingParticipantListResponse {

    private final int participantCount;
    private final List<EventWaitingParticipantResponse> participants;
}
