package com.github.lechandonga.idempotentsettlementledgerjava.repository.memory;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountLocks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 每账户一把 {@link ReentrantLock}。
 *
 * <p>并发约定：
 * <ul>
 *   <li>涉及两个账户的操作始终按账户号字典序依次加锁，所有事务遵循同一顺序，
 *       因此不会出现 AB/BA 死锁；</li>
 *   <li>使用 {@code tryLock(timeout)}，拿不到锁视为并发争用，抛
 *       {@link ErrorCode#RETRYABLE_CONTENTION}（可重试），与余额不足
 *       （{@link ErrorCode#INSUFFICIENT_FUNDS}，拒绝）明确区分；</li>
 *   <li>同一账户重复加锁是可重入的，冲正等场景安全。</li>
 * </ul>
 */
public class InMemoryAccountLocks implements AccountLocks {

    private final long timeoutMillis;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public InMemoryAccountLocks(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    private ReentrantLock lockFor(String accountId) {
        return locks.computeIfAbsent(accountId, k -> new ReentrantLock(true));
    }

    @Override
    public <T> T withLocks(String firstAccountId, String secondAccountId, Supplier<T> action) {
        List<String> ordered = new java.util.TreeSet<>(List.of(firstAccountId, secondAccountId))
                .stream().toList();
        List<ReentrantLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (String accountId : ordered) {
                ReentrantLock lock = lockFor(accountId);
                try {
                    if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        throw new LedgerException(ErrorCode.RETRYABLE_CONTENTION,
                                "账户 " + accountId + " 存在并发争用，未获取到锁，请重试");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LedgerException(ErrorCode.RETRYABLE_CONTENTION,
                            "等待账户锁时被中断: " + accountId, e);
                }
                acquired.add(lock);
            }
            return action.get();
        } finally {
            // 逆序释放。
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    @Override
    public <T> T withLock(String accountId, Supplier<T> action) {
        return withLocks(accountId, accountId, action);
    }

    @Override
    public <T> T withAllLocks(Collection<String> accountIds, Supplier<T> action) {
        List<String> ordered = accountIds.stream().distinct().sorted().toList();
        List<ReentrantLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (String accountId : ordered) {
                ReentrantLock lock = lockFor(accountId);
                try {
                    if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        throw new LedgerException(ErrorCode.RETRYABLE_CONTENTION,
                                "账户 " + accountId + " 存在并发争用，未获取到锁，请重试");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LedgerException(ErrorCode.RETRYABLE_CONTENTION,
                            "等待账户锁时被中断: " + accountId, e);
                }
                acquired.add(lock);
            }
            return action.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    @Override
    public AutoCloseable acquireForTesting(String accountId) throws InterruptedException {
        ReentrantLock lock = lockFor(accountId);
        if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new LedgerException(ErrorCode.RETRYABLE_CONTENTION,
                    "测试占用账户锁失败: " + accountId);
        }
        return () -> lock.unlock();
    }
}
