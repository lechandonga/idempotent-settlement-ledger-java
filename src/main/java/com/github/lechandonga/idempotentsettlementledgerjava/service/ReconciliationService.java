package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerEntry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationFinding;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationStatus;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.TransactionJournal;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryTransactionJournal;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水核对。
 *
 * <p>四类判定，结论优先级 TAMPERED &gt; UNBALANCED &gt; OK：
 * <ol>
 *   <li><b>单条完整性</b>：重算每条分录的 SHA-256，与存储值不符 → ENTRY_HASH_MISMATCH；</li>
 *   <li><b>哈希链</b>：按 seq 排序后序号必须连续、prevHash 必须指向上一条 hash，
 *       首条指向创世值 → HASH_CHAIN_BROKEN（可发现删除/重排/外部改动）；</li>
 *   <li><b>借贷守恒</b>：全部分录借方合计必须等于贷方合计 → ENTRIES_NOT_BALANCED；</li>
 *   <li><b>账实相符</b>：由流水按“借增贷减”重算每个账户余额，必须与账户当前余额一致
 *       → ACCOUNT_BALANCE_MISMATCH（外部直接改余额会在此暴露）。</li>
 * </ol>
 * 核对在全部账户锁内执行，保证快照时刻余额与流水一致、没有在途修改。
 */
@Service
public class ReconciliationService {

    private final AccountRepository accounts;
    private final TransactionJournal journal;
    private final AccountLocks locks;

    public ReconciliationService(AccountRepository accounts, TransactionJournal journal,
                                 AccountLocks locks) {
        this.accounts = accounts;
        this.journal = journal;
        this.locks = locks;
    }

    public ReconciliationReport reconcile() {
        List<String> accountIds = accounts.findAll().stream()
                .map(LedgerAccount::id)
                .sorted()
                .toList();
        return locks.withAllLocks(accountIds, this::buildReport);
    }

    private ReconciliationReport buildReport() {
        List<ReconciliationFinding> findings = new ArrayList<>();
        boolean tampered = false;

        List<LedgerEntry> entries = journal.findAllEntries();
        List<LedgerAccount> accountsNow = accounts.findAll();

        String expectedPrev = InMemoryTransactionJournal.GENESIS;
        long expectedSeq = 1;
        BigInteger totalDebit = BigInteger.ZERO;
        BigInteger totalCredit = BigInteger.ZERO;
        Map<String, BigInteger> netByAccount = new LinkedHashMap<>();

        List<LedgerEntry> ordered = entries.stream()
                .sorted(Comparator.comparingLong(LedgerEntry::seq))
                .toList();

        // 1) 单条内容哈希；2) 哈希链连续性
        for (LedgerEntry e : ordered) {
            String recomputed = InMemoryTransactionJournal.entryContentHash(
                    e.id(), e.transactionId(), e.accountId(),
                    e.amount(), e.debit(), e.seq(), e.prevHash());
            if (!recomputed.equals(e.hash())) {
                tampered = true;
                findings.add(new ReconciliationFinding("ENTRY_HASH_MISMATCH", e.id(),
                        "分录内容被改动：存储哈希=" + e.hash() + "，重算哈希=" + recomputed));
            }
            if (e.seq() != expectedSeq) {
                tampered = true;
                findings.add(new ReconciliationFinding("HASH_CHAIN_BROKEN", e.id(),
                        "分录序号不连续：期望=" + expectedSeq + "，实际=" + e.seq()));
            }
            if (!expectedPrev.equals(e.prevHash())) {
                tampered = true;
                findings.add(new ReconciliationFinding("HASH_CHAIN_BROKEN", e.id(),
                        "前驱哈希不匹配：期望=" + expectedPrev + "，实际=" + e.prevHash()));
            }

            if (e.debit()) {
                totalDebit = totalDebit.add(BigInteger.valueOf(e.amount()));
            } else {
                totalCredit = totalCredit.add(BigInteger.valueOf(e.amount()));
            }
            netByAccount.merge(e.accountId(), e.signedAmount(), BigInteger::add);
            expectedPrev = e.hash();
            expectedSeq++;
        }

        // 3) 借贷守恒
        if (totalDebit.compareTo(totalCredit) != 0) {
            findings.add(new ReconciliationFinding("ENTRIES_NOT_BALANCED", "*",
                    "借方合计=" + totalDebit + " 不等于贷方合计=" + totalCredit));
        }

        // 4) 账实相符：流水重算余额 vs 账户当前余额
        Map<String, Long> balancesFromLedger = new LinkedHashMap<>();
        boolean balanceMismatch = false;
        for (LedgerAccount a : accountsNow) {
            long expected = netByAccount.getOrDefault(a.id(), BigInteger.ZERO).longValueExact();
            balancesFromLedger.put(a.id(), expected);
            if (expected != a.balance()) {
                balanceMismatch = true;
                findings.add(new ReconciliationFinding("ACCOUNT_BALANCE_MISMATCH", a.id(),
                        "账户当前余额=" + a.balance() + " 与流水重算余额=" + expected + " 不一致"));
            }
        }

        long totalAccounts = accountsNow.stream().mapToLong(LedgerAccount::balance).sum();
        long totalNet = netByAccount.values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add).longValueExact();

        boolean unbalanced = totalDebit.compareTo(totalCredit) != 0 || balanceMismatch;
        ReconciliationStatus status = tampered ? ReconciliationStatus.TAMPERED
                : (unbalanced ? ReconciliationStatus.UNBALANCED : ReconciliationStatus.OK);

        return new ReconciliationReport(status, totalAccounts, totalNet,
                balancesFromLedger, findings);
    }
}
