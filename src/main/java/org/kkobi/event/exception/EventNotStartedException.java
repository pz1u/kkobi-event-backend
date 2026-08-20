package org.kkobi.event.exception;

// WAITING 상태인 행사에 관리자 FINISH를 시도할 때 발생하는 예외
public class EventNotStartedException extends RuntimeException {

    public EventNotStartedException(String message) {
        super(message);
    }
}
