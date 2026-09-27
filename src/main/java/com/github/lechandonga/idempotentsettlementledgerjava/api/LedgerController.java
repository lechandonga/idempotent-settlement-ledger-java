package com.github.lechandonga.idempotentsettlementledgerjava.api;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerTransaction;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.service.AccountService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.TransferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地记账 HTTP 接口。错误响应统一见 {@link LedgerExceptionHandler}。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final AccountService accounts;
    private final TransferService transfers;
    private final ReconciliationService reconciliation;

    public LedgerController(AccountService accounts, TransferService transfers,
                            ReconciliationService reconciliation) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/accounts")
    public LedgerAccount createAccount(@RequestBody CreateAccountRequest request) {
        if (request == null) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        long initial = request.initialBalance() == null ? 0L : request.initialBalance();
        return accounts.createAccount(request.id(), request.name(), initial);
    }

    @GetMapping("/accounts")
    public List<LedgerAccount> listAccounts() {
        return accounts.listAccounts();
    }

    @GetMapping("/accounts/{id}")
    public LedgerAccount getAccount(@PathVariable String id) {
        return accounts.getAccount(id);
    }

    @PostMapping("/transfers")
    public TransferResult transfer(@RequestBody TransferCommand command) {
        return transfers.transfer(command);
    }

    @GetMapping("/transactions/{id}")
    public LedgerTransaction getTransaction(@PathVariable String id) {
        return transfers.getTransaction(id);
    }

    @PostMapping("/transactions/{id}/reversals")
    public ReversalResult reverse(@PathVariable String id,
                                  @RequestBody(required = false) ReversalRequest request) {
        String key = request == null ? null : request.idempotencyKey();
        return transfers.reverse(id, key);
    }

    @PostMapping("/reconcile")
    public ReconciliationReport reconcile() {
        return reconciliation.reconcile();
    }

    public record CreateAccountRequest(String id, String name, Long initialBalance) {
    }

    public record ReversalRequest(String idempotencyKey) {
    }
}
