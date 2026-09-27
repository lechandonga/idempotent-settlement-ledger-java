package com.github.lechandonga.idempotentsettlementledgerjava.repository.memory;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerConstants;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存账户仓储。
 *
 * <p>{@link #applyDelta} 与 {@link #overwriteBalanceForVerification}
 * 约定由调用方在对应账户锁内调用，因此这里只做单键上的原子更新；
 * 返回的 {@link LedgerAccount} 是不可变快照。
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<String, LedgerAccount> accounts = new ConcurrentHashMap<>();

    @Override
    public LedgerAccount create(String id, String name, long initialBalance) {
        LedgerAccount created = new LedgerAccount(id, name, initialBalance, 0);
        LedgerAccount existing = accounts.putIfAbsent(id, created);
        if (existing != null) {
            throw new LedgerException(ErrorCode.DUPLICATE_ACCOUNT,
                    "账户已存在: " + id);
        }
        return created;
    }

    @Override
    public Optional<LedgerAccount> findById(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public List<LedgerAccount> findAll() {
        return accounts.values().stream()
                .sorted(java.util.Comparator.comparing(LedgerAccount::id))
                .toList();
    }

    @Override
    public LedgerAccount applyDelta(String accountId, long delta) {
        // compute 在 ConcurrentHashMap 的锁桶内原子完成读改写，杜绝丢失更新。
        LedgerAccount updated = accounts.compute(accountId, (id, current) -> {
            if (current == null) {
                if (LedgerConstants.OPENING_ACCOUNT_ID.equals(id)) {
                    // 开业科目在首次开户入金时惰性创建。
                    return new LedgerAccount(id, "系统开业配平科目", delta, 1);
                }
                return null;
            }
            return new LedgerAccount(current.id(), current.name(),
                    current.balance() + delta, current.version() + 1);
        });
        if (updated == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + accountId);
        }
        return updated;
    }

    @Override
    public LedgerAccount overwriteBalanceForVerification(String accountId, long newBalance) {
        LedgerAccount updated = accounts.computeIfPresent(accountId, (id, current) ->
                new LedgerAccount(current.id(), current.name(), newBalance, current.version()));
        if (updated == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + accountId);
        }
        return updated;
    }
}
