package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.IdempotencyRegistry;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.TransactionJournal;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryAccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryAccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryIdempotencyRegistry;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.memory.InMemoryTransactionJournal;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配内存版记账组件，不依赖任何外部服务或真实支付通道。
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfiguration {

    @Bean
    public AccountRepository accountRepository() {
        return new InMemoryAccountRepository();
    }

    @Bean
    public TransactionJournal transactionJournal() {
        return new InMemoryTransactionJournal();
    }

    @Bean
    public AccountLocks accountLocks(LedgerProperties properties) {
        return new InMemoryAccountLocks(properties.getLockTimeoutMillis());
    }

    @Bean
    public IdempotencyRegistry idempotencyRegistry() {
        return new InMemoryIdempotencyRegistry();
    }
}
