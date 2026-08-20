package org.kkobi.event.service;

import org.kkobi.event.dto.request.EventParticipantJoinRequest;
import org.kkobi.event.dto.response.EventAdminParticipantListResponse;
import org.kkobi.event.dto.response.EventParticipantJoinResponse;
import org.kkobi.event.dto.response.EventParticipantMeResponse;

public interface EventParticipantService {

    // 닉네임으로 행사에 참가하고 participantToken을 발급
    EventParticipantJoinResponse join(EventParticipantJoinRequest request);

    // participantToken으로 기존 참가자 정보를 조회
    EventParticipantMeResponse getMe(String participantToken);

    // 관리자용: 현재 세션의 참가자 전체 목록 조회 (participantToken 미노출)
    EventAdminParticipantListResponse getParticipantsForAdmin();
}
