package org.kkobi.event.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 관리자 참가자 목록 항목. participantToken은 노출하지 않는다.
@Getter
@AllArgsConstructor
public class EventAdminParticipantResponse {

    private final Long participantId;
    private final String nickname;
}
