package com.github.lechandonga.idempotentsettlementledgerjava.repo;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.Account;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 账户内存仓库。账户余额的实际读写在账户锁保护下进行（见 AccountLocks）。 */
@Repository
public class AccountRepository {

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

    /** 开户；重复开户视为参数错误。 */
    public Account create(String accountNo, BigDecimal initialBalance) {
        Account account = new Account();
        account.setAccountNo(accountNo);
        account.setInitialBalance(initialBalance);
        account.setBalance(initialBalance);
        account.setVersion(0L);
        if (accounts.putIfAbsent(accountNo, account) != null) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "account already exists: " + accountNo);
        }
        return account;
    }

    public Optional<Account> find(String accountNo) {
        return Optional.ofNullable(accounts.get(accountNo));
    }

    /** 取账户，不存在抛 ACCOUNT_NOT_FOUND。 */
    public Account require(String accountNo) {
        return find(accountNo)
                .orElseThrow(() -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + accountNo));
    }

    public List<Account> findAll() {
        return accounts.values().stream()
                .sorted(java.util.Comparator.comparing(Account::getAccountNo))
                .toList();
    }
}
