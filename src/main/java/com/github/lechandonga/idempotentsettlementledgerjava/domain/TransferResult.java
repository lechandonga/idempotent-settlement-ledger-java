package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 转账结果。重复提交同一幂等标识时始终返回等价结果。
 *
 * @param transactionId 交易号
 * @param replayed      true 表示本次调用命中幂等、未发生真实入账
 */
public record TransferResult(String transactionId, boolean replayed) {
}
