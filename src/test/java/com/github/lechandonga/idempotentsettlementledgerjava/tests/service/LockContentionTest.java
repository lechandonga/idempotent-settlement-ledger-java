package com.github.lechandonga.idempotentsettlementledgerjava.tests.service;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.support.AccountLocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 账户锁超时路径的确定性覆盖：1ms 超时 + 外部占锁，争用错误必现且与余额不足区分。 */
class LockContentionTest {

    private static final Logger log = LoggerFactory.getLogger(LockContentionTest.class);

    private TransferCommand cmd(String key, String from, String to, String amount) {
        TransferCommand c = new TransferCommand();
        c.setIdempotencyKey(key);
        c.setFromAccountNo(from);
        c.setToAccountNo(to);
        c.setAmount(new BigDecimal(amount));
        return c;
    }

    @Test
    @DisplayName("账户锁被占用且等待超时 -> CONCURRENT_CONFLICT（可重试），绝不产生入账")
    void lockTimeoutYieldsConcurrentConflict() throws Exception {
        AccountLocks locks = new AccountLocks(1L);
        LedgerService service = new LedgerService(new AccountRepository(), new EntryRepository(),
                new IdempotencyRepository(), locks);
        service.createAccount("A", new BigDecimal("100.00"));
        service.createAccount("B", new BigDecimal("0.00"));

        // 由另一个线程持有 A、B 的条带锁（ReentrantLock 可重入，同线程自占自试锁会立即成功，
        // 因此必须跨线程占锁才能真实模拟争用）。
        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            Lock b = locks.lockFor("B");
            Lock a = locks.lockFor("A");
            b.lock();
            acquired.countDown();
            a.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                a.unlock();
                b.unlock();
            }
        }, "lock-holder");
        holder.start();
        acquired.await(5, TimeUnit.SECONDS);
        try {
            LedgerException ex = assertThrows(LedgerException.class,
                    () -> service.transfer(cmd("TIMEOUT-1", "A", "B", "10.00")));
            log.info("[输入] A/B 锁被其他线程占用，超时=1ms 发起 A->B 10.00; [判定] errorCode={} (不同于INSUFFICIENT_FUNDS={})",
                    ex.getErrorCode(), ex.getErrorCode() != ErrorCode.INSUFFICIENT_FUNDS);
            assertEquals(ErrorCode.CONCURRENT_CONFLICT, ex.getErrorCode());
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance("A")),
                    "争用失败不得改动任何余额");
        } finally {
            release.countDown();
            holder.join(5000);
        }

        // 锁释放后，用原键重试必须成功——证明争用错误是"可重试"语义
        var result = service.transfer(cmd("TIMEOUT-1", "A", "B", "10.00"));
        log.info("[输入] 锁释放后用原键 TIMEOUT-1 重试; [判定] 成功 transferId={}, A={}, B={}",
                result.getTransferId(), service.getBalance("A").toPlainString(),
                service.getBalance("B").toPlainString());
        assertEquals(0, new BigDecimal("90.00").compareTo(service.getBalance("A")));
        assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance("B")));
        assertEquals(true, service.reconcile().isBalanced());
    }
}
