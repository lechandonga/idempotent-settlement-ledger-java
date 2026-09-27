package com.github.lechandonga.idempotentsettlementledgerjava.repository;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerEntry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerTransaction;

import java.util.List;
import java.util.Optional;

/**
 * 流水账：交易与分录只追加、不可变；冲正以追加反向交易的方式表达。
 */
public interface TransactionJournal {

    /**
     * 追加一笔交易及其两条分录。实现负责分配全局序号并计算哈希链。
     */
    Posted post(LedgerTransaction transaction, String debitAccountId, String creditAccountId, long amount);

    Optional<LedgerTransaction> findTransactionById(String id);

    List<LedgerTransaction> findAllTransactions();

    List<LedgerEntry> findAllEntries();

    /** 在流水锁内标记原交易被某冲正交易冲回。 */
    void markReversed(String originalTransactionId, String reversalTransactionId);

    /** 仅供测试：直接改写一条分录的金额，模拟外部篡改。 */
    LedgerEntry tamperEntryAmountForVerification(String entryId, long newAmount);

    /** 一次追加结果。 */
    record Posted(LedgerTransaction transaction, LedgerEntry debitEntry, LedgerEntry creditEntry) {
    }
}
