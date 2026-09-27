package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/** 转账单据状态。 */
public enum TransferStatus {
    /** 正常入账。 */
    POSTED,
    /** 已被冲正，净发生额为零。 */
    REVERSED
}
