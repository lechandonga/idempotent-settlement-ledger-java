package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationReport;
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

/** 基础链路：开户、首次转账、幂等重放、同键不同内容冲突、余额不足与参数错误区分。 */
class LedgerBasicTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerBasicTest.class);

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new AccountRepository(), new EntryRepository(),
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
    @DisplayName("首次转账真实入账一次，重复提交只重放不重复入账")
    void transferOnceAndReplay() {
        TransferResult first = ledger.transfer(cmd("K1", "A", "B", "30.00"));
        log.info("[输入] key=K1 A->B 30.00 首次; [判定] transferId={}, replayed={}",
                first.getTransferId(), first.isReplayed());
        assertFalse(first.isReplayed(), "首次执行不应是重放");

        TransferResult replay = ledger.transfer(cmd("K1", "A", "B", "30.00"));
        log.info("[输入] key=K1 同内容重复提交; [判定] transferId={} (与首次一致={}), replayed={}",
                replay.getTransferId(), replay.getTransferId().equals(first.getTransferId()),
                replay.isReplayed());

        assertEquals(first.getTransferId(), replay.getTransferId(), "同键必须返回同一个 transferId");
        assertTrue(replay.isReplayed(), "重复提交必须被标记为重放");
        assertEquals(0, new BigDecimal("70.00").compareTo(ledger.getBalance("A")), "A 只被扣一次");
        assertEquals(0, new BigDecimal("30.00").compareTo(ledger.getBalance("B")), "B 只被加一次");

        ReconciliationReport report = ledger.reconcile();
        log.info("[输入] 转账后核对; [判定] balanced={}, problems={}", report.isBalanced(),
                report.getProblems());
        assertTrue(report.isBalanced(), "正常转账后账目必须平衡");
    }

    @Test
    @DisplayName("同一幂等键提交不同内容 -> IDEMPOTENCY_CONFLICT，区别于 PARAM_INVALID")
    void sameKeyDifferentPayloadIsConflict() {
        ledger.transfer(cmd("K2", "A", "B", "10.00"));

        LedgerException conflict = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K2", "A", "B", "20.00")));
        log.info("[输入] key=K2 先10.00再用同键提交20.00; [判定] errorCode={}",
                conflict.getErrorCode());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.getErrorCode());
        // 冲突不能污染账目：A 仍只扣 10
        assertEquals(0, new BigDecimal("90.00").compareTo(ledger.getBalance("A")));

        LedgerException paramError = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K3", "A", "B", "-5.00")));
        log.info("[输入] key=K3 amount=-5.00; [判定] errorCode={} (与冲突码不同={})",
                paramError.getErrorCode(), paramError.getErrorCode() != ErrorCode.IDEMPOTENCY_CONFLICT);
        assertEquals(ErrorCode.PARAM_INVALID, paramError.getErrorCode());
    }

    @Test
    @DisplayName("余额不足 -> INSUFFICIENT_FUNDS，且该确定性拒绝被记忆并稳定重放")
    void insufficientFundsIsDeterministicAndReplaysSame() {
        LedgerException first = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K4", "A", "B", "150.00")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.getErrorCode());
        log.info("[输入] key=K4 A余额100转150; [判定] errorCode={}", first.getErrorCode());

        LedgerException replay = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K4", "A", "B", "150.00")));
        log.info("[输入] key=K4 同内容重放; [判定] errorCode={} (稳定={})",
                replay.getErrorCode(), replay.getErrorCode() == ErrorCode.INSUFFICIENT_FUNDS);
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, replay.getErrorCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.getBalance("B")), "余额不足绝不能发生任何入账");
    }

    @Test
    @DisplayName("同账户转账、未知账户与余额不足错误码互相区分")
    void distinctFailureReasons() {
        LedgerException same = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K5", "A", "A", "1.00")));
        assertEquals(ErrorCode.SAME_ACCOUNT, same.getErrorCode());

        LedgerException missing = assertThrows(LedgerException.class,
                () -> ledger.transfer(cmd("K6", "A", "NOPE", "1.00")));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, missing.getErrorCode());
        log.info("[输入] K5同账户 / K6目标账户不存在; [判定] {} / {}",
                same.getErrorCode(), missing.getErrorCode());
    }
}
