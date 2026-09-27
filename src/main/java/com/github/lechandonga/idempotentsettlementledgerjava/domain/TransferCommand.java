package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 转账请求。
 *
 * @param idempotencyKey 调用方幂等标识（必填）
 * @param fromAccountId  转出账户（必填）
 * @param toAccountId    转入账户（必填）
 * @param amount         金额（正整数，最小货币单位）
 * @param reference      业务备注（可空）
 */
public record TransferCommand(String idempotencyKey, String fromAccountId,
                              String toAccountId, long amount, String reference) {
}
