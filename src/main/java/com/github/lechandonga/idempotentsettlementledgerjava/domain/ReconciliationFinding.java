package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 单条核对发现。
 *
 * @param code      发现类型（如 ENTRY_HASH_MISMATCH、HASH_CHAIN_BROKEN、ACCOUNT_BALANCE_MISMATCH）
 * @param reference 涉及的分录号/账户号/交易号
 * @param detail    判定依据描述
 */
public record ReconciliationFinding(String code, String reference, String detail) {
}
