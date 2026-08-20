package org.kkobi.event.game.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.game.domain.EventGameState;

import java.math.BigDecimal;

public interface EventGameStateMapper {

    // participantId로 게임 상태 조회
    EventGameState findByParticipantId(@Param("participantId") Long participantId);

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
}
