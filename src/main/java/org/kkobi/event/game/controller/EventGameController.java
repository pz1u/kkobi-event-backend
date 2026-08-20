package org.kkobi.event.game.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.game.dto.request.EventGameActionRequest;
import org.kkobi.event.game.dto.response.EventGameActionResponse;
import org.kkobi.event.game.dto.response.EventGameStatusResponse;
import org.kkobi.event.game.service.EventGameActionService;
import org.kkobi.event.game.service.EventGameStateService;
import org.kkobi.game.dto.GameStartRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/event/game")
@Tag(name = "행사 게임", description = "행사 참가자 성향 파악 게임 진행 API")
public class EventGameController {

    private final EventGameStateService eventGameStateService;
    private final EventGameActionService eventGameActionService;

    @Operation(
            summary = "행사 게임 상태 생성/조회",
            description = "참가자의 행사 게임 상태가 없으면 초기 자산을 생성하고, 있으면 기존 상태를 반환합니다."
    )
    @PostMapping
    public ResponseEntity<EventGameStatusResponse> ensureGameState(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken,
            @Valid @RequestBody GameStartRequest request
    ) {
        return ResponseEntity.ok(eventGameStateService.ensureGameState(participantToken, request));
    }

    @Operation(
            summary = "행사 게임 상태 조회",
            description = "서버 시간 기준 현재 Tick, 남은 시간, 자산 현황을 조회합니다. 폴링에 사용합니다."
    )
    @GetMapping
    public ResponseEntity<EventGameStatusResponse> getGameStatus(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken
    ) {
        return ResponseEntity.ok(eventGameStateService.getStatus(participantToken));
    }

    @Operation(
            summary = "행사 게임 행동 저장",
            description = "매수·매도·예금 해지 행동을 저장합니다. gameTick과 가격은 서버가 계산한 값을 사용합니다."
    )
    @PostMapping("/actions")
    public ResponseEntity<EventGameActionResponse> saveGameAction(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken,
            @Valid @RequestBody EventGameActionRequest request
    ) {
        return ResponseEntity.ok(eventGameActionService.saveAction(participantToken, request));
    }
}
