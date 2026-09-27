package com.github.lechandonga.idempotentsettlementledgerjava.tests;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerEntry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationStatus;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.tests.support.TestLedger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础记账规则：双分录、余额扣减、余额不足拒绝、冲正回退与总额守恒。
 */
class AccountAndTransferTest {

    private static final Logger log = LoggerFactory.getLogger(AccountAndTransferTest.class);

    @Test
    void transferProducesBalancedDoubleEntriesAndConservesTotal() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "账户A", 10_000)
                .seed("B", "账户B", 5_000);
        long totalBefore = ledger.customerTotalBalance();
        log.info("[输入] A=10000, B=5000, 转账 A->B 3000，初始总额={}", totalBefore);

        TransferResult result = ledger.transferService.transfer(
                new TransferCommand("K1", "A", "B", 3_000, "货款"));
        log.info("[判定] 交易号={} replayed={}；期望首次入账 replayed=false",
                result.transactionId(), result.replayed());

        assertFalse(result.replayed(), "首次提交必须真实入账");
        assertEquals(7_000, ledger.balanceOf("A"), "A 应扣减 3000");
        assertEquals(8_000, ledger.balanceOf("B"), "B 应增加 3000");
        assertEquals(totalBefore, ledger.customerTotalBalance(), "客户资金总额必须守恒");
        assertEquals(0, ledger.totalBalance(), "全系统借贷净额必须恒为 0");

        List<LedgerEntry> entries = ledger.journal.findAllEntries().stream()
                .filter(e -> e.transactionId().equals(result.transactionId()))
                .toList();
        assertEquals(2, entries.size(), "一笔转账必须产生两条分录");
        long debitSum = entries.stream().filter(LedgerEntry::debit).mapToLong(LedgerEntry::amount).sum();
        long creditSum = entries.stream().filter(e -> !e.debit()).mapToLong(LedgerEntry::amount).sum();
        log.info("[判定] 借方合计={} 贷方合计={}，期望相等且=3000", debitSum, creditSum);
        assertEquals(3_000, debitSum);
        assertEquals(3_000, creditSum);

        assertEquals(ReconciliationStatus.OK,
                ledger.reconciliationService.reconcile().status(),
                "正常记账后核对必须通过");
    }

    @Test
    void overdraftIsRejectedAsInsufficientFundsAndNotRetrying() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "账户A", 100)
                .seed("B", "账户B", 0);
        log.info("[输入] A=100, B=0, 试图转账 A->B 500（超过余额）");

        LedgerException ex = assertThrows(LedgerException.class, () ->
                ledger.transferService.transfer(
                        new TransferCommand("K2", "A", "B", 500, null)));
        log.info("[判定] 错误码={} retryable={}；期望 INSUFFICIENT_FUNDS 且不可重试",
                ex.getCode(), ex.getCode().isRetryable());

        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.getCode());
        assertFalse(ex.getCode().isRetryable(), "余额不足是业务拒绝，不能提示调用方重试");
        assertEquals(100, ledger.balanceOf("A"), "扣款失败不得改动余额");
        assertEquals(0, ledger.balanceOf("B"));
        assertTrue(ledger.journal.findAllTransactions().stream()
                        .noneMatch(t -> "K2".equals(t.idempotencyKey())),
                "扣款失败不得留下该笔转账流水");
    }

    @Test
    void invalidParametersAreRejectedDistinctly() {
        TestLedger ledger = TestLedger.create(200).seed("A", "A", 100).seed("B", "B", 100);
        log.info("[输入] 非法参数用例：金额<=0、收付方相同、幂等键为空");

        LedgerException zeroAmount = assertThrows(LedgerException.class, () ->
                ledger.transferService.transfer(new TransferCommand("K3", "A", "B", 0, null)));
        LedgerException sameAccount = assertThrows(LedgerException.class, () ->
                ledger.transferService.transfer(new TransferCommand("K4", "A", "A", 10, null)));
        LedgerException blankKey = assertThrows(LedgerException.class, () ->
                ledger.transferService.transfer(new TransferCommand(" ", "A", "B", 10, null)));
        log.info("[判定] 金额非法={} 同账户={} 空幂等键={}；期望全部 PARAM_INVALID",
                zeroAmount.getCode(), sameAccount.getCode(), blankKey.getCode());

        assertEquals(ErrorCode.PARAM_INVALID, zeroAmount.getCode());
        assertEquals(ErrorCode.PARAM_INVALID, sameAccount.getCode());
        assertEquals(ErrorCode.PARAM_INVALID, blankKey.getCode());
    }

    @Test
    void reversalRestoresBalancesConservesTotalAndIsItselfIdempotent() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "账户A", 10_000)
                .seed("B", "账户B", 0);
        long totalBefore = ledger.customerTotalBalance();
        TransferResult transfer = ledger.transferService.transfer(
                new TransferCommand("K5", "A", "B", 4_000, "需冲正的入账"));
        log.info("[输入] A->B 4000 已入账 tx={}，随后冲正", transfer.transactionId());

        ReversalResult first = ledger.transferService.reverse(transfer.transactionId(), null);
        ReversalResult second = ledger.transferService.reverse(transfer.transactionId(), null);
        log.info("[判定] 首次冲正 rv={} replayed={}；重复冲正 replayed={}（应为 true 且同号）",
                first.reversalTransactionId(), first.replayed(), second.replayed());

        assertFalse(first.replayed());
        assertTrue(second.replayed(), "冲正必须幂等：重复调用命中回放");
        assertEquals(first.reversalTransactionId(), second.reversalTransactionId(),
                "冲正幂等重放必须返回同一个冲正交易号");
        assertEquals(10_000, ledger.balanceOf("A"), "冲正后 A 恢复原余额");
        assertEquals(0, ledger.balanceOf("B"), "冲正后 B 恢复原余额");
        assertEquals(totalBefore, ledger.customerTotalBalance(), "冲正不得破坏客户资金守恒");
        assertEquals(0, ledger.totalBalance(), "冲正后全系统净额仍必须为 0");
        long postedEntries = ledger.journal.findAllEntries().stream()
                .filter(e -> e.transactionId().equals(transfer.transactionId())
                        || e.transactionId().equals(first.reversalTransactionId()))
                .count();
        assertEquals(4, postedEntries, "原交易两条分录 + 冲正两条分录");
        assertEquals(ReconciliationStatus.OK,
                ledger.reconciliationService.reconcile().status());
    }
}
