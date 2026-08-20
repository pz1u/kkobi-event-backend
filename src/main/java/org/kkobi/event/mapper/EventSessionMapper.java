package org.kkobi.event.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.enums.EventSessionStatus;

import java.time.LocalDateTime;

public interface EventSessionMapper {

    // 현재 사용할 행사 세션을 조회 (행사 1회 운영 기준 최신 세션 1건)
    EventSession findCurrentSession();

    // sessionId로 행사 세션을 조회
    EventSession findSessionById(@Param("sessionId") Long sessionId);

    // 상태 변경을 위해 현재 세션을 조회하고 FOR UPDATE로 잠금 (동시 START/FINISH 요청 직렬화)
    EventSession findCurrentSessionForUpdate();

    // 특정 세션의 현재 참가자 수를 조회
    int countParticipantsBySessionId(@Param("sessionId") Long sessionId);

    // COUNTDOWN 시작 시간을 확정하고 상태를 COUNTDOWN으로 갱신 (WAITING 상태에서만 적용)
    int startCountdown(
            @Param("sessionId") Long sessionId,
            @Param("countdownStartedAt") LocalDateTime countdownStartedAt,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    // 세션 상태를 전이 (fromStatus인 경우에만 적용)
    int transitionStatus(
            @Param("sessionId") Long sessionId,
            @Param("fromStatus") EventSessionStatus fromStatus,
            @Param("toStatus") EventSessionStatus toStatus
    );

    // 상태를 FINISHED로 전이하며 실제 종료 시각(finishedAt)을 함께 확정 (fromStatus인 경우에만 적용)
    int transitionToFinished(
            @Param("sessionId") Long sessionId,
            @Param("fromStatus") EventSessionStatus fromStatus,
            @Param("finishedAt") LocalDateTime finishedAt
    );
}
