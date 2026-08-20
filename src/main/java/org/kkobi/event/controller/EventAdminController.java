package org.kkobi.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.dto.response.EventAdminParticipantListResponse;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.leaderboard.dto.response.EventAdminLeaderboardResponse;
import org.kkobi.event.leaderboard.service.EventLeaderboardService;
import org.kkobi.event.service.EventParticipantService;
import org.kkobi.event.service.EventSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/event")
@Tag(name = "행사 관리자", description = "행사 시작/종료 제어 및 운영 조회 API (X-Admin-Key 필요)")
public class EventAdminController {

    private final EventSessionService eventSessionService;
    private final EventParticipantService eventParticipantService;
    private final EventLeaderboardService eventLeaderboardService;

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

    @Operation(
            summary = "관리자 참가자 목록 조회",
            description = "현재 세션에 등록된 참가자 전체 목록을 조회한다. participantToken은 응답에 포함하지 않는다."
    )
    @GetMapping("/participants")
    public ResponseEntity<EventAdminParticipantListResponse> getParticipants() {
        return ResponseEntity.ok(eventParticipantService.getParticipantsForAdmin());
    }

    @Operation(
            summary = "관리자 최종 리더보드 조회",
            description = "게임 종료(FINISHED) 이후 실제 게임 참여자 전원의 최종 순위를 조회한다."
    )
    @GetMapping("/leaderboard")
    public ResponseEntity<EventAdminLeaderboardResponse> getLeaderboard() {
        return ResponseEntity.ok(eventLeaderboardService.getLeaderboardForAdmin());
    }
}
