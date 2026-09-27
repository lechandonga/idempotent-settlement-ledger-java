package com.github.lechandonga.idempotentsettlementledgerjava.error;

/** 记账业务异常，携带可被调用方区分处理的错误码。 */
public class LedgerException extends RuntimeException {

    private final ErrorCode errorCode;

    public LedgerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
