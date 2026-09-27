package com.github.lechandonga.idempotentsettlementledgerjava.service.dto;

import java.math.BigDecimal;

/** 转账请求。idempotencyKey 由调用方提供，同键重放安全。 */
public class TransferCommand {

    private String idempotencyKey;
    private String fromAccountNo;
    private String toAccountNo;
    private BigDecimal amount;

    public TransferCommand() {
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFromAccountNo() {
        return fromAccountNo;
    }

    public void setFromAccountNo(String fromAccountNo) {
        this.fromAccountNo = fromAccountNo;
    }

    public String getToAccountNo() {
        return toAccountNo;
    }

    public void setToAccountNo(String toAccountNo) {
        this.toAccountNo = toAccountNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
