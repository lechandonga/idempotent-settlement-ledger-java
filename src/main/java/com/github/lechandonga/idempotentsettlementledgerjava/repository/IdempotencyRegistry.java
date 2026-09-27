package com.github.lechandonga.idempotentsettlementledgerjava.repository;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等记录登记册。
 *
 * 语义：
 * - 新 key：进入处理，处理结束（成功或业务失败）后结果被记录；
 * - 相同 key + 相同指纹：等待首次处理完成并回放同一结果，不重复入账；
 * - 相同 key + 不同指纹：直接拒绝 IDEMPOTENCY_CONFLICT，与普通参数错误区分。
 */
public interface IdempotencyRegistry {

    /**
     * 在幂等保护下执行一次操作。
     *
     * @param idempotencyKey 调用方幂等标识
     * @param fingerprint    请求内容指纹（参与冲突判定的字段哈希）
     * @param action         首次提交时执行的真实业务动作，返回交易号
     * @return 执行结果，包含交易号以及是否为重放
     */
    IdempotentExecution execute(String idempotencyKey, String fingerprint, Supplier<String> action);

    /** 已完成的幂等结果（供查询/回放使用）。 */
    Optional<IdempotencyOutcome> peek(String idempotencyKey);
}
