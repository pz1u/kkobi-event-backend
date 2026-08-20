package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class EventAdminParticipantListResponse {

    private final int participantCount;
    private final List<EventAdminParticipantResponse> participants;
}
