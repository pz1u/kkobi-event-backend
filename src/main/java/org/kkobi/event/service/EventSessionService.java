package org.kkobi.event.service;

import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.enums.EventSessionStatus;

public interface EventSessionService {

    // 현재 행사 상태를 조회 (조회 시점에 상태 자동 동기화)
    EventStatusResponse getEventStatus();

    // 관리자 요청으로 행사를 시작 (WAITING → COUNTDOWN, 시간 확정)
    EventStatusResponse startEvent();

    // 관리자 요청으로 행사를 강제 종료 (COUNTDOWN/RUNNING → FINISHED)
    EventStatusResponse finishEvent();

    // 세션의 최신 동기화 상태를 조회 (참가자 상태 조회 등에서 재사용)
    EventSessionStatus getSynchronizedStatus(Long sessionId);
}
