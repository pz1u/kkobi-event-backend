package org.kkobi.event.game.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.game.dto.response.EventGameResultResponse;
import org.kkobi.event.game.service.EventGameResultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/event/result")
@Tag(name = "행사 게임 결과", description = "행사 참가자 최종 결과(자산/수익률/성향) 조회 API")
public class EventGameResultController {

    private final EventGameResultService eventGameResultService;

    @Operation(
            summary = "행사 게임 결과 조회",
            description = "게임 종료 후 참가자의 최종 자산/수익률/성향 결과를 조회합니다. "
                    + "결과가 없으면 최초 1회 계산해 저장하고, 이후에는 저장된 결과를 그대로 반환합니다."
    )
    @GetMapping
    public ResponseEntity<EventGameResultResponse> getResult(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken
    ) {
        return ResponseEntity.ok(eventGameResultService.getOrCreateResult(participantToken));
    }
}
