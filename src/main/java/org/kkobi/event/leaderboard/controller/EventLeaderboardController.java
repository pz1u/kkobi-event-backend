package org.kkobi.event.leaderboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.leaderboard.dto.response.EventLeaderboardResponse;
import org.kkobi.event.leaderboard.service.EventLeaderboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/event/leaderboard")
@Tag(name = "행사 리더보드", description = "행사 최종 리더보드(공동순위 포함) 조회 API")
public class EventLeaderboardController {

    private final EventLeaderboardService eventLeaderboardService;

    @Operation(
            summary = "행사 최종 리더보드 조회",
            description = "게임 종료(FINISHED) 이후 실제 게임에 참여한 참가자 전원의 최종 순위를 조회합니다. "
                    + "결과 화면을 조회하지 않은 참가자의 결과도 서버에서 자동으로 확정한 뒤 순위에 포함합니다."
    )
    @GetMapping
    public ResponseEntity<EventLeaderboardResponse> getLeaderboard(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken
    ) {
        return ResponseEntity.ok(eventLeaderboardService.getLeaderboard(participantToken));
    }
}
