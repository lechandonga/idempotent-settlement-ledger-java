package com.github.lechandonga.idempotentsettlementledgerjava.repository;

/**
 * 幂等执行结果。
 *
 * @param transactionId 交易号（首次执行与重放返回同一个值）
 * @param replayed      true 表示命中已有结果、本次未发生真实入账
 */
public record IdempotentExecution(String transactionId, boolean replayed) {
}
