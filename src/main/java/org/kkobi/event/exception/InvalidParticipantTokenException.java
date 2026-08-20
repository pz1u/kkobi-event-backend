package org.kkobi.event.exception;

// participantToken이 없거나 유효하지 않을 때 발생하는 예외
public class InvalidParticipantTokenException extends RuntimeException {

    public InvalidParticipantTokenException(String message) {
        super(message);
    }
}
