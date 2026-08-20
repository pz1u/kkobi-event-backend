package org.kkobi.event.service;

import org.kkobi.event.dto.response.EventStatusResponse;

public interface EventSessionService {

    // 현재 행사 상태를 조회
    EventStatusResponse getEventStatus();
}
