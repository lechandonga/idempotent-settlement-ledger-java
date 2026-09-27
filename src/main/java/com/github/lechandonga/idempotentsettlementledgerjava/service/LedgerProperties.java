package com.github.lechandonga.idempotentsettlementledgerjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记账能力配置。
 */
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    /** 获取账户锁的最长等待毫秒数；超时按可重试的并发争用处理。 */
    private long lockTimeoutMillis = 200;

    /** 启动时是否写入演示账户。 */
    private boolean seedDemoData = true;

    public long getLockTimeoutMillis() {
        return lockTimeoutMillis;
    }

    public void setLockTimeoutMillis(long lockTimeoutMillis) {
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    public boolean isSeedDemoData() {
        return seedDemoData;
    }

    public void setSeedDemoData(boolean seedDemoData) {
        this.seedDemoData = seedDemoData;
    }
}
