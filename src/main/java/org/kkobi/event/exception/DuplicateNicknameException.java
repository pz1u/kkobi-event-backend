package org.kkobi.event.exception;

// 같은 행사 세션 내 닉네임이 이미 사용 중일 때 발생하는 예외
public class DuplicateNicknameException extends RuntimeException {

    public DuplicateNicknameException(String message) {
        super(message);
    }
}
