package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 核对结论。
 */
public enum ReconciliationStatus {
    /** 账实相符：哈希链完整、借贷守恒、账户余额与流水一致。 */
    OK,
    /** 账目不平：流水守恒或余额对不上，但未发现记录被直接篡改。 */
    UNBALANCED,
    /** 记录被外部改动：哈希校验失败或哈希链断裂。 */
    TAMPERED
}
