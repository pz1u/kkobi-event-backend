package org.kkobi.event.game.calculator;

import java.math.BigDecimal;

// 특징 추출 결과 + 잔고 정합성 검증용 최종 수량·원금(로그 재구성값이 유일한 원천).
public record EventExtractionResult(
        EventSessionFeatures features,
        BigDecimal finalQuantity,
        long finalPrincipal
) {
}
