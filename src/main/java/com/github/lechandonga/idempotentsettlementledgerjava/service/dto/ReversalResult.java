package com.github.lechandonga.idempotentsettlementledgerjava.service.dto;

/** 冲正稳定结果。 */
public class ReversalResult {

    private String transferId;
    private String idempotencyKey;
    private boolean replayed;

    public ReversalResult() {
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
