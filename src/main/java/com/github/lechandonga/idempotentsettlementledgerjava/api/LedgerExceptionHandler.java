package com.github.lechandonga.idempotentsettlementledgerjava.api;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把领域错误翻译成稳定的 HTTP 响应，错误码与 retryable 标记对外可见，
 * 调用方可据此区分“拒绝”与“可重试”。
 */
@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ErrorResponse> handleLedger(LedgerException ex) {
        ErrorCode code = ex.getCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(new ErrorResponse(code.name(), code.isRetryable(), ex.getMessage()));
    }

    public record ErrorResponse(String errorCode, boolean retryable, String message) {
    }
}
