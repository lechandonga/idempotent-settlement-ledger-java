package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 账台级常量。
 */
public final class LedgerConstants {

    private LedgerConstants() {
    }

    /**
     * 系统开业配平科目。开户初始余额以“借 新账户 / 贷 本科目”入账，
     * 使全系统借贷恒等、余额可由流水完整重算；该科目不出现在客户账户列表中。
     */
    public static final String OPENING_ACCOUNT_ID = "SYSTEM_OPENING";
}
