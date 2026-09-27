package com.github.lechandonga.idempotentsettlementledgerjava.web;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把业务错误码映射为可区分的 HTTP 响应。调用方主要依据 body.errorCode 判断处置方式：
 * CONCURRENT_CONFLICT 表示应重试；INSUFFICIENT_FUNDS、PARAM 类错误、IDEMPOTENCY_CONFLICT 表示不应重试。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, Object>> handleLedger(LedgerException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case ACCOUNT_NOT_FOUND, TRANSFER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // 并发争用与幂等冲突同为 409，但 errorCode 不同：前者重试，后者换键
            case CONCURRENT_CONFLICT, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        log.info("ledger error mapped: errorCode={}, httpStatus={}, message={}",
                ex.getErrorCode(), status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(body(ex.getErrorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.PARAM_INVALID.name(), "malformed JSON request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", ex.getMessage()));
    }

    private static Map<String, Object> body(String errorCode, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("errorCode", errorCode);
        map.put("message", message);
        return map;
    }
}
