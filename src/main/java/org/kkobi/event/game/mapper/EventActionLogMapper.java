package org.kkobi.event.game.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.game.dto.EventActionLogDto;

import java.util.List;

public interface EventActionLogMapper {

    // 행사 참가자 게임 행동 로그 저장
    int saveActionLog(EventActionLogDto actionLog);

    // 참가자별 게임 행동 로그를 tick 순서로 조회
    List<EventActionLogDto> getActionLogsByParticipantId(@Param("participantId") Long participantId);

    // sessionId 기준 행동 로그 전체 삭제 (행사 초기화용)
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
