package com.placeholder.global.exception;

import com.placeholder.global.exception.custom.CouponAlreadyRedeemedByUserException;
import com.placeholder.global.exception.custom.CouponExhaustedException;
import com.placeholder.global.exception.custom.CouponNotFoundException;
import com.placeholder.global.exception.custom.DuplicateEmailException;
import com.placeholder.global.exception.custom.DuplicateSeatLabelException;
import com.placeholder.global.exception.custom.EventNotFoundException;
import com.placeholder.global.exception.custom.InsufficientPointException;
import com.placeholder.global.exception.custom.InvalidCredentialsException;
import com.placeholder.global.exception.custom.InvalidUserRoleException;
import com.placeholder.global.exception.custom.QueueAdmissionRequiredException;
import com.placeholder.global.exception.custom.ReservationNotFoundException;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.global.exception.custom.SeatNotFoundException;
import com.placeholder.global.exception.custom.SeatNotHeldByUserException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Validation 실패 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {

        String errorMessage = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));

        ErrorResponse response = ErrorResponse.builder()
                .code("VALIDATION_FAILED")
                .message(errorMessage)
                .timestamp(LocalDateTime.now())
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 커스텀 예외 처리 - 리소스 없음
     */
    @ExceptionHandler({EventNotFoundException.class, UserNotFoundException.class, SeatNotFoundException.class,
            CouponNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFoundException(RuntimeException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("RESOURCE_NOT_FOUND")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler({DuplicateSeatLabelException.class, InvalidUserRoleException.class})
    public ResponseEntity<ErrorResponse> handleBusinessException(RuntimeException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("BUSINESS_RULE_VIOLATION")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("ACCESS_DENIED")
                .message("접근 권한이 없습니다")
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmailException(DuplicateEmailException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("DUPLICATE_EMAIL")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotAvailableException(SeatNotAvailableException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("SEAT_NOT_AVAILABLE")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(SeatNotHeldByUserException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotHeldByUserException(SeatNotHeldByUserException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("SEAT_NOT_HELD_BY_USER")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler({CouponAlreadyRedeemedByUserException.class, CouponExhaustedException.class})
    public ResponseEntity<ErrorResponse> handleCouponRedeemConflict(RuntimeException ex) {
        String code = (ex instanceof CouponExhaustedException)
                ? "COUPON_EXHAUSTED" : "COUPON_ALREADY_REDEEMED";
        ErrorResponse response = ErrorResponse.builder()
                .code(code)
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InsufficientPointException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientPointException(InsufficientPointException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("INSUFFICIENT_POINT")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFoundException(ReservationNotFoundException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("RESOURCE_NOT_FOUND")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(InvalidCredentialsException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("INVALID_CREDENTIALS")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * 대기열 입장 토큰 없이 hold 시도 (ADR-013). 트래픽 셰이핑 거절이므로 429로 응답해
     * 클라이언트가 대기열 진입 후 재시도하도록 유도한다.
     */
    @ExceptionHandler(QueueAdmissionRequiredException.class)
    public ResponseEntity<ErrorResponse> handleQueueAdmissionRequired(QueueAdmissionRequiredException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .code("QUEUE_ADMISSION_REQUIRED")
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    /**
     * 예상치 못한 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);

        ErrorResponse response = ErrorResponse.builder()
                .code("INTERNAL_SERVER_ERROR")
                .message("서버 내부 오류가 발생했습니다")
                .timestamp(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
