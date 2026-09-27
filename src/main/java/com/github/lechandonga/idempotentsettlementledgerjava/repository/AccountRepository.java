package com.github.lechandonga.idempotentsettlementledgerjava.repository;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;

import java.util.List;
import java.util.Optional;

/**
 * 账户仓储（内存实现）。账户的创建与余额变动由服务层在账户锁内调用。
 */
public interface AccountRepository {

    LedgerAccount create(String id, String name, long initialBalance);

    Optional<LedgerAccount> findById(String id);

    List<LedgerAccount> findAll();

    /** 在账户锁内调用：余额按 delta 增减，版本号加一。 */
    LedgerAccount applyDelta(String accountId, long delta);

    /** 仅供核对/测试：直接覆盖余额，用于模拟外部篡改。 */
    LedgerAccount overwriteBalanceForVerification(String accountId, long newBalance);
}
