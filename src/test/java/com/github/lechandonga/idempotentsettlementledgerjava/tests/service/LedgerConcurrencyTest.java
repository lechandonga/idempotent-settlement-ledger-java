package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.support.AccountLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发重点场景：
 * 1) 同一账户被多线程同时扣款：成功金额之和不得超过余额，绝不透支/重复扣款；
 * 2) 不同失败原因可区分：INSUFFICIENT_FUNDS（确定性拒绝）vs CONCURRENT_CONFLICT（应重试）；
 * 3) 所有成功转账事后核对：借贷守恒、流水与余额一致。
 */
class LedgerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerConcurrencyTest.class);

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new AccountRepository(), new EntryRepository(),
                new IdempotencyRepository(), new AccountLocks());
        ledger.createAccount("SOURCE", new BigDecimal("1000.00"));
        for (int i = 0; i < 16; i++) {
            ledger.createAccount("T" + i, new BigDecimal("0.00"));
        }
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
    @DisplayName("200 笔每笔10元同时从同一账户扣出：成功总额<=1000，永不透支，失败只能是余额不足")
    void concurrentDebitsNeverOverdraft() throws Exception {
        int threads = 32;
        int total = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每笔请求独立幂等键：它们是不同的业务请求，只是并发争用同一账户
                    ledger.transfer(cmd("C-" + idx, "SOURCE", "T" + (idx % 16), "10.00"));
                    success.incrementAndGet();
                } catch (LedgerException e) {
                    failures.computeIfAbsent(e.getErrorCode(), k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "所有任务应在超时前结束");
        pool.shutdown();

        int insufficient = failures.getOrDefault(ErrorCode.INSUFFICIENT_FUNDS, new AtomicInteger()).get();
        int contention = failures.getOrDefault(ErrorCode.CONCURRENT_CONFLICT, new AtomicInteger()).get();
        log.info("[输入] 200 笔 x 10.00 并发扣同一账户(余额1000); "
                        + "[判定] 成功={}, 余额不足={}, 并发争用={}, 其他失败={}",
                success.get(), insufficient, contention,
                failures.size() - (failures.containsKey(ErrorCode.INSUFFICIENT_FUNDS) ? 1 : 0)
                        - (failures.containsKey(ErrorCode.CONCURRENT_CONFLICT) ? 1 : 0));

        // 成功最多 100 笔（1000 / 10），且成功 + 失败 = 总数
        assertTrue(success.get() <= 100, "成功笔数不得超过余额可承受的 100 笔，实际=" + success.get());
        assertEquals(total, success.get() + insufficient + contention, "每笔必须有明确结论");
        // 不允许出现除"余额不足/并发争用"以外的失败
        failures.keySet().forEach(code ->
                assertTrue(code == ErrorCode.INSUFFICIENT_FUNDS || code == ErrorCode.CONCURRENT_CONFLICT,
                        "出现了意料外的失败类型: " + code));

        BigDecimal source = ledger.getBalance("SOURCE");
        log.info("[输入] 并发结束后查 SOURCE 余额; [判定] balance={}, 期望>=0, 与(1000-成功*10)一致={}",
                source.toPlainString(),
                source.compareTo(new BigDecimal(1000 - success.get() * 10L)) == 0);
        assertEquals(0, source.compareTo(new BigDecimal(1000 - success.get() * 10L)),
                "余额必须精确等于 初始 - 成功扣款总额");
        assertTrue(source.signum() >= 0, "绝不能透支");

        ReconciliationReport report = ledger.reconcile();
        log.info("[输入] 并发风暴后核对; [判定] balanced={}, problems={}",
                report.isBalanced(), report.getProblems());
        assertTrue(report.isBalanced(), "并发结束后账目必须仍然守恒且可对账: " + report.getProblems());
    }

    @Test
    @DisplayName("网状并发互转后：系统总金额守恒，逐户余额与流水对得上")
    void concurrentMeshTransfersConserveTotal() throws Exception {
        // 建立 8 个工作账户，各开 500.00 余额（开户直接注资，不计入并发断言）
        for (int i = 0; i < 8; i++) {
            ledger.createAccount("W" + i, new BigDecimal("500.00"));
        }
        int rounds = 400;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(rounds);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < rounds; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    int from = idx % 8;
                    int to = (idx * 7 + 3) % 8;
                    if (from == to) {
                        done.countDown();
                        return;
                    }
                    ledger.transfer(cmd("M-" + idx, "W" + from, "W" + to, "1.00"));
                    success.incrementAndGet();
                } catch (LedgerException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[输入] 400 笔网状互转 x 1.00; [判定] 成功={}, 拒绝={}", success.get(), rejected.get());

        ReconciliationReport report = ledger.reconcile();
        log.info("[输入] 网状互转后核对; [判定] balanced={}, problems={}",
                report.isBalanced(), report.getProblems());
        assertTrue(report.isBalanced(), "系统总额守恒且逐户可对账: " + report.getProblems());

        // 8 个工作账户总余额恒为 4000（成功互转只是搬家）
        BigDecimal workTotal = BigDecimal.ZERO;
        for (int i = 0; i < 8; i++) {
            workTotal = workTotal.add(ledger.getBalance("W" + i));
        }
        log.info("[输入] 汇总 W0..W7 余额; [判定] total={}, 期望=4000.00", workTotal.toPlainString());
        assertEquals(0, workTotal.compareTo(new BigDecimal("4000.00")), "工作账户总金额守恒");
    }
}
