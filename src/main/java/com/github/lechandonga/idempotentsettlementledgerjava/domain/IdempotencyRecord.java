package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 按调用方幂等键记录的请求指纹与稳定结果。
 * result 保存成功结果快照；errorCode 保存确定性业务拒绝（如余额不足）的错误码。
 */
public class IdempotencyRecord {

    private String idempotencyKey;
    private /** 请求内容指纹，冲突检测用。 */ String fingerprint;
    private IdempotencyStatus status;
    private /** 成功时产生的转账号/冲正关联转账号。 */ String transferId;
    private /** 成功结果 JSON 快照。 */ String result;
    private /** 确定性业务拒绝的错误码（可安全重放）。 */ String errorCode;

    public IdempotencyRecord() {
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
