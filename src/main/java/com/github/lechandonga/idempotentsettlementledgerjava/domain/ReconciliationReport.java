package com.github.lechandonga.idempotentsettlementledgerjava.domain;

import java.util.List;
import java.util.Map;

/**
 * 核对报告。
 *
 * @param status                 总体结论
 * @param totalBalanceFromAccounts 由账户当前余额直接汇总的总余额
 * @param totalNetFromEntries      由全部流水净额汇总的总余额（应等于初始总额）
 * @param accountBalancesFromLedger 由流水重算出的各账户余额
 * @param findings               全部发现明细（可据此人工判断）
 */
public record ReconciliationReport(ReconciliationStatus status,
                                   long totalBalanceFromAccounts,
                                   long totalNetFromEntries,
                                   Map<String, Long> accountBalancesFromLedger,
                                   List<ReconciliationFinding> findings) {
}
