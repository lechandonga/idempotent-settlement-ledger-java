package com.github.lechandonga.idempotentsettlementledgerjava.service.dto;

/** 转账稳定结果：无论首次执行还是幂等重放，同键返回内容一致。 */
public class TransferResult {

    private String transferId;
    private String idempotencyKey;
    private boolean replayed;

    public TransferResult() {
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public void setReplayed(boolean replayed) {
        this.replayed = replayed;
    }
}
