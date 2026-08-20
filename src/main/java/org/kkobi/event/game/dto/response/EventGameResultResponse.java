package org.kkobi.event.game.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.kkobi.persona.dto.PersonaResponseDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class EventGameResultResponse {

    private final Long participantId;
    private final String nickname;

    private final long initialAsset;
    private final long finalAsset;
    private final BigDecimal returnRate;

    private final BigDecimal rtScore;
    private final BigDecimal lhScore;
    private final BigDecimal rpScore;

    private final Long personaId;
    private final String personaName;
    private final PersonaResponseDto persona;

    private final int finalTick;
    private final long finalPrice;

    private final LocalDateTime finishedAt;
}
