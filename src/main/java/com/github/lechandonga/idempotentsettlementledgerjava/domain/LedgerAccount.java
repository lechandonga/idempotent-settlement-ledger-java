package com.github.lechandonga.idempotentsettlementledgerjava.domain;

/**
 * 账户。余额单位为最小整数货币单位（如分），余额不可变快照由仓储层生成。
 *
 * @param id      账户号
 * @param name    账户名称
 * @param balance 当前余额（借为正的资产视角下，转账扣款直接减少该值）
 * @param version 乐观/锁版本，供并发处理与核对使用
 */
public record LedgerAccount(String id, String name, long balance, long version) {
}
