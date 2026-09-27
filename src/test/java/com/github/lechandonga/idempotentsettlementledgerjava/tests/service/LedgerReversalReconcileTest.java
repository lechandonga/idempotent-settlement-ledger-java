package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.Direction;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.Entry;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.support.AccountLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冲正规则与核对结论：冲正幂等、净发生额为零、重复冲正/未知单据区分，以及篡改与改账可被发现。 */
class LedgerReversalReconcileTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerReversalReconcileTest.class);

    private LedgerService ledger;
    private AccountRepository accountRepository;
    private EntryRepository entryRepository;

    @BeforeEach
    void setUp() {
        accountRepository = new AccountRepository();
        entryRepository = new EntryRepository();
        ledger = new LedgerService(accountRepository, entryRepository,
                new IdempotencyRepository(), new AccountLocks());
        ledger.createAccount("A", new BigDecimal("100.00"));
        ledger.createAccount("B", new BigDecimal("0.00"));
    }

    private TransferCommand cmd(String key, String from, String to, String amount) {
        TransferCommand c = new TransferCommand();
        c.setIdempotencyKey(key);
        c.setFromAccountNo(from);
        c.setToAccountNo(to);
        c.setAmount(new BigDecimal(amount));
        return c;
    }

    @Test
    @DisplayName("冲正回退余额且净发生额为零；冲正本身幂等，重复调用只重放不重复回退")
    void reversalRestoresAndIsIdempotent() {
        TransferResult t = ledger.transfer(cmd("K1", "A", "B", "30.00"));
        log.info("[输入] 转账 A->B 30.00; [判定] transferId={}, A={}, B={}",
                t.getTransferId(), ledger.getBalance("A"), ledger.getBalance("B"));

        ReversalResult r1 = ledger.reverse("R1", t.getTransferId());
        log.info("[输入] 冲正 R1 -> {}; [判定] replayed={}, A={}, B={}",
                t.getTransferId(), r1.isReplayed(), ledger.getBalance("A"), ledger.getBalance("B"));
        assertFalse(r1.isReplayed());
        assertEquals(0, new BigDecimal("100.00").compareTo(ledger.getBalance("A")), "冲正后 A 复原");
        assertEquals(0, new BigDecimal("0.00").compareTo(ledger.getBalance("B")), "冲正后 B 复原");
        assertEquals(4, entryRepository.findAll().size(), "转账2条 + 冲正2条");

        // 冲正产生独立新单据：新分录 transferId 为冲正单号，reversalOf 指向原 transferId。
        // 因此按 reversalOf 过滤才能取到冲正分录（它们必须一借一贷、金额相等）。
        var reversalEntries = entryRepository.findAll().stream()
                .filter(e -> t.getTransferId().equals(e.getReversalOf()))
                .toList();
        assertEquals(2, reversalEntries.size(), "冲正必须恰好产生两条分录");
        long reversalDebits = reversalEntries.stream()
                .filter(e -> e.getDirection() == Direction.DEBIT).count();
        long reversalCredits = reversalEntries.stream()
                .filter(e -> e.getDirection() == Direction.CREDIT).count();
        assertEquals(1, reversalDebits, "冲正分录应恰好一条借方（原转出方A补回）");
        assertEquals(1, reversalCredits, "冲正分录应恰好一条贷方（原收款方B扣减）");
        assertTrue(reversalEntries.stream()
                        .allMatch(e -> t.getTransferId().equals(e.getReversalOf())),
                "冲正分录必须全部标记 reversalOf = 原 transferId");

        ReversalResult r2 = ledger.reverse("R1", t.getTransferId());
        log.info("[输入] 用同键 R1 重复冲正; [判定] replayed={}, transferId一致={}, 流水仍为4条={}",
                r2.isReplayed(), r2.getTransferId().equals(t.getTransferId()),
                entryRepository.findAll().size() == 4);
        assertTrue(r2.isReplayed());
        assertEquals(4, entryRepository.findAll().size(), "重复冲正绝不能再产生分录");
        assertTrue(ledger.reconcile().isBalanced());
    }

    @Test
    @DisplayName("换一个幂等键再次冲正同一笔 -> ALREADY_REVERSED；未知单据 -> TRANSFER_NOT_FOUND")
    void doubleReverseAndUnknownTransferDistinguished() {
        TransferResult t = ledger.transfer(cmd("K2", "A", "B", "10.00"));
        ledger.reverse("R2", t.getTransferId());

        LedgerException twice = assertThrows(LedgerException.class,
                () -> ledger.reverse("R2-DIFFERENT-KEY", t.getTransferId()));
        log.info("[输入] 已冲正单据换新键再冲正; [判定] errorCode={}", twice.getErrorCode());
        assertEquals(ErrorCode.ALREADY_REVERSED, twice.getErrorCode());

        LedgerException missing = assertThrows(LedgerException.class,
                () -> ledger.reverse("R3", "T-nope"));
        log.info("[输入] 冲正不存在的单据; [判定] errorCode={} (与ALREADY_REVERSED不同={})",
                missing.getErrorCode(), missing.getErrorCode() != ErrorCode.ALREADY_REVERSED);
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, missing.getErrorCode());

        // 同一冲正键拿去冲另一笔单据（不同内容）-> 幂等冲突，独立于上面两个业务错误
        TransferResult t2 = ledger.transfer(cmd("K2B", "A", "B", "5.00"));
        LedgerException conflict = assertThrows(LedgerException.class,
                () -> ledger.reverse("R2", t2.getTransferId()));
        log.info("[输入] 冲正键 R2 改指向另一笔单据; [判定] errorCode={}", conflict.getErrorCode());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.getErrorCode());
    }

    @Test
    @DisplayName("流水被外部篡改金额 -> 核对不平衡且问题明细指向被篡改分录")
    void tamperedEntryIsDetected() {
        TransferResult t = ledger.transfer(cmd("K4", "A", "B", "20.00"));
        assertTrue(ledger.reconcile().isBalanced());

        // 外部直接改动一条流水分录金额（绕过记账服务）
        Entry tampered = entryRepository.findAll().get(0);
        log.info("[输入] 外部篡改 seq={} 金额 20.00 -> 200.00; [判定] 期望核对发现 tampered",
                tampered.getSeq());
        tampered.setAmount(new BigDecimal("200.00"));

        ReconciliationReport report = ledger.reconcile();
        log.info("[输入] 篡改后核对; [判定] balanced={}, problems={}",
                report.isBalanced(), report.getProblems());
        assertFalse(report.isBalanced());
        assertTrue(report.getProblems().stream().anyMatch(p -> p.contains("tampered")),
                "问题明细应包含 tampered 提示: " + report.getProblems());
    }

    @Test
    @DisplayName("账户余额被外部直接改动 -> 核对发现余额与流水不一致")
    void externallyChangedBalanceIsDetected() {
        ledger.transfer(cmd("K5", "A", "B", "20.00"));
        // 外部直接把 A 余额改掉（不动流水）
        accountRepository.find("A").orElseThrow().setBalance(new BigDecimal("1.00"));
        ReconciliationReport report = ledger.reconcile();
        log.info("[输入] 外部把 A 余额直接改为1.00(流水未动); [判定] balanced={}, problems={}",
                report.isBalanced(), report.getProblems());
        assertFalse(report.isBalanced());
        assertTrue(report.getProblems().stream().anyMatch(p -> p.contains("balance mismatch")),
                "应报告余额与流水不符: " + report.getProblems());

        var detail = report.getAccountDetails().stream()
                .filter(d -> d.getAccountNo().equals("A")).findFirst().orElseThrow();
        log.info("[输入] A 账户核对明细; [判定] ledgerBalance={}, computedFromEntries={}",
                detail.getLedgerBalance(), detail.getComputedBalance());
        assertEquals(0, detail.getComputedBalance().compareTo(new BigDecimal("80.00")),
                "流水重算余额应为80.00");
    }
}
