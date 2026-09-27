package com.github.lechandonga.idempotentsettlementledgerjava.domain;

import java.math.BigDecimal;

/**
 * 账户及其当前余额。initialBalance 为开户金额（不可变），
 * 核对时按 初始余额 + 借方发生额 - 贷方发生额 重算余额，与 balance 比对。
 */
public class Account {

    private String accountNo;
    private BigDecimal initialBalance;
    private BigDecimal balance;
    private long version;

    public Account() {
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
