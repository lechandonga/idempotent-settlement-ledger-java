package com.github.lechandonga.idempotentsettlementledgerjava.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 核对结论。balanced 为 true 表示借贷守恒、流水与余额全部对得上且哈希链完好；
 * 为 false 时 problems 给出每条可判断的问题明细，accountDetails 给出逐户核对依据。
 */
public class ReconciliationReport {

    private boolean balanced;
    private List<String> problems = new ArrayList<>();
    private List<AccountDetail> accountDetails = new ArrayList<>();

    public static class AccountDetail {
        private String accountNo;
        private BigDecimal ledgerBalance;
        private BigDecimal computedBalance;
        private BigDecimal totalDebit;
        private BigDecimal totalCredit;

        public String getAccountNo() {
            return accountNo;
        }

        public void setAccountNo(String accountNo) {
            this.accountNo = accountNo;
        }

        public BigDecimal getLedgerBalance() {
            return ledgerBalance;
        }

        public void setLedgerBalance(BigDecimal ledgerBalance) {
            this.ledgerBalance = ledgerBalance;
        }

        public BigDecimal getComputedBalance() {
            return computedBalance;
        }

        public void setComputedBalance(BigDecimal computedBalance) {
            this.computedBalance = computedBalance;
        }

        public BigDecimal getTotalDebit() {
            return totalDebit;
        }

        public void setTotalDebit(BigDecimal totalDebit) {
            this.totalDebit = totalDebit;
        }

        public BigDecimal getTotalCredit() {
            return totalCredit;
        }

        public void setTotalCredit(BigDecimal totalCredit) {
            this.totalCredit = totalCredit;
        }
    }

    public boolean isBalanced() {
        return balanced;
    }

    public void setBalanced(boolean balanced) {
        this.balanced = balanced;
    }

    public List<String> getProblems() {
        return problems;
    }

    public void setProblems(List<String> problems) {
        this.problems = problems;
    }

    public List<AccountDetail> getAccountDetails() {
        return accountDetails;
    }

    public void setAccountDetails(List<AccountDetail> accountDetails) {
        this.accountDetails = accountDetails;
    }
}
