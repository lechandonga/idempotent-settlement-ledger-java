package com.github.lechandonga.idempotentsettlementledgerjava.tests.support;

import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryAccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryAccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryIdempotencyRegistry;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryTransactionJournal;
import com.github.lechandonga.idempotentsettlementledgerjava.service.AccountService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerProperties;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.TransferService;

/**
 * 纯手工装配的内存账台：测试不依赖 Spring 与外部服务，锁超时可按用例调大/调小。
 */
public final class TestLedger {

    public final InMemoryAccountRepository accounts;
    public final InMemoryTransactionJournal journal;
    public final InMemoryAccountLocks locks;
    public final InMemoryIdempotencyRegistry idempotency;
    public final AccountService accountService;
    public final TransferService transferService;
    public final ReconciliationService reconciliationService;

    private TestLedger(long lockTimeoutMillis) {
        this.accounts = new InMemoryAccountRepository();
        this.journal = new InMemoryTransactionJournal();
        this.locks = new InMemoryAccountLocks(lockTimeoutMillis);
        this.idempotency = new InMemoryIdempotencyRegistry();
        LedgerProperties properties = new LedgerProperties();
        properties.setLockTimeoutMillis(lockTimeoutMillis);
        this.accountService = new AccountService(accounts, locks, journal);
        this.transferService = new TransferService(accounts, journal, locks, idempotency, properties);
        this.reconciliationService = new ReconciliationService(accounts, journal, locks);
    }

    public static TestLedger create(long lockTimeoutMillis) {
        return new TestLedger(lockTimeoutMillis);
    }

    public TestLedger seed(String id, String name, long balance) {
        accountService.createAccount(id, name, balance);
        return this;
    }

    public long balanceOf(String id) {
        return accounts.findById(id).orElseThrow().balance();
    }

    /** 全部账户（含系统开业科目）之和：全系统借贷净额，恒为 0。 */
    public long totalBalance() {
        return accounts.findAll().stream().mapToLong(a -> a.balance()).sum();
    }

    /** 客户资金总额（不含系统开业科目），转账/冲正前后必须不变。 */
    public long customerTotalBalance() {
        return accounts.findAll().stream()
                .filter(a -> !com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerConstants.OPENING_ACCOUNT_ID.equals(a.id()))
                .mapToLong(a -> a.balance()).sum();
    }
}
