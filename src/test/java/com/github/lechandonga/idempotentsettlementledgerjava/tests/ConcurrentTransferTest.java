package com.github.lechandonga.idempotentsettlementledgerjava.tests;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.tests.support.TestLedger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发记账：
 * - 多笔转账打向同一账户时不允许透支/重复扣款；
 * - 成功入账总额守恒；
 * - 锁争用（RETRYABLE_CONTENTION，可重试）必须与余额不足（INSUFFICIENT_FUNDS，拒绝）区分。
 */
class ConcurrentTransferTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransferTest.class);

    @Test
    void concurrentDebitsNeverOverdrawAndConserveTotal() throws Exception {
        TestLedger ledger = TestLedger.create(500)
                .seed("A", "热点付款方", 1_000)
                .seed("B", "收款方B", 0)
                .seed("C", "收款方C", 0)
                .seed("D", "收款方D", 0);
        long customerBefore = ledger.customerTotalBalance();
        int threads = 60;
        long amountEach = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String payee = List.of("B", "C", "D").get(i % 3);
            String key = "PAR-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                start.await();
                ledger.transferService.transfer(
                        new TransferCommand(key, "A", payee, amountEach, null));
                return null;
            }));
        }
        start.countDown();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger contention = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (Future<?> f : futures) {
            try {
                f.get();
                success.incrementAndGet();
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof LedgerException le) {
                    if (le.getCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                        insufficient.incrementAndGet();
                    } else if (le.getCode() == ErrorCode.RETRYABLE_CONTENTION) {
                        contention.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } else {
                    other.incrementAndGet();
                }
            }
        }
        pool.shutdown();

        long balanceA = ledger.balanceOf("A");
        long credited = ledger.balanceOf("B") + ledger.balanceOf("C") + ledger.balanceOf("D");
        log.info("[输入] {} 笔各 {} 元并发从 A（初始1000）转出到 B/C/D", threads, amountEach);
        log.info("[判定] 成功={} 余额不足={} 锁争用={} 其它={}",
                success.get(), insufficient.get(), contention.get(), other.get());
        log.info("[判定] A余额={}（必须>=0），收款方合计入金={}（必须=成功数*100={}）",
                balanceA, credited, success.get() * amountEach);

        assertEquals(0, other.get(), "不允许出现争用/余额不足之外的异常");
        assertTrue(balanceA >= 0, "并发扣减绝不允许透支");
        assertEquals(success.get() * amountEach, credited, "每笔成功转账必须且只能入金一次");
        assertEquals(1_000 - success.get() * amountEach, balanceA,
                "A 的余额必须等于初始余额减去成功转出总额（无重复扣款）");
        assertEquals(customerBefore, ledger.customerTotalBalance(), "客户资金总额守恒");
        assertEquals(0, ledger.totalBalance(), "全系统借贷净额恒为 0");
        assertEquals(10, success.get(), "1000 元 / 每笔 100 元，恰有 10 笔成功");
        assertEquals(threads - 10, insufficient.get() + contention.get(),
                "其余请求必须以可区分的失败原因结束");
    }

    @Test
    void lockContentionIsReportedAsRetryableDistinctFromInsufficientFunds() throws Exception {
        // 锁超时调小，便于稳定制造争用。
        TestLedger ledger = TestLedger.create(50)
                .seed("A", "A", 1_000).seed("B", "B", 0);

        AtomicInteger contention = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        try (AutoCloseable ignored = ledger.locks.acquireForTesting("A")) {
            // 测试线程持有 A 的锁；业务线程尝试 A->B，必然拿锁超时。
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String key = "LOCK-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        ledger.transferService.transfer(
                                new TransferCommand(key, "A", "B", 10, null));
                        success.incrementAndGet();
                    } catch (LedgerException e) {
                        if (e.getCode() == ErrorCode.RETRYABLE_CONTENTION) {
                            contention.incrementAndGet();
                        } else if (e.getCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                            insufficient.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
        }

        log.info("[输入] 外部持有 A 的账户锁期间，提交 5 笔 A->B 10 元");
        log.info("[判定] 锁争用={} 成功={} 余额不足={}；期望争用=5 且错误 retryable=true",
                contention.get(), success.get(), insufficient.get());

        assertEquals(5, contention.get(), "锁被占用时必须判定为并发争用");
        assertEquals(0, success.get(), "争用期间不得有任何入账");
        assertEquals(0, insufficient.get(), "争用绝不能误报为余额不足");
        assertEquals(1_000, ledger.balanceOf("A"), "争用失败不得改动余额");
        assertEquals(0, ledger.balanceOf("B"));

        // 锁释放后，同参数新请求应成功 —— 证明争用确实“可安全重试”。
        TransferResult after = ledger.transferService.transfer(
                new TransferCommand("LOCK-AFTER", "A", "B", 10, null));
        assertFalse(after.replayed());
        assertEquals(990, ledger.balanceOf("A"));
        log.info("[判定] 锁释放后新请求成功 tx={}，A={}，证明争用失败可安全重试",
                after.transactionId(), ledger.balanceOf("A"));
    }

    @Test
    void retriesAfterContentionEventuallySucceedWithoutDoublePosting() throws Exception {
        TestLedger ledger = TestLedger.create(50)
                .seed("A", "A", 500).seed("B", "B", 0);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 调用方策略：遇到可重试争用就退避后用“新的幂等键”重试，直到成功或业务拒绝。
                int attempts = 0;
                while (true) {
                    attempts++;
                    try {
                        ledger.transferService.transfer(new TransferCommand(
                                "RT-" + UUID.randomUUID(), "A", "B", 30, null));
                        return attempts;
                    } catch (LedgerException e) {
                        if (e.getCode() == ErrorCode.RETRYABLE_CONTENTION) {
                            Thread.sleep(5);
                            continue;
                        }
                        throw e;
                    }
                }
            }));
        }
        start.countDown();

        int totalAttempts = 0;
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : futures) {
            try {
                totalAttempts += f.get();
                ok++;
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof LedgerException le
                        && le.getCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdown();

        log.info("[输入] 20 笔各 30 元并发 A->B，调用方对争用自动重试（A 初始 500）");
        log.info("[判定] 成功={} 余额不足拒绝={} 总尝试次数={}（>成功数即证明发生过争用重试）",
                ok, rejected, totalAttempts);

        assertEquals(16, ok, "500/30 取整，16 笔成功");
        assertEquals(4, rejected, "其余 4 笔应被余额不足拒绝");
        assertTrue(totalAttempts >= ok, "重试不影响最终账目");
        assertEquals(20, ledger.balanceOf("A"), "500 - 16*30 = 20");
        assertEquals(480, ledger.balanceOf("B"), "16*30 = 480，无重复入账");
    }
}
