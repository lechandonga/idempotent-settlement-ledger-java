package com.github.lechandonga.idempotentsettlementledgerjava.web;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.service.LedgerService;
import com.github.lechandonga.idempotentsettlementledgerjava.service.ReconciliationReport;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本地记账 REST 接口。 */
@RestController
@RequestMapping("/ledger")
public class LedgerController {

    private static final Logger log = LoggerFactory.getLogger(LedgerController.class);

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 开户：{"initialBalance": 1000.00}，缺省为 0。 */
    @PostMapping("/accounts/{accountNo}")
    public Map<String, Object> createAccount(@PathVariable String accountNo,
                                             @RequestBody(required = false) Map<String, Object> body) {
        BigDecimal initial = initialBalanceOf(body);
        log.info("HTTP createAccount: accountNo={}, initialBalance={}", accountNo, initial.toPlainString());
        ledgerService.createAccount(accountNo, initial);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountNo", accountNo);
        resp.put("balance", initial);
        return resp;
    }

    @GetMapping("/accounts/{accountNo}")
    public Map<String, Object> getAccount(@PathVariable String accountNo) {
        BigDecimal balance = ledgerService.getBalance(accountNo);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountNo", accountNo);
        resp.put("balance", balance);
        return resp;
    }

    /** 转账：{"idempotencyKey":"k1","fromAccountNo":"A","toAccountNo":"B","amount":10.00} */
    @PostMapping("/transfers")
    public TransferResult transfer(@RequestBody TransferCommand command) {
        log.info("HTTP transfer: key={}, {} -> {}, amount={}",
                command == null ? null : command.getIdempotencyKey(),
                command == null ? null : command.getFromAccountNo(),
                command == null ? null : command.getToAccountNo(),
                command == null || command.getAmount() == null ? null : command.getAmount().toPlainString());
        return ledgerService.transfer(command);
    }

    /** 冲正：{"idempotencyKey":"r1"}；同键安全重放。 */
    @PostMapping("/transfers/{transferId}/reversal")
    public ReversalResult reverse(@PathVariable String transferId,
                                  @RequestBody Map<String, String> body) {
        String key = body == null ? null : body.get("idempotencyKey");
        log.info("HTTP reversal: key={}, transferId={}", key, transferId);
        return ledgerService.reverse(key, transferId);
    }

    @GetMapping("/reconcile")
    public ReconciliationReport reconcile() {
        log.info("HTTP reconcile");
        return ledgerService.reconcile();
    }

    private static BigDecimal initialBalanceOf(Map<String, Object> body) {
        if (body == null) {
            return BigDecimal.ZERO;
        }
        Object raw = body.get("initialBalance");
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal value = new BigDecimal(raw.toString());
            if (value.signum() < 0) {
                throw new LedgerException(ErrorCode.PARAM_INVALID, "initialBalance must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "initialBalance not a number: " + raw);
        }
    }
}
