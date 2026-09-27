package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerConstants;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerTransaction;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.TransactionJournal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 账户开户与查询。
 *
 * <p>开户的初始余额同样以双分录入账：借 新账户 / 贷 系统开业科目
 * {@link LedgerConstants#OPENING_ACCOUNT_ID}。这样任何时刻全系统借贷相等，
 * 每个账户（含开业科目）的余额都能由流水完整重算，核对不需要任何特例。
 * 初始余额为 0 时不产生分录。
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AccountLocks locks;
    private final TransactionJournal journal;

    public AccountService(AccountRepository accounts, AccountLocks locks,
                          TransactionJournal journal) {
        this.accounts = accounts;
        this.locks = locks;
        this.journal = journal;
    }

    public LedgerAccount createAccount(String id, String name, long initialBalance) {
        if (id == null || id.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "账户号不能为空");
        }
        if (LedgerConstants.OPENING_ACCOUNT_ID.equals(id)) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "账户号 " + LedgerConstants.OPENING_ACCOUNT_ID + " 为系统保留科目");
        }
        if (name == null || name.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "账户名称不能为空");
        }
        if (initialBalance < 0) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "初始余额不能为负: " + initialBalance);
        }

        return locks.withLock(id, () -> {
            LedgerAccount created = accounts.create(id, name, initialBalance);
            if (initialBalance > 0) {
                String txId = "OPN-" + UUID.randomUUID();
                LedgerTransaction tx = new LedgerTransaction(txId, "OPENING-" + id,
                        LedgerConstants.OPENING_ACCOUNT_ID, id, initialBalance,
                        "开户入金 " + id, null, null, System.currentTimeMillis());
                // 借 新账户（资产增加）、贷 开业科目，金额相等，全系统配平。
                journal.post(tx, id, LedgerConstants.OPENING_ACCOUNT_ID, initialBalance);
                accounts.applyDelta(LedgerConstants.OPENING_ACCOUNT_ID, -initialBalance);
            }
            return created;
        });
    }

    public LedgerAccount getAccount(String id) {
        return accounts.findById(id)
                .orElseThrow(() -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + id));
    }

    /** 仅返回客户账户，系统开业科目不对外展示。 */
    public List<LedgerAccount> listAccounts() {
        return accounts.findAll().stream()
                .filter(a -> !LedgerConstants.OPENING_ACCOUNT_ID.equals(a.id()))
                .toList();
    }
}
