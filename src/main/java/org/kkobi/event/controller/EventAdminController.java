package org.kkobi.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.service.EventSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/event")
@Tag(name = "행사 관리자", description = "행사 시작/종료 제어 API")
public class EventAdminController {

    private final EventSessionService eventSessionService;

    @Operation(
            summary = "행사 시작",
            description = "WAITING 상태의 행사를 시작한다. 서버 시간 기준 5초 카운트다운 후 durationSeconds만큼 진행된다."
    )
    @PostMapping("/start")
    public ResponseEntity<EventStatusResponse> start() {
        return ResponseEntity.ok(eventSessionService.startEvent());
    }

    @Operation(
            summary = "행사 강제 종료",
            description = "COUNTDOWN 또는 RUNNING 상태의 행사를 즉시 종료한다."
    )
    @PostMapping("/finish")
    public ResponseEntity<EventStatusResponse> finish() {
        return ResponseEntity.ok(eventSessionService.finishEvent());
    }
}
