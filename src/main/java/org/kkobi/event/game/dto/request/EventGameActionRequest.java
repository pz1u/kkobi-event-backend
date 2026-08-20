package org.kkobi.event.game.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class EventGameActionRequest {

    @NotBlank
    private String actionType;

    @NotBlank
    private String assetType;

    @NotNull
    @Min(0)
    private Long actionAmount;

    // 클라이언트가 보내더라도 서버가 계산한 tick으로 대체되며 신뢰하지 않는다
    private Integer gameTick;
}
