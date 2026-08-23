package org.kkobi.event.game.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 행사 게임의 과거 금액 기반 로그 수량 재구성과 매도 원금 차감 정책.
// 신규 매매는 정수 actionQuantity를 그대로 반영하고 서버가 amount = quantity * price로 저장하므로,
// 특징 추출기가 이 정책으로 로그를 다시 나누어도 동일한 정수 수량이 복원된다.
// scale=8·HALF_UP은 기존 EventGameActionService 구현 값을 그대로 이관한 것으로,
// 이 값을 바꾸면 저장된 event_game_states.stock_quantity와 불일치가 발생한다.
public final class StockQuantityPolicy {

    // 과거 금액 기반 거래 로그 수량의 소수점 자릿수
    public static final int QUANTITY_SCALE = 8;

    // 매도 원금은 원 단위 정수이므로 반올림 자릿수 0
    private static final int PRINCIPAL_SCALE = 0;

    // 수량 정합성 허용치 (왕복 완결·전량 매도 판정, 재구성 대조 공용).
    // 신규 로그는 서버가 amount = N×price로 저장하므로 재구성 수량은 정확히 정수다.
    // 즉 신규 흐름의 전량 매도 잔여는 정확히 0이며,
    // 이 허용치가 흡수하는 것은 scale=8 HALF_UP 나눗셈 반올림 먼지(연산당 최대 5e-9)뿐이다.
    // 1주(시나리오 최저가 17,700원)보다 7자릿수 작으므로 진짜 미청산 포지션을 완결로 오판하지 않는다.
    public static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.000001");

    private StockQuantityPolicy() {
    }

    // 저장된 금액을 당시 가격으로 나눠 로그의 거래 수량을 재구성한다.
    public static BigDecimal calculateQuantity(long actionAmount, long currentPrice) {
        return BigDecimal.valueOf(actionAmount)
                .divide(BigDecimal.valueOf(currentPrice), QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    // 평단가(=매도 직전 stock_principal/stock_quantity) 방식으로
    // 매도 수량 비율만큼 매입 원금을 함께 차감한다.
    // 보유 수량이 0인 매도(강건성 테스트의 제거 변형 등 비정상 시퀀스)는
    // 원금 차감 없이 무시한다 — 실제 서버 흐름에서는 검증으로 차단되는 케이스다.
    public static long calculateProportionalPrincipal(
            long stockPrincipalBefore,
            BigDecimal soldQuantity,
            BigDecimal stockQuantityBefore) {
        if (stockQuantityBefore == null || stockQuantityBefore.signum() == 0) {
            return 0L;
        }
        return BigDecimal.valueOf(stockPrincipalBefore)
                .multiply(soldQuantity)
                .divide(stockQuantityBefore, PRINCIPAL_SCALE, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
