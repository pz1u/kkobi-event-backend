package org.kkobi.event.exception;

// WAITING 상태가 아닌 행사에 신규 참가를 시도할 때 발생하는 예외
public class EventJoinNotAllowedException extends RuntimeException {

    public EventJoinNotAllowedException(String message) {
        super(message);
    }
}
