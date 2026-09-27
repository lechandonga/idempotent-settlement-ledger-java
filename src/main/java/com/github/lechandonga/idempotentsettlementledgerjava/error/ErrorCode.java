package com.github.lechandonga.idempotentsettlementledgerjava.error;

/**
 * 业务错误码。不同类别让调用方可判断该重试还是该拒绝：
 * PARAM_* 确定性参数错误（不要重试）；
 * IDEMPOTENCY_CONFLICT 同键不同内容（不要重试，需更换键）；
 * INSUFFICIENT_FUNDS 确定性业务拒绝（不要重试）；
 * CONCURRENT_CONFLICT 争用失败（应当重试）。
 */
public enum ErrorCode {
    PARAM_INVALID,
    ACCOUNT_NOT_FOUND,
    SAME_ACCOUNT,
    IDEMPOTENCY_CONFLICT,
    INSUFFICIENT_FUNDS,
    CONCURRENT_CONFLICT,
    TRANSFER_NOT_FOUND,
    ALREADY_REVERSED
}
