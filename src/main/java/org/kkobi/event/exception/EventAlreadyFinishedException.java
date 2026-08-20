package org.kkobi.event.exception;

// 이미 FINISHED된 행사에 관리자 FINISH를 다시 시도할 때 발생하는 예외
public class EventAlreadyFinishedException extends RuntimeException {

    public EventAlreadyFinishedException(String message) {
        super(message);
    }
}
