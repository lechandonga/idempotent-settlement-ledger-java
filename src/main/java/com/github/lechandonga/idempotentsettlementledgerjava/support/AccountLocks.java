package com.github.lechandonga.idempotentsettlementledgerjava.support;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 按账户编号哈希分条带的锁管理器。
 * 一次转账按账户号升序同时持有相关账户锁，杜绝环路等待（死锁）；
 * tryLock 超时抛 CONCURRENT_CONFLICT——语义是"此刻争用，应当重试"，
 * 与 INSUFFICIENT_FUNDS（确定性业务拒绝，重试无意义）严格区分。
 */
@Component
public class AccountLocks {

    /** 争用等待上限：超过即判定并发冲突，快速失败让调用方重试。 */
    private static final long LOCK_TIMEOUT_MS = 200L;

    private static final int STRIPES = 256;

    private final long lockTimeoutMs;

    private final Lock[] stripes;

    /** 生产默认：争用等待上限 200ms。 */
    public AccountLocks() {
        this(LOCK_TIMEOUT_MS);
    }

    /** 可指定等待上限（主要供测试用极小超时确定性触发争用分支）。 */
    public AccountLocks(long lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
        this.stripes = new Lock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    /** 在两个账户锁保护下执行；同账户转账会归一成一把锁（不会自锁）。 */
    public <T> T withAccountLocks(String firstAccountNo, String secondAccountNo, Supplier<T> action) {
        List<String> accountNos = new ArrayList<>(2);
        accountNos.add(firstAccountNo);
        if (!firstAccountNo.equals(secondAccountNo)) {
            accountNos.add(secondAccountNo);
        }
        accountNos.sort(Comparator.naturalOrder());

        List<Lock> acquired = new ArrayList<>(accountNos.size());
        try {
            for (String accountNo : accountNos) {
                Lock lock = lockFor(accountNo);
                boolean got;
                try {
                    got = lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LedgerException(ErrorCode.CONCURRENT_CONFLICT,
                            "interrupted while acquiring account lock: " + accountNo);
                }
                if (!got) {
                    throw new LedgerException(ErrorCode.CONCURRENT_CONFLICT,
                            "account lock contention timeout: " + accountNo);
                }
                acquired.add(lock);
            }
            return action.get();
        } finally {
            // 逆序释放
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    /** 账户号对应的条带锁。公开以便测试确定性占用锁、复现争用分支；业务代码请用 withAccountLocks。 */
    public Lock lockFor(String accountNo) {
        int index = Math.floorMod(accountNo.hashCode(), STRIPES);
        return stripes[index];
    }
}
