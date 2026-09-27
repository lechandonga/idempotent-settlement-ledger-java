package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerAccount;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.LedgerTransaction;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.IdempotencyRegistry;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.TransactionJournal;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 转账与冲正的应用服务。
 *
 * <p>记账规则（资产/客户余额视角：借增贷减）：
 * <ul>
 *   <li>一笔转账生成两条分录：借 收款方、贷 付款方，金额相等；</li>
 *   <li>付款方先检查余额（不足抛 {@link ErrorCode#INSUFFICIENT_FUNDS}，拒绝），
 *       再在同一组账户锁内完成“落流水 + 改余额”，锁外不可见中间态；</li>
 *   <li>锁获取超时抛 {@link ErrorCode#RETRYABLE_CONTENTION}，可安全重试；</li>
 *   <li>冲正用一笔方向相反的新交易表达，并标记原交易；冲正同样走幂等。</li>
 * </ul>
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransactionJournal journal;
    private final AccountLocks locks;
    private final IdempotencyRegistry idempotency;

    public TransferService(AccountRepository accounts, TransactionJournal journal,
                           AccountLocks locks, IdempotencyRegistry idempotency,
                           LedgerProperties properties) {
        this.accounts = accounts;
        this.journal = journal;
        this.locks = locks;
        this.idempotency = idempotency;
    }

    public LedgerTransaction getTransaction(String id) {
        return journal.findTransactionById(id)
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "交易不存在: " + id));
    }

    /** 请求内容指纹：只有参与业务语义的字段参与，reference 不参与。 */
    static String fingerprint(TransferCommand command) {
        return LedgerHasher.sha256Hex(
                "TRANSFER",
                nullToEmpty(command.fromAccountId()),
                nullToEmpty(command.toAccountId()),
                Long.toString(command.amount()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public TransferResult transfer(TransferCommand command) {
        validate(command);
        String fp = fingerprint(command);

        var execution = idempotency.execute(command.idempotencyKey(), fp, () ->
                locks.withLocks(command.fromAccountId(), command.toAccountId(),
                        () -> doTransfer(command)));
        return new TransferResult(execution.transactionId(), execution.replayed());
    }

    private void validate(TransferCommand command) {
        if (command == null) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "idempotencyKey 不能为空");
        }
        if (command.fromAccountId() == null || command.fromAccountId().isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "fromAccountId 不能为空");
        }
        if (command.toAccountId() == null || command.toAccountId().isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "toAccountId 不能为空");
        }
        if (command.fromAccountId().equals(command.toAccountId())) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "转出账户与转入账户不能相同: " + command.fromAccountId());
        }
        if (command.amount() <= 0) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "转账金额必须为正整数（最小货币单位）: " + command.amount());
        }
    }

    /** 在已持有双方账户锁的前提下执行真实入账。 */
    private String doTransfer(TransferCommand command) {
        LedgerAccount from = requireAccount(command.fromAccountId());
        LedgerAccount to = requireAccount(command.toAccountId());
        if (from.balance() < command.amount()) {
            // 业务拒绝：和锁超时的 RETRYABLE_CONTENTION 明确区分。
            throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                    "账户 " + from.id() + " 余额 " + from.balance()
                            + " 不足以支付 " + command.amount());
        }

        String txId = "TX-" + UUID.randomUUID();
        LedgerTransaction tx = new LedgerTransaction(txId, command.idempotencyKey(),
                from.id(), to.id(), command.amount(), command.reference(),
                null, null, System.currentTimeMillis());

        // 先落只追加流水（含哈希链），再改余额；同处锁内，对外原子可见。
        journal.post(tx, to.id(), from.id(), command.amount());
        accounts.applyDelta(from.id(), -command.amount());
        accounts.applyDelta(to.id(), command.amount());
        return txId;
    }

    private LedgerAccount requireAccount(String id) {
        return accounts.findById(id)
                .orElseThrow(() -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + id));
    }

    /**
     * 冲正：对原交易追加一笔反向交易（借原付款方、贷原收款方），
     * 并在流水上标记原交易已被冲回。冲正交易本身不可再被冲正。
     *
     * @param originalTransactionId 原交易号
     * @param idempotencyKey        冲正操作的幂等标识；为空时默认 REVERSAL-OF-原交易号
     */
    public ReversalResult reverse(String originalTransactionId, String idempotencyKey) {
        if (originalTransactionId == null || originalTransactionId.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "originalTransactionId 不能为空");
        }
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "REVERSAL-OF-" + originalTransactionId
                : idempotencyKey;
        String fp = LedgerHasher.sha256Hex("REVERSAL", originalTransactionId);

        var execution = idempotency.execute(key, fp, () ->
                doReverse(originalTransactionId, key));
        return new ReversalResult(execution.transactionId(),
                originalTransactionId, execution.replayed());
    }

    private String doReverse(String originalTransactionId, String key) {
        // 存在性先做一次快速检查（锁外）；
        // “是否已被冲正”的判定必须在账户锁内重读流水，避免并发双重冲正。
        LedgerTransaction preview = journal.findTransactionById(originalTransactionId)
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "交易不存在: " + originalTransactionId));
        if (preview.isReversal()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "冲正交易不能再次冲正: " + originalTransactionId);
        }

        String reversalId = "RV-" + UUID.randomUUID();
        return locks.withLocks(preview.fromAccountId(), preview.toAccountId(), () -> {
            LedgerTransaction original = journal.findTransactionById(originalTransactionId).orElseThrow();
            if (original.isReversed()) {
                throw new LedgerException(ErrorCode.TRANSACTION_ALREADY_REVERSED,
                        "交易已被冲正: " + originalTransactionId
                                + "，冲正交易: " + original.reversedById());
            }

            // 反向：原付款方收回资金（借），原收款方退回资金（贷）。
            // 原交易发生时已校验过付款方余额；冲正把双方恢复到此前状态，总额守恒。
            LedgerTransaction reversal = new LedgerTransaction(
                    reversalId, key,
                    original.toAccountId(), original.fromAccountId(),
                    original.amount(), "冲正 " + originalTransactionId,
                    originalTransactionId, null, System.currentTimeMillis());

            journal.post(reversal, original.fromAccountId(), original.toAccountId(),
                    original.amount());
            journal.markReversed(originalTransactionId, reversalId);
            accounts.applyDelta(original.fromAccountId(), original.amount());
            accounts.applyDelta(original.toAccountId(), -original.amount());
            return reversalId;
        });
    }
}
