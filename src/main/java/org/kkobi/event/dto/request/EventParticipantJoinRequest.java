package org.kkobi.event.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class EventParticipantJoinRequest {

    @NotBlank(message = "닉네임은 필수 입력 값입니다.")
    private String nickname;
}
