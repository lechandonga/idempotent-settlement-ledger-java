package com.github.lechandonga.idempotentsettlementledgerjava.tests;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerEntry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对与冲正：
 * - 正常账目核对 OK；
 * - 外部篡改分录金额 → TAMPERED（哈希不匹配）；
 * - 外部直接改账户余额 → UNBALANCED（账实不符）；
 * - 重复冲正必须被拒绝。
 */
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);

    @Test
    void cleanLedgerReconcilesOk() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "A", 8_000).seed("B", "B", 3_000).seed("C", "C", 0);
        ledger.transferService.transfer(new TransferCommand("R1", "A", "B", 500, null));
        ledger.transferService.transfer(new TransferCommand("R2", "B", "C", 200, null));

        ReconciliationReport report = ledger.reconciliationService.reconcile();
        log.info("[输入] 开户+2 笔正常转账后核对");
        log.info("[判定] status={} 账户余额合计={} 流水净额={} findings={}",
                report.status(), report.totalBalanceFromAccounts(),
                report.totalNetFromEntries(), report.findings().size());

        assertEquals(ReconciliationStatus.OK, report.status());
        assertEquals(0, report.totalNetFromEntries(), "全系统流水净额必须为 0");
        assertEquals(0, report.totalBalanceFromAccounts(), "含开业科目的余额合计必须为 0");
        assertTrue(report.findings().isEmpty());
    }

    @Test
    void tamperedEntryAmountIsDetectedAsTampered() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "A", 1_000).seed("B", "B", 0);
        TransferResult tx = ledger.transferService.transfer(
                new TransferCommand("T1", "A", "B", 300, null));

        // 模拟外部直接改库：只改金额、不重算哈希。
        List<LedgerEntry> txEntries = ledger.journal.findAllEntries().stream()
                .filter(e -> e.transactionId().equals(tx.transactionId())).toList();
        String tamperedEntryId = txEntries.get(0).id();
        long oldAmount = txEntries.get(0).amount();
        ledger.journal.tamperEntryAmountForVerification(tamperedEntryId, oldAmount + 999);
        log.info("[输入] 将交易 {} 的分录 {} 金额 {} 外部篡改为 {}",
                tx.transactionId(), tamperedEntryId, oldAmount, oldAmount + 999);

        ReconciliationReport report = ledger.reconciliationService.reconcile();
        log.info("[判定] status={}（期望 TAMPERED），发现明细={}", report.status(),
                report.findings().stream().map(f -> f.code() + "@" + f.reference()).toList());

        assertEquals(ReconciliationStatus.TAMPERED, report.status(),
                "改动分录但未重算哈希，必须判定为被篡改");
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.code().equals("ENTRY_HASH_MISMATCH")
                        && f.reference().equals(tamperedEntryId)));
        // 篡改必然同时导致借贷不平，结论仍应是更严重的 TAMPERED
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("ENTRIES_NOT_BALANCED")));
    }

    @Test
    void externallyChangedAccountBalanceIsDetectedAsUnbalanced() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "A", 1_000).seed("B", "B", 2_000);
        ledger.transferService.transfer(new TransferCommand("U1", "A", "B", 400, null));

        // 模拟外部绕开流水直接改账户余额。
        ledger.accounts.overwriteBalanceForVerification("B", 9_999);
        log.info("[输入] 外部绕开流水把 B 余额直接改为 9999（流水未动，哈希完好）");

        ReconciliationReport report = ledger.reconciliationService.reconcile();
        log.info("[判定] status={}（期望 UNBALANCED 而非 TAMPERED），findings={}",
                report.status(),
                report.findings().stream().map(f -> f.code() + "@" + f.reference()).toList());

        assertEquals(ReconciliationStatus.UNBALANCED, report.status(),
                "只改余额、流水哈希完好时应判定账目不平");
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.code().equals("ACCOUNT_BALANCE_MISMATCH")
                        && f.reference().equals("B")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.code().equals("ENTRY_HASH_MISMATCH") || f.code().equals("HASH_CHAIN_BROKEN")));
    }

    @Test
    void reversingTwiceWithDifferentKeysIsRejectedAfterFirstReversal() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "A", 1_000).seed("B", "B", 0);
        TransferResult tx = ledger.transferService.transfer(
                new TransferCommand("V1", "A", "B", 250, null));

        ledger.transferService.reverse(tx.transactionId(), "RV-KEY-1");
        LedgerException second = expect(() ->
                ledger.transferService.reverse(tx.transactionId(), "RV-KEY-2"),
                ErrorCode.TRANSACTION_ALREADY_REVERSED);
        log.info("[输入] 对 tx={} 先用 RV-KEY-1 冲正，再用 RV-KEY-2 冲正", tx.transactionId());
        log.info("[判定] 第二次冲正错误码={}（期望 TRANSACTION_ALREADY_REVERSED），余额 A={} B={}",
                second.getCode(), ledger.balanceOf("A"), ledger.balanceOf("B"));

        assertEquals(1_000, ledger.balanceOf("A"), "只能冲正一次");
        assertEquals(0, ledger.balanceOf("B"));
        assertEquals(ReconciliationStatus.OK, ledger.reconciliationService.reconcile().status());
    }

    @Test
    void reversingAlreadyReversedWithSameKeyReplaysOriginalReversal() {
        TestLedger ledger = TestLedger.create(200)
                .seed("A", "A", 1_000).seed("B", "B", 0);
        TransferResult tx = ledger.transferService.transfer(
                new TransferCommand("V2", "A", "B", 250, null));

        var first = ledger.transferService.reverse(tx.transactionId(), "SAME-RV");
        var again = ledger.transferService.reverse(tx.transactionId(), "SAME-RV");
        log.info("[输入] 同一冲正幂等键 SAME-RV 提交两次");
        log.info("[判定] first.replayed={} again.replayed={} 冲正交易号一致={}",
                first.replayed(), again.replayed(),
                first.reversalTransactionId().equals(again.reversalTransactionId()));

        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.reversalTransactionId(), again.reversalTransactionId());
    }

    private static LedgerException expect(RunnableCall call, ErrorCode expected) {
        try {
            call.run();
        } catch (LedgerException e) {
            assertEquals(expected, e.getCode());
            return e;
        }
        throw new AssertionError("期望抛出 " + expected);
    }

    private interface RunnableCall {
        void run();
    }
}
