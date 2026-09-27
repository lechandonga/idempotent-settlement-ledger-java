package com.github.lechandonga.idempotentsettlementledgerjava.repository;

/**
 * 幂等槽位的已存结果。
 *
 * @param transactionId 成功时为交易号；业务失败时可能为 null
 * @param failureCode   业务失败的错误码名；成功时为 null
 * @param failureMessage 业务失败的描述
 */
public record IdempotencyOutcome(String transactionId, String failureCode, String failureMessage) {

    public static IdempotencyOutcome success(String transactionId) {
        return new IdempotencyOutcome(transactionId, null, null);
    }

    public static IdempotencyOutcome failure(String code, String message) {
        return new IdempotencyOutcome(null, code, message);
    }

    public boolean isSuccess() {
        return failureCode == null;
    }
}
