package org.kkobi.event.game.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.game.domain.EventGameResult;

public interface EventGameResultMapper {

    // participantId로 최종 결과 조회
    EventGameResult findByParticipantId(@Param("participantId") Long participantId);

    // 최종 결과 최초 저장 (참가자당 1건, UNIQUE 제약을 최종 방어선으로 사용)
    int saveResult(EventGameResult result);
}
