package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error is an RFC 9457 problem detail with a stable {@code code} property. */
@RestControllerAdvice
class ProblemHandler {

    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledger(LedgerException e) {
        return problem(status(e.code()), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request body is invalid");
        p.setProperty("errors", fields);
        return p;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if (TransferController.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getHeaderName())) {
            return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
                    "requests that move money need an Idempotency-Key header");
        }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "missing header " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request body is not valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "invalid value for " + e.getName());
    }

    static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case ACCOUNT_NOT_FOUND, TRANSFER_NOT_FOUND, HOLD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_REUSED, USERNAME_TAKEN, HOLD_NOT_ACTIVE, HOLD_EXPIRED -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_INVALID, USERNAME_INVALID, PASSWORD_INVALID -> HttpStatus.BAD_REQUEST;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ACCOUNT_INACTIVE, INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, CURRENCY_NOT_SUPPORTED, SAME_ACCOUNT,
                    INVALID_AMOUNT, BALANCE_OVERFLOW, HOLD_EXPIRY_INVALID, CAPTURE_EXCEEDS_HOLD ->
                    HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return p;
    }
}
