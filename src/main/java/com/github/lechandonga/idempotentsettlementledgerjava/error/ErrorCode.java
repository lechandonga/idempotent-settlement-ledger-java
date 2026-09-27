package com.github.lechandonga.idempotentsettlementledgerjava.error;

/**
 * 业务错误码。httpStatus 为对外映射的 HTTP 状态码；retryable 表示调用方是否可以原样重试。
 */
public enum ErrorCode {

    /** 普通参数错误（金额非法、账户相同、字段缺失等），不可重试。 */
    PARAM_INVALID(400, false),
    /** 同一个幂等标识提交了内容不同的请求，不可重试，必须与 PARAM_INVALID 区分。 */
    IDEMPOTENCY_CONFLICT(409, false),
    /** 账户已存在。 */
    DUPLICATE_ACCOUNT(409, false),
    /** 账户不存在。 */
    ACCOUNT_NOT_FOUND(404, false),
    /** 余额不足，业务拒绝，不可重试。 */
    INSUFFICIENT_FUNDS(422, false),
    /** 并发争用导致本次未入账（拿锁超时），调用方可安全重试。 */
    RETRYABLE_CONTENTION(409, true),
    /** 流水/交易不存在。 */
    TRANSACTION_NOT_FOUND(404, false),
    /** 交易已被冲正，不能重复冲正。 */
    TRANSACTION_ALREADY_REVERSED(409, false);

    private final int httpStatus;
    private final boolean retryable;

    ErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
