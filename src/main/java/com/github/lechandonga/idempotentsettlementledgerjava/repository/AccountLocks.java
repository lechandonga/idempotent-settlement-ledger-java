package com.github.lechandonga.idempotentsettlementledgerjava.repository;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * 账户锁：按账户号排序后依次加锁，避免多账户操作时的死锁；
 * 获取超时统一转成 RETRYABLE_CONTENTION，以便和余额不足区分。
 */
public interface AccountLocks {

    <T> T withLocks(String firstAccountId, String secondAccountId, Supplier<T> action);

    <T> T withLock(String accountId, Supplier<T> action);

    /** 按字典序一次性持有全部给定账户锁后执行（核对用）。 */
    <T> T withAllLocks(Collection<String> accountIds, Supplier<T> action);

    /** 测试支持：占用某账户锁直到 close，用于制造争用。 */
    AutoCloseable acquireForTesting(String accountId) throws InterruptedException;
}
