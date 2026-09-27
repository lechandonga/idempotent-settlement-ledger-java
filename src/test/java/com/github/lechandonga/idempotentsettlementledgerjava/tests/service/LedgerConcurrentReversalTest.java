package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发冲正：同一笔转账被两个线程用不同幂等键同时冲正，只允许一次真实回退。 */
class LedgerConcurrentReversalTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerConcurrentReversalTest.class);

    private LedgerService ledger;
    private EntryRepository entryRepository;

    @BeforeEach
    void setUp() {
        entryRepository = new EntryRepository();
        ledger = new LedgerService(new AccountRepository(), entryRepository,
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
    @DisplayName("两线程不同幂等键并发冲正同一笔：恰一方成功，另一方 ALREADY_REVERSED，余额只回退一次")
    void concurrentReversalOfSameTransferOnlyOnce() throws Exception {
        String transferId = ledger.transfer(cmd("K1", "A", "B", "40.00")).getTransferId();
        log.info("[输入] 准备被冲正的转账 transferId={}, 冲正前 A=60 B=40", transferId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger alreadyReversed = new AtomicInteger();
        AtomicReference<ErrorCode> unexpected = new AtomicReference<>();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    // 每个线程用自己的幂等键冲正同一笔：它们不是重复提交，而是并发竞争
                    ledger.reverse("RV-" + idx, transferId);
                    success.incrementAndGet();
                } catch (LedgerException e) {
                    if (e.getErrorCode() == ErrorCode.ALREADY_REVERSED) {
                        alreadyReversed.incrementAndGet();
                    } else {
                        unexpected.set(e.getErrorCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        // 所有线程就绪后统一放行，最大化同时进入冲正临界区的概率
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[输入] 8 线程不同键并发冲正同一笔; [判定] 成功={}, ALREADY_REVERSED={}, 意外错误={}",
                success.get(), alreadyReversed.get(), unexpected.get());

        assertEquals(1, success.get(), "只能有一次冲正真正入账");
        assertEquals(threads - 1, alreadyReversed.get(), "其余必须确定性拒绝为 ALREADY_REVERSED");
        assertEquals(null, unexpected.get(), "不允许出现其他类型失败");
        assertEquals(0, new BigDecimal("100.00").compareTo(ledger.getBalance("A")), "余额只回退一次");
        assertEquals(0, new BigDecimal("0.00").compareTo(ledger.getBalance("B")));
        assertEquals(4, entryRepository.findAll().size(), "原始2条 + 冲正2条，失败冲正不得留痕");
        assertTrue(ledger.reconcile().isBalanced());
    }
}
