package org.kkobi.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.kkobi.event.dto.request.EventParticipantJoinRequest;
import org.kkobi.event.dto.response.EventParticipantJoinResponse;
import org.kkobi.event.dto.response.EventParticipantMeResponse;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.service.EventParticipantService;
import org.kkobi.event.service.EventSessionService;
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
@RequestMapping("/api/event")
@Tag(name = "행사", description = "행사 세션 및 참가자 API")
public class EventController {

    private final EventSessionService eventSessionService;
    private final EventParticipantService eventParticipantService;

    @Operation(
            summary = "행사 참가",
            description = "닉네임으로 행사에 참가하고 participantToken을 발급받습니다."
    )
    @PostMapping("/participants")
    public ResponseEntity<EventParticipantJoinResponse> join(
            @Valid @RequestBody EventParticipantJoinRequest request
    ) {
        return ResponseEntity.ok(eventParticipantService.join(request));
    }

    @Operation(
            summary = "행사 상태 조회",
            description = "행사 상태, 서버 시간, 참가자 수를 조회합니다. 폴링에 사용합니다."
    )
    @GetMapping("/status")
    public ResponseEntity<EventStatusResponse> getStatus() {
        return ResponseEntity.ok(eventSessionService.getEventStatus());
    }

    @Operation(
            summary = "참가자 정보 복구",
            description = "participantToken으로 기존 참가자 정보를 조회합니다."
    )
    @GetMapping("/me")
    public ResponseEntity<EventParticipantMeResponse> getMe(
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken
    ) {
        return ResponseEntity.ok(eventParticipantService.getMe(participantToken));
    }
}
