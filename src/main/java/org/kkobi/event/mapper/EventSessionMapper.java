package org.kkobi.event.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.domain.EventSession;

public interface EventSessionMapper {

    // 현재 사용할 행사 세션을 조회 (행사 1회 운영 기준 최신 세션 1건)
    EventSession findCurrentSession();

    // sessionId로 행사 세션을 조회
    EventSession findSessionById(@Param("sessionId") Long sessionId);

    // 특정 세션의 현재 참가자 수를 조회
    int countParticipantsBySessionId(@Param("sessionId") Long sessionId);
}
