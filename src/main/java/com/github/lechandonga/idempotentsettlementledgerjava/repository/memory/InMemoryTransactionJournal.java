package com.github.lechandonga.idempotentsettlementledgerjava.repository.memory;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerEntry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerTransaction;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.TransactionJournal;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerHasher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存流水账。
 *
 * <p>分录只追加、永不变更（冲正也以追加反向交易表达）。每条分录携带：
 * <ul>
 *   <li>对自身业务字段计算的 SHA-256（防外部改动单条记录）；</li>
 *   <li>上一条分录的哈希，全局形成一条哈希链（防删除/重排/断链）。</li>
 * </ul>
 * 约定 {@code post}/{@code markReversed} 由服务层在账户锁保护下调用；
 * 追加本身用 synchronized 保证序号与哈希链的原子推进。
 */
public class InMemoryTransactionJournal implements TransactionJournal {

    /** 创世前驱哈希。 */
    public static final String GENESIS = "0".repeat(64);

    private final List<LedgerTransaction> transactions = new ArrayList<>();
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public InMemoryTransactionJournal() {
    }

    /** 对一条分录的业务字段计算内容哈希（不含 hash 字段本身）。 */
    public static String entryContentHash(String id, String transactionId, String accountId,
                                          long amount, boolean debit, long seq, String prevHash) {
        return LedgerHasher.sha256Hex(
                id, transactionId, accountId,
                Long.toString(amount), Boolean.toString(debit),
                Long.toString(seq), nullToEmpty(prevHash));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public synchronized Posted post(LedgerTransaction transaction, String debitAccountId,
                                    String creditAccountId, long amount) {
        long seqDebit = sequence.incrementAndGet();
        String prevDebit = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash();
        LedgerEntry debit = new LedgerEntry(
                UUID.randomUUID().toString(), transaction.id(), debitAccountId,
                amount, true, seqDebit, prevDebit,
                entryContentHash(/*filled below*/ "", "", "", 0, false, 0, ""));
        debit = withHash(debit);

        long seqCredit = sequence.incrementAndGet();
        String prevCredit = debit.hash();
        LedgerEntry credit = new LedgerEntry(
                UUID.randomUUID().toString(), transaction.id(), creditAccountId,
                amount, false, seqCredit, prevCredit,
                entryContentHash("", "", "", 0, false, 0, ""));
        credit = withHash(credit);

        transactions.add(transaction);
        entries.add(debit);
        entries.add(credit);
        return new Posted(transaction, debit, credit);
    }

    private LedgerEntry withHash(LedgerEntry e) {
        String hash = entryContentHash(e.id(), e.transactionId(), e.accountId(),
                e.amount(), e.debit(), e.seq(), e.prevHash());
        return new LedgerEntry(e.id(), e.transactionId(), e.accountId(),
                e.amount(), e.debit(), e.seq(), e.prevHash(), hash);
    }

    @Override
    public synchronized Optional<LedgerTransaction> findTransactionById(String id) {
        return transactions.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public synchronized List<LedgerTransaction> findAllTransactions() {
        return transactions.stream()
                .sorted(Comparator.comparingLong(LedgerTransaction::createdAtMillis)
                        .thenComparing(LedgerTransaction::id))
                .toList();
    }

    @Override
    public synchronized List<LedgerEntry> findAllEntries() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized void markReversed(String originalTransactionId, String reversalTransactionId) {
        for (int i = 0; i < transactions.size(); i++) {
            LedgerTransaction t = transactions.get(i);
            if (t.id().equals(originalTransactionId)) {
                transactions.set(i, new LedgerTransaction(t.id(), t.idempotencyKey(),
                        t.fromAccountId(), t.toAccountId(), t.amount(), t.reference(),
                        t.reversalOfId(), reversalTransactionId, t.createdAtMillis()));
                return;
            }
        }
        throw new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                "交易不存在: " + originalTransactionId);
    }

    @Override
    public synchronized LedgerEntry tamperEntryAmountForVerification(String entryId, long newAmount) {
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry e = entries.get(i);
            if (e.id().equals(entryId)) {
                // 故意只改金额、不重算哈希，模拟外部直接篡改落库记录。
                LedgerEntry tampered = new LedgerEntry(e.id(), e.transactionId(), e.accountId(),
                        newAmount, e.debit(), e.seq(), e.prevHash(), e.hash());
                entries.set(i, tampered);
                return tampered;
            }
        }
        throw new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                "分录不存在: " + entryId);
    }
}
