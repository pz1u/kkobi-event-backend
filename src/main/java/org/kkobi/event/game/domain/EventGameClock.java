package org.kkobi.event.game.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 서버 시간과 EventSession/시나리오를 기준으로 계산된 현재 Tick 정보
@Getter
@AllArgsConstructor
public class EventGameClock {

    private final int currentTick;
    private final int totalTickCount;
    private final long remainingSeconds;
    private final boolean actionAllowed;
}
