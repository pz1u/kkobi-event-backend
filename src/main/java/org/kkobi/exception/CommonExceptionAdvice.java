package org.kkobi.exception;

import lombok.extern.log4j.Log4j2;
import org.kkobi.common.dto.ApiResponse;
import org.kkobi.event.exception.DuplicateNicknameException;
import org.kkobi.event.exception.EventAlreadyFinishedException;
import org.kkobi.event.exception.EventAlreadyStartedException;
import org.kkobi.event.exception.EventJoinNotAllowedException;
import org.kkobi.event.exception.EventNotStartedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.game.exception.EventNotFinishedException;
import org.kkobi.users.dto.response.MessageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;

@ControllerAdvice
@Log4j2
public class CommonExceptionAdvice {

    // DTO 필드 검증 실패 메시지를 JSON으로 반환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "요청 값을 확인해 주세요." : fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(new MessageResponse(message));
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 이메일 또는 닉네임 중복 오류를 JSON으로 반환
    @ExceptionHandler(DuplicateUserException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleDuplicateUser(DuplicateUserException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 동시에 들어온 가입 요청이 DB 고유 제약조건과 충돌한 경우 JSON으로 반환
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleDuplicateKey() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse("이미 사용 중인 이메일 또는 닉네임입니다."));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 행사 닉네임 중복 오류를 JSON으로 반환
    @ExceptionHandler(DuplicateNicknameException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleDuplicateNickname(DuplicateNicknameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // WAITING 상태가 아닌 행사에 신규 참가를 시도한 오류를 JSON으로 반환
    @ExceptionHandler(EventJoinNotAllowedException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleEventJoinNotAllowed(EventJoinNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 유효하지 않은 participantToken 오류를 JSON으로 반환
    @ExceptionHandler(InvalidParticipantTokenException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleInvalidParticipantToken(InvalidParticipantTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 이미 시작된 행사에 대한 중복 START 요청 오류를 JSON으로 반환
    @ExceptionHandler(EventAlreadyStartedException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleEventAlreadyStarted(EventAlreadyStartedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 아직 시작되지 않은 행사에 대한 FINISH 요청 오류를 JSON으로 반환
    @ExceptionHandler(EventNotStartedException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleEventNotStarted(EventNotStartedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 이미 종료된 행사에 대한 중복 FINISH 요청 오류를 JSON으로 반환
    @ExceptionHandler(EventAlreadyFinishedException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleEventAlreadyFinished(EventAlreadyFinishedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 아직 종료되지 않은 행사에 대한 결과 조회 요청 오류를 JSON으로 반환
    @ExceptionHandler(EventNotFinishedException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleEventNotFinished(EventNotFinishedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(ex.getMessage()));
    }

    // 비밀번호 등 비즈니스 규칙 검증 오류를 JSON으로 반환
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new MessageResponse(ex.getMessage()));
    }

    // @RequestParam·@PathVariable 타입 변환 실패를 JSON으로 반환
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        Object value = ex.getValue();
        return ResponseEntity.badRequest()
                .body(new MessageResponse(field + " 파라미터 값이 올바르지 않습니다: " + value));
    }

    // @ModelAttribute 바인딩 실패(예: enum 변환 실패)를 JSON으로 반환
    @ExceptionHandler(BindException.class)
    @ResponseBody
    public ResponseEntity<MessageResponse> handleBind(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message;
        if (fieldError == null) {
            message = "요청 값을 확인해 주세요.";
        } else {
            message = fieldError.getField() + " 파라미터 값이 올바르지 않습니다: "
                    + fieldError.getRejectedValue();
        }
        return ResponseEntity.badRequest().body(new MessageResponse(message));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> except(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handle404(NoHandlerFoundException ex, HttpServletRequest request) {
        log.error("404 Not Found: {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."));
    }
}
