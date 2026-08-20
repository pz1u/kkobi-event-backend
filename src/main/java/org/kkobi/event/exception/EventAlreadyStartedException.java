package org.kkobi.event.exception;

// WAITING이 아닌 행사에 관리자 START를 시도할 때 발생하는 예외
public class EventAlreadyStartedException extends RuntimeException {

    public EventAlreadyStartedException(String message) {
        super(message);
    }
}
