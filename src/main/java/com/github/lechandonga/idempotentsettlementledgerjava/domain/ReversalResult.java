package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 冲正结果。
 *
 * @param reversalTransactionId 冲正交易号
 * @param originalTransactionId 被冲正的原交易号
 * @param replayed              true 表示命中冲正幂等、未发生真实入账
 */
public record ReversalResult(String reversalTransactionId,
                             String originalTransactionId, boolean replayed) {
}
