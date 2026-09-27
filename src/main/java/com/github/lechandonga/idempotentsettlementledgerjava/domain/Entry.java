package com.github.lechandonga.idempotentsettlementledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条会计流水分录（单边）。一次转账固定产生两条金额相等、方向相反的分录；
 * 一次冲正再产生两条反向分录。seq 全局严格递增，prevHash/hash 构成防篡改哈希链。
 */
public class Entry {

    private long seq;
    private String transferId;
    private String accountNo;
    private Direction direction;
    private BigDecimal amount;
    private Instant createdAt;
    private /** 冲正目标转账号；普通入账为 null。 */ String reversalOf;
    private String prevHash;
    private String hash;

    public Entry() {
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getReversalOf() {
        return reversalOf;
    }

    public void setReversalOf(String reversalOf) {
        this.reversalOf = reversalOf;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
