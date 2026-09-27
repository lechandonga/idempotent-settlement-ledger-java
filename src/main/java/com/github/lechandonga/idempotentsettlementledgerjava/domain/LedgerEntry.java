package com.github.lechandonga.idempotentsettlementledgerjava.domain;

import java.math.BigInteger;

/**
 * 单条分录流水。一笔转账产生两条方向相反的分录，金额相等。
 *
 * @param id            分录号
 * @param transactionId 所属交易号
 * @param accountId     账户号
 * @param amount        金额（正整数，最小货币单位）
 * @param debit         true=借方，false=贷方
 * @param seq           全局单调递增序号（哈希链用）
 * @param prevHash      上一条分录的哈希，构成哈希链
 * @param hash          本条分录的完整性校验哈希
 */
public record LedgerEntry(String id, String transactionId, String accountId,
                          long amount, boolean debit, long seq,
                          String prevHash, String hash) {

    /** 带符号金额：借为正、贷为负。 */
    public BigInteger signedAmount() {
        BigInteger v = BigInteger.valueOf(amount);
        return debit ? v : v.negate();
    }
}
