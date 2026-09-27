package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.support.AccountLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 争用与重试语义：
 * 1) 同一个幂等键并发提交两次：只有一次真实入账；败者先收 CONCURRENT_CONFLICT（键进行中），
 *    稍后重试得到与首次完全一致的稳定结果；
 * 2) 账户锁被长时间占用时，等待超时的一方收 CONCURRENT_CONFLICT（区别于余额不足），
 *    占锁释放后用原键重试成功，且全程不产生重复入账、账目守恒。
 */
class LedgerRaceAndRetryTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerRaceAndRetryTest.class);

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new AccountRepository(), new EntryRepository(),
                new IdempotencyRepository(), new AccountLocks());
        ledger.createAccount("A", new BigDecimal("1000.00"));
        ledger.createAccount("B", new BigDecimal("0.00"));
        ledger.createAccount("C", new BigDecimal("1000.00"));
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
    @DisplayName("同键并发：仅一次真实入账，败者先收 CONCURRENT_CONFLICT，稍后重试为稳定重放")
    void sameKeyConcurrentOnlyOnePosting() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<TransferResult> winnerResult = new AtomicReference<>();
        AtomicReference<ErrorCode> loserCode = new AtomicReference<>();

        Runnable request = () -> {
            try {
                ready.countDown();
                start.await();
                TransferResult r = ledger.transfer(cmd("SAME-KEY", "A", "B", "25.00"));
                winnerResult.set(r);
            } catch (LedgerException e) {
                loserCode.set(e.getErrorCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> f1 = pool.submit(request);
        Future<?> f2 = pool.submit(request);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        log.info("[输入] 同键 SAME-KEY 双线程同时提交 A->B 25.00; [判定] 入账成功={}, 败者errorCode={}",
                winnerResult.get() != null, loserCode.get());
        assertNotNull(winnerResult.get(), "必须恰好有一方真实入账");
        assertEquals(ErrorCode.CONCURRENT_CONFLICT, loserCode.get(),
                "进行中的同键重复请求必须得到可重试的并发冲突，而非幂等冲突");

        // 败者用同一键稍后重试：必须是重放，transferId 相同，账目不再变化
        TransferResult retry = ledger.transfer(cmd("SAME-KEY", "A", "B", "25.00"));
        log.info("[输入] 败者用 SAME-KEY 稍后重试; [判定] replayed={}, transferId一致={}, A余额={}",
                retry.isReplayed(), retry.getTransferId().equals(winnerResult.get().getTransferId()),
                ledger.getBalance("A").toPlainString());
        assertTrue(retry.isReplayed());
        assertEquals(winnerResult.get().getTransferId(), retry.getTransferId());
        assertEquals(0, new BigDecimal("975.00").compareTo(ledger.getBalance("A")), "只扣一次");
        assertEquals(0, new BigDecimal("25.00").compareTo(ledger.getBalance("B")));
    }

    @Test
    @DisplayName("占锁期间争用请求收 CONCURRENT_CONFLICT 并被拒绝，释放后原键重试成功，全程只入账一次")
    void contentionRejectedThenRetrySucceedsOnce() throws Exception {
        // 用 1ms 锁超时的独立服务，配合后台持锁线程，确定性制造账户锁争用
        AccountLocks locks = new AccountLocks(1L);
        AccountRepository accounts = new AccountRepository();
        EntryRepository entries = new EntryRepository();
        LedgerService svc = new LedgerService(accounts, entries,
                new IdempotencyRepository(), locks);
        svc.createAccount("A", new BigDecimal("100.00"));
        svc.createAccount("B", new BigDecimal("0.00"));

        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            var la = locks.lockFor("A");
            var lb = locks.lockFor("B");
            la.lock();
            acquired.countDown();
            lb.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lb.unlock();
                la.unlock();
            }
        }, "lock-holder");
        holder.start();
        acquired.await(5, TimeUnit.SECONDS);

        // 占锁期间发起：只能得到 CONCURRENT_CONFLICT，且不能有任何入账
        AtomicInteger conflicts = new AtomicInteger();
        int attempts = 10;
        for (int i = 0; i < attempts; i++) {
            try {
                svc.transfer(cmd("CONTENDED", "A", "B", "10.00"));
            } catch (LedgerException e) {
                assertEquals(ErrorCode.CONCURRENT_CONFLICT, e.getErrorCode(),
                        "占锁期间只允许并发争用错误，实际=" + e.getErrorCode());
                conflicts.incrementAndGet();
            }
        }
        log.info("[输入] 占锁期间用键 CONTENDED 连续尝试 {} 次; [判定] 全部 CONCURRENT_CONFLICT={}, 期间流水条数={}",
                attempts, conflicts.get() == attempts, entries.findAll().size());
        assertEquals(attempts, conflicts.get(), "占锁期间每一笔都必须因争用失败");
        assertEquals(0, entries.findAll().size(), "争用失败绝不能留下流水");
        assertEquals(0, new BigDecimal("100.00").compareTo(svc.getBalance("A")));

        release.countDown();
        holder.join(5000);

        // 释放后用原键重试：成功，且只入账一次
        TransferResult ok = svc.transfer(cmd("CONTENDED", "A", "B", "10.00"));
        TransferResult replay = svc.transfer(cmd("CONTENDED", "A", "B", "10.00"));
        log.info("[输入] 释放后用 CONTENDED 重试并再放; [判定] transferId一致={}, replayed={}, A={}, B={}",
                ok.getTransferId().equals(replay.getTransferId()), replay.isReplayed(),
                svc.getBalance("A").toPlainString(), svc.getBalance("B").toPlainString());
        assertEquals(ok.getTransferId(), replay.getTransferId());
        assertTrue(replay.isReplayed());
        assertEquals(0, new BigDecimal("90.00").compareTo(svc.getBalance("A")));
        assertEquals(0, new BigDecimal("10.00").compareTo(svc.getBalance("B")));
        assertTrue(svc.reconcile().isBalanced());
    }
}
