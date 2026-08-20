package org.kkobi.event.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.domain.EventParticipant;

public interface EventParticipantMapper {

    // 참가자 저장
    int saveParticipant(EventParticipant participant);

    // 세션 내 닉네임 중복 여부 조회
    boolean existsBySessionIdAndNickname(
            @Param("sessionId") Long sessionId,
            @Param("nickname") String nickname
    );

    // participantToken으로 참가자 조회
    EventParticipant findByParticipantToken(@Param("participantToken") String participantToken);

    // participantId로 참가자 조회
    EventParticipant findByParticipantId(@Param("participantId") Long participantId);
}
