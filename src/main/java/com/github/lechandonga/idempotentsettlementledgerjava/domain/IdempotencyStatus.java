package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/** 幂等请求的生命周期状态。 */
public enum IdempotencyStatus {
    /** 已占位、业务执行中：重复请求此刻应得到可重试的并发冲突。 */
    PROCESSING,
    /** 已完成并持久化了稳定结果（成功或确定性的业务拒绝）。 */
    COMPLETED
}
