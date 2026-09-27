package com.github.lechandonga.idempotentsettlementledgerjava.error;

/**
 * 领域内所有可预期的业务失败统一用本异常表达，携带可区分的 {@link ErrorCode}。
 */
public class LedgerException extends RuntimeException {

    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LedgerException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
