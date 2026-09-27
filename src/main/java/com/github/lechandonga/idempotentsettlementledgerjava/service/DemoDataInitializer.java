package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时写入本地演示账户（可用 ledger.seed-demo-data=false 关闭），方便直接验证。
 * 必须经 {@link AccountService} 开户，初始余额才会以双分录入流水。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final LedgerProperties properties;

    public DemoDataInitializer(AccountRepository accounts, AccountService accountService,
                               LedgerProperties properties) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedDemoData()) {
            return;
        }
        createIfAbsent("A", "演示账户A", 100_000L);
        createIfAbsent("B", "演示账户B", 100_000L);
        createIfAbsent("C", "演示账户C", 0L);
    }

    private void createIfAbsent(String id, String name, long balance) {
        if (accounts.findById(id).isEmpty()) {
            accountService.createAccount(id, name, balance);
        }
    }
}
