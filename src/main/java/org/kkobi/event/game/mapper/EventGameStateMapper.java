package org.kkobi.event.game.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.game.domain.EventGameState;

import java.math.BigDecimal;
import java.util.List;

public interface EventGameStateMapper {

    // participantId로 게임 상태 조회
    EventGameState findByParticipantId(@Param("participantId") Long participantId);

    // 결과 최초 확정 시 같은 참가자의 동시 계산을 직렬화하기 위한 잠금 조회
    EventGameState findByParticipantIdForUpdate(@Param("participantId") Long participantId);

    // sessionId로 실제 게임을 진행한 참가자의 게임 상태 전체 조회 (리더보드 대상 판별용)
    List<EventGameState> findBySessionId(@Param("sessionId") Long sessionId);

    // 게임 상태 최초 저장 (참가자당 1건)
    int saveGameState(EventGameState gameState);

    // 행동 처리 후 잔액과 보유 수량을 갱신
    int updateBalances(
            @Param("gameStateId") Long gameStateId,
            @Param("cashBalance") Long cashBalance,
            @Param("stockPrincipal") Long stockPrincipal,
            @Param("stockQuantity") BigDecimal stockQuantity,
            @Param("depositAmount") Long depositAmount
    );

    // sessionId 기준 게임 상태 전체 삭제 (행사 초기화용)
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
