package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 一笔不可变的双分录交易。
 *
 * @param id                 交易号
 * @param idempotencyKey     调用方幂等标识
 * @param fromAccountId      转出账户
 * @param toAccountId        转入账户
 * @param amount             金额（正整数，最小货币单位）
 * @param reference          业务备注
 * @param reversalOfId       非空表示该交易是对某笔原交易的冲正
 * @param reversedById       非空表示本交易已被该冲正交易所冲回
 * @param createdAtMillis    创建时间
 */
public record LedgerTransaction(String id, String idempotencyKey,
                                String fromAccountId, String toAccountId,
                                long amount, String reference,
                                String reversalOfId, String reversedById,
                                long createdAtMillis) {

    public boolean isReversal() {
        return reversalOfId != null;
    }

    public boolean isReversed() {
        return reversedById != null;
    }
}
