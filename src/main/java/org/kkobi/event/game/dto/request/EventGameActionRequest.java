package org.kkobi.event.game.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
public class EventGameActionRequest {

    @NotBlank
    private String actionType;

    @NotBlank
    private String assetType;

    @Min(1)
    private Long actionQuantity;

    // 예금 해지 호환용. 주식 매수·매도 금액은 actionQuantity와 서버 현재가로 계산한다.
    @Min(1)
    private Long actionAmount;

    // 클라이언트가 보내더라도 서버가 계산한 tick으로 대체되며 신뢰하지 않는다
    private Integer gameTick;
}
