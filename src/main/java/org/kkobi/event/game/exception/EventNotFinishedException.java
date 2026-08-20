package org.kkobi.event.game.exception;

// RUNNING 상태(아직 종료되지 않은 행사)에서 결과 조회를 시도할 때 발생하는 예외
public class EventNotFinishedException extends RuntimeException {

    public EventNotFinishedException(String message) {
        super(message);
    }
}
