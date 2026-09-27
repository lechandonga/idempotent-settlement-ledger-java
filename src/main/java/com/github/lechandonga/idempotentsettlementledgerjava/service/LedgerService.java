package com.github.lechandonga.idempotentsettlementledgerjava.service;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.Account;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.Direction;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.Entry;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.IdempotencyRecord;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.IdempotencyStatus;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.TransferStatus;
import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.AccountRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.EntryRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.repo.IdempotencyRepository;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.ReversalResult;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferCommand;
import com.github.lechandonga.idempotentsettlementledgerjava.service.dto.TransferResult;
import com.github.lechandonga.idempotentsettlementledgerjava.support.AccountLocks;
import com.github.lechandonga.idempotentsettlementledgerjava.support.Hashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记账服务：幂等转账、幂等冲正、流水核对。
 *
 * <p>判定顺序固定为：①参数校验（PARAM_*）②幂等闸门（IDEMPOTENCY_CONFLICT / 稳定重放 /
 * 进行中可重试）③账户锁内余额校验（INSUFFICIENT_FUNDS，确定性拒绝）。
 * 因此"参数错误 / 幂等冲突 / 余额不足 / 并发争用"四类失败对调用方可明确区分。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AccountLocks accountLocks;

    /** transferId -> 单据状态。状态变更在账户锁内完成。 */
    private final ConcurrentHashMap<String, TransferStatus> transferStatuses = new ConcurrentHashMap<>();

    public LedgerService(AccountRepository accountRepository,
                         EntryRepository entryRepository,
                         IdempotencyRepository idempotencyRepository,
                         AccountLocks accountLocks) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.accountLocks = accountLocks;
    }

    public void createAccount(String accountNo, BigDecimal initialBalance) {
        validateAccountNo(accountNo);
        BigDecimal balance = initialBalance == null ? BigDecimal.ZERO : initialBalance;
        if (balance.signum() < 0) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "initial balance must not be negative: " + balance);
        }
        accountRepository.create(accountNo, balance);
        log.info("account opened: no={}, initialBalance={}", accountNo, balance.toPlainString());
    }

    public BigDecimal getBalance(String accountNo) {
        return accountRepository.require(accountNo).getBalance();
    }

    /**
     * 幂等转账。
     * <ul>
     *   <li>同键同内容：只在首次真实入账，之后稳定重放同一 transferId/结果；</li>
     *   <li>同键不同内容：抛 IDEMPOTENCY_CONFLICT（与普通 PARAM_INVALID 区分）；</li>
     *   <li>同键并发进行中：抛 CONCURRENT_CONFLICT（可重试）；</li>
     *   <li>余额不足：抛 INSUFFICIENT_FUNDS（确定性拒绝，且结果同样被幂等记忆、稳定重放）。</li>
     * </ul>
     */
    public TransferResult transfer(TransferCommand command) {
        validateTransferCommand(command);

        String key = command.getIdempotencyKey();
        String fingerprint = fingerprintOf(command.getFromAccountNo(), command.getToAccountNo(),
                command.getAmount(), null);

        IdempotencyRecord placeholder = new IdempotencyRecord();
        placeholder.setIdempotencyKey(key);
        placeholder.setFingerprint(fingerprint);
        placeholder.setStatus(IdempotencyStatus.PROCESSING);

        IdempotencyRecord winner = idempotencyRepository.putIfAbsent(placeholder)
                .orElse(null);
        if (winner == null) {
            // 首次占位成功：执行业务
            try {
                TransferResult result = doTransfer(key, command);
                idempotencyRepository.complete(key, () -> completed(key, fingerprint,
                        result.getTransferId(), serialize(result), null));
                log.info("transfer executed: key={}, transferId={}, {} -> {}, amount={}",
                        key, result.getTransferId(), command.getFromAccountNo(),
                        command.getToAccountNo(), command.getAmount().toPlainString());
                return result;
            } catch (LedgerException e) {
                if (e.getErrorCode() == ErrorCode.CONCURRENT_CONFLICT) {
                    // 可重试：释放占位，调用方可用同键安全重试
                    idempotencyRepository.delete(key);
                } else {
                    // 确定性业务拒绝（余额不足等）：记忆结果，保证重放稳定
                    final LedgerException fe = e;
                    idempotencyRepository.complete(key, () -> completed(key, fingerprint,
                            null, null, fe.getErrorCode().name()));
                    log.info("transfer deterministically rejected: key={}, reason={}", key, e.getErrorCode());
                }
                throw e;
            }
        }

        // 键已存在：指纹不符 → 幂等冲突
        if (!fingerprint.equals(winner.getFingerprint())) {
            log.info("idempotency conflict: key={}, storedFingerprint={}, requestFingerprint={}",
                    key, winner.getFingerprint(), fingerprint);
            throw new LedgerException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key reused with a different request payload: " + key);
        }
        if (winner.getStatus() == IdempotencyStatus.PROCESSING) {
            log.info("idempotency key in-flight: key={}", key);
            throw new LedgerException(ErrorCode.CONCURRENT_CONFLICT,
                    "identical request is still being processed: " + key);
        }
        // 已完成：稳定重放（成功结果或确定性拒绝）
        if (winner.getErrorCode() != null) {
            log.info("replaying deterministic rejection: key={}, errorCode={}", key, winner.getErrorCode());
            throw new LedgerException(ErrorCode.valueOf(winner.getErrorCode()),
                    "replayed rejection for idempotency key: " + key);
        }
        TransferResult replayed = deserialize(winner.getResult(), TransferResult.class);
        replayed.setReplayed(true);
        log.info("transfer replayed: key={}, transferId={}", key, replayed.getTransferId());
        return replayed;
    }

    /** 真正的双分录记账：锁内做存在性/余额校验并原子更新余额与流水。 */
    private TransferResult doTransfer(String key, TransferCommand command) {
        String from = command.getFromAccountNo();
        String to = command.getToAccountNo();
        BigDecimal amount = command.getAmount();
        return accountLocks.withAccountLocks(from, to, () -> {
            Account debitAccount = accountRepository.require(from);
            Account creditAccount = accountRepository.require(to);
            if (debitAccount.getBalance().compareTo(amount) < 0) {
                throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                        "insufficient funds: account=" + from + ", balance="
                                + debitAccount.getBalance().toPlainString()
                                + ", requested=" + amount.toPlainString());
            }
            String transferId = newTransferId();
            Instant now = Instant.now();

            // 先写流水（append 串行且可能失败），再改余额；同锁内、无异常提交即一致。
            postPair(transferId, from, to, amount, now, null,
                    Direction.CREDIT, Direction.DEBIT);
            debitAccount.setBalance(debitAccount.getBalance().subtract(amount));
            creditAccount.setBalance(creditAccount.getBalance().add(amount));
            transferStatuses.put(transferId, TransferStatus.POSTED);

            TransferResult result = new TransferResult();
            result.setTransferId(transferId);
            result.setIdempotencyKey(key);
            result.setReplayed(false);
            return result;
        });
    }

    /**
     * 幂等冲正：对目标转账追加两条反向分录并回退余额，净发生额为零。
     * 转账不存在、已冲正均为确定性拒绝；冲正本身也走幂等闸门。
     */
    public ReversalResult reverse(String idempotencyKey, String transferId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "idempotencyKey is blank");
        }
        if (transferId == null || transferId.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "transferId is blank");
        }

        String key = idempotencyKey;
        String fingerprint = fingerprintOf(transferId, null, null, "REVERSAL");

        IdempotencyRecord placeholder = new IdempotencyRecord();
        placeholder.setIdempotencyKey(key);
        placeholder.setFingerprint(fingerprint);
        placeholder.setStatus(IdempotencyStatus.PROCESSING);

        IdempotencyRecord winner = idempotencyRepository.putIfAbsent(placeholder).orElse(null);
        if (winner == null) {
            try {
                ReversalResult result = doReverse(key, transferId);
                idempotencyRepository.complete(key, () -> completed(key, fingerprint,
                        transferId, serialize(result), null));
                log.info("reversal executed: key={}, transferId={}", key, transferId);
                return result;
            } catch (LedgerException e) {
                if (e.getErrorCode() == ErrorCode.CONCURRENT_CONFLICT) {
                    idempotencyRepository.delete(key);
                } else {
                    final LedgerException fe = e;
                    idempotencyRepository.complete(key, () -> completed(key, fingerprint,
                            transferId, null, fe.getErrorCode().name()));
                    log.info("reversal deterministically rejected: key={}, reason={}", key, e.getErrorCode());
                }
                throw e;
            }
        }

        if (!fingerprint.equals(winner.getFingerprint())) {
            log.info("reversal idempotency conflict: key={}", key);
            throw new LedgerException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key reused with a different request payload: " + key);
        }
        if (winner.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new LedgerException(ErrorCode.CONCURRENT_CONFLICT,
                    "identical reversal is still being processed: " + key);
        }
        if (winner.getErrorCode() != null) {
            throw new LedgerException(ErrorCode.valueOf(winner.getErrorCode()),
                    "replayed rejection for idempotency key: " + key);
        }
        ReversalResult replayed = deserialize(winner.getResult(), ReversalResult.class);
        replayed.setReplayed(true);
        log.info("reversal replayed: key={}, transferId={}", key, transferId);
        return replayed;
    }

    private ReversalResult doReverse(String key, String transferId) {
        List<Entry> original = entryRepository.findByTransfer(transferId);
        if (original.isEmpty()) {
            throw new LedgerException(ErrorCode.TRANSFER_NOT_FOUND, "transfer not found: " + transferId);
        }
        // 取原始转账的双方账户与金额（原始两条分录金额相等）
        Entry firstCredit = original.stream()
                .filter(e -> e.getReversalOf() == null && e.getDirection() == Direction.CREDIT)
                .findFirst()
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSFER_NOT_FOUND,
                        "original credit entry missing: " + transferId));
        String from = firstCredit.getAccountNo();
        String to = original.stream()
                .filter(e -> e.getReversalOf() == null && e.getDirection() == Direction.DEBIT)
                .map(Entry::getAccountNo).findFirst()
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSFER_NOT_FOUND,
                        "original debit entry missing: " + transferId));
        BigDecimal amount = firstCredit.getAmount();
        String reversalId = newTransferId();

        return accountLocks.withAccountLocks(from, to, () -> {
            // 锁内复查状态，防止并发重复冲正
            TransferStatus status = transferStatuses.get(transferId);
            if (status == null) {
                throw new LedgerException(ErrorCode.TRANSFER_NOT_FOUND, "transfer not found: " + transferId);
            }
            if (status == TransferStatus.REVERSED
                    || entryRepository.findByReversalOf(transferId).isPresent()) {
                throw new LedgerException(ErrorCode.ALREADY_REVERSED,
                        "transfer already reversed: " + transferId);
            }
            Account debitAccount = accountRepository.require(from);
            Account creditAccount = accountRepository.require(to);

            // 反向分录：原转出方补回（DEBIT），原收款方扣减（CREDIT）
            postPair(reversalId, from, to, amount, Instant.now(), transferId,
                    Direction.DEBIT, Direction.CREDIT);
            debitAccount.setBalance(debitAccount.getBalance().add(amount));
            creditAccount.setBalance(creditAccount.getBalance().subtract(amount));
            transferStatuses.put(transferId, TransferStatus.REVERSED);

            ReversalResult result = new ReversalResult();
            result.setTransferId(transferId);
            result.setIdempotencyKey(key);
            result.setReplayed(false);
            return result;
        });
    }

    /**
     * 追加一对金额相等、方向相反的分录；seq 连续、哈希链接龙。
     * firstAccount 记 firstDirection，secondAccount 记 secondDirection。
     */
    private void postPair(String pairId, String firstAccount, String secondAccount,
                          BigDecimal amount, Instant now, String reversalOf,
                          Direction firstDirection, Direction secondDirection) {
        postEntry(pairId, firstAccount, firstDirection, amount, now, reversalOf);
        postEntry(pairId, secondAccount, secondDirection, amount, now, reversalOf);
    }

    private void postEntry(String transferId, String accountNo, Direction direction,
                           BigDecimal amount, Instant now, String reversalOf) {
        Entry entry = new Entry();
        entry.setTransferId(transferId);
        entry.setAccountNo(accountNo);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setCreatedAt(now);
        entry.setReversalOf(reversalOf);
        entryRepository.append(entry);
    }

    // ---------------- 核对 ----------------

    /**
     * 核对全部账目，结论分四类维度：
     * <ol>
     *   <li>哈希链：seq 连续、prevHash 接龙、hash 重算一致——任一条被外部改动即 TAMPERED；</li>
     *   <li>余额：逐户按 初始余额 + 借 - 贷 重算，与账户当前余额比对；</li>
     *   <li>守恒：全部借方发生额合计等于贷方发生额合计；</li>
     *   <li>双分录：每个 transferId 必须恰好两条金额相等、方向相反的分录。</li>
     * </ol>
     */
    public ReconciliationReport reconcile() {
        ReconciliationReport report = new ReconciliationReport();
        List<String> problems = new ArrayList<>();

        List<Entry> entries = entryRepository.findAll();

        // ① 哈希链
        String expectedPrev = "0".repeat(64);
        long expectedSeq = 1L;
        for (Entry e : entries) {
            if (e.getSeq() != expectedSeq) {
                problems.add(String.format("seq gap: expected=%d, actual=%d", expectedSeq, e.getSeq()));
            }
            if (!expectedPrev.equals(e.getPrevHash())) {
                problems.add("hash chain broken at seq=" + e.getSeq());
            }
            String recomputed = Hashes.sha256(canonicalOf(e));
            if (!recomputed.equals(e.getHash())) {
                problems.add("entry tampered: seq=" + e.getSeq() + ", transferId=" + e.getTransferId());
            }
            expectedPrev = e.getHash();
            expectedSeq++;
        }

        // ④ 双分录配对（按 transferId 分组）
        Map<String, List<Entry>> byTransfer = new LinkedHashMap<>();
        for (Entry e : entries) {
            byTransfer.computeIfAbsent(e.getTransferId(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Entry>> group : byTransfer.entrySet()) {
            List<Entry> pair = group.getValue();
            if (pair.size() != 2) {
                problems.add("transfer " + group.getKey() + " has " + pair.size() + " entries, expected 2");
                continue;
            }
            Entry a = pair.get(0);
            Entry b = pair.get(1);
            if (a.getDirection() == b.getDirection()) {
                problems.add("transfer " + group.getKey() + " entries share direction " + a.getDirection());
            }
            if (a.getAmount().compareTo(b.getAmount()) != 0) {
                problems.add("transfer " + group.getKey() + " entry amounts differ");
            }
            if (a.getAccountNo().equals(b.getAccountNo())) {
                problems.add("transfer " + group.getKey() + " entries hit the same account");
            }
            if (!java.util.Objects.equals(a.getReversalOf(), b.getReversalOf())) {
                problems.add("transfer " + group.getKey() + " reversal markers differ");
            }
        }

        // ② 逐户流水重算余额
        Map<String, BigDecimal> debitSum = new LinkedHashMap<>();
        Map<String, BigDecimal> creditSum = new LinkedHashMap<>();
        for (Entry e : entries) {
            debitSum.merge(e.getAccountNo(),
                    e.getDirection() == Direction.DEBIT ? e.getAmount() : BigDecimal.ZERO, BigDecimal::add);
            creditSum.merge(e.getAccountNo(),
                    e.getDirection() == Direction.CREDIT ? e.getAmount() : BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal grandDebit = BigDecimal.ZERO;
        BigDecimal grandCredit = BigDecimal.ZERO;
        for (Account account : accountRepository.findAll()) {
            BigDecimal td = debitSum.getOrDefault(account.getAccountNo(), BigDecimal.ZERO);
            BigDecimal tc = creditSum.getOrDefault(account.getAccountNo(), BigDecimal.ZERO);
            BigDecimal computed = account.getInitialBalance().add(td).subtract(tc);
            grandDebit = grandDebit.add(td);
            grandCredit = grandCredit.add(tc);

            ReconciliationReport.AccountDetail detail = new ReconciliationReport.AccountDetail();
            detail.setAccountNo(account.getAccountNo());
            detail.setLedgerBalance(account.getBalance());
            detail.setComputedBalance(computed);
            detail.setTotalDebit(td);
            detail.setTotalCredit(tc);
            report.getAccountDetails().add(detail);

            if (computed.compareTo(account.getBalance()) != 0) {
                problems.add(String.format("balance mismatch for %s: ledger=%s, computedFromEntries=%s",
                        account.getAccountNo(), account.getBalance().toPlainString(),
                        computed.toPlainString()));
            }
            if (account.getBalance().signum() < 0) {
                problems.add("negative balance for " + account.getAccountNo()
                        + ": " + account.getBalance().toPlainString());
            }
        }

        // 流水里出现了仓库中不存在的账户
        for (String accountNo : debitSum.keySet()) {
            if (accountRepository.find(accountNo).isEmpty()) {
                problems.add("entries reference unknown account: " + accountNo);
            }
        }

        // ③ 全局借贷守恒
        if (grandDebit.compareTo(grandCredit) != 0) {
            problems.add(String.format("debit/credit not balanced: totalDebit=%s, totalCredit=%s",
                    grandDebit.toPlainString(), grandCredit.toPlainString()));
        }

        report.setProblems(problems);
        report.setBalanced(problems.isEmpty());
        log.info("reconciliation done: balanced={}, problems={}", report.isBalanced(), problems.size());
        return report;
    }

    /** 与 EntryRepository 中一致的哈希规范串（核对时重算用）。 */
    private String canonicalOf(Entry e) {
        return e.getSeq() + "|"
                + e.getPrevHash() + "|"
                + e.getTransferId() + "|"
                + e.getAccountNo() + "|"
                + e.getDirection() + "|"
                + e.getAmount().stripTrailingZeros().toPlainString() + "|"
                + (e.getReversalOf() == null ? "" : e.getReversalOf());
    }

    // ---------------- 辅助方法 ----------------

    private void validateTransferCommand(TransferCommand c) {
        if (c == null) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "request body is required");
        }
        validateAccountNo(c.getFromAccountNo());
        validateAccountNo(c.getToAccountNo());
        if (c.getFromAccountNo().equals(c.getToAccountNo())) {
            throw new LedgerException(ErrorCode.SAME_ACCOUNT,
                    "from and to accounts must differ: " + c.getFromAccountNo());
        }
        if (c.getIdempotencyKey() == null || c.getIdempotencyKey().isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "idempotencyKey is blank");
        }
        if (c.getAmount() == null || c.getAmount().signum() <= 0) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "amount must be positive, got: " + c.getAmount());
        }
        if (c.getAmount().stripTrailingZeros().scale() > 2) {
            throw new LedgerException(ErrorCode.PARAM_INVALID,
                    "amount scale must not exceed 2: " + c.getAmount().toPlainString());
        }
    }

    private void validateAccountNo(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "accountNo is blank");
        }
        if (accountNo.length() > 64) {
            throw new LedgerException(ErrorCode.PARAM_INVALID, "accountNo too long: " + accountNo);
        }
    }

    private String newTransferId() {
        return "T" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 业务内容指纹：纳入全部决定入账结果的字段；intent 区分转账/冲正命名空间。 */
    private String fingerprintOf(String a, String b, BigDecimal amount, String intent) {
        String canonical = (intent == null ? "TRANSFER" : intent) + "|"
                + (a == null ? "" : a) + "|"
                + (b == null ? "" : b) + "|"
                + (amount == null ? "" : amount.stripTrailingZeros().toPlainString());
        return Hashes.sha256(canonical);
    }

    private IdempotencyRecord completed(String key, String fingerprint, String transferId,
                                        String result, String errorCode) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setIdempotencyKey(key);
        r.setFingerprint(fingerprint);
        r.setStatus(IdempotencyStatus.COMPLETED);
        r.setTransferId(transferId);
        r.setResult(result);
        r.setErrorCode(errorCode);
        return r;
    }

    /** 极简 JSON 序列化（仅本服务两个结果 DTO，字段固定，避免引入额外依赖）。 */
    private String serialize(Object result) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (result instanceof TransferResult t) {
            map.put("transferId", t.getTransferId());
            map.put("idempotencyKey", t.getIdempotencyKey());
            map.put("replayed", t.isReplayed());
        } else if (result instanceof ReversalResult r) {
            map.put("transferId", r.getTransferId());
            map.put("idempotencyKey", r.getIdempotencyKey());
            map.put("replayed", r.isReplayed());
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Boolean) {
                sb.append(v);
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String json, Class<T> type) {
        // 手写极简解析：{"transferId":"...","idempotencyKey":"...","replayed":false}
        Map<String, String> values = new LinkedHashMap<>();
        String body = json.substring(1, json.length() - 1);
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            String k = pair.substring(0, idx).replace("\"", "");
            String v = pair.substring(idx + 1);
            values.put(k, v);
        }
        if (type == TransferResult.class) {
            TransferResult t = new TransferResult();
            t.setTransferId(unescape(values.get("transferId")));
            t.setIdempotencyKey(unescape(values.get("idempotencyKey")));
            t.setReplayed(Boolean.parseBoolean(values.get("replayed")));
            return (T) t;
        }
        ReversalResult r = new ReversalResult();
        r.setTransferId(unescape(values.get("transferId")));
        r.setIdempotencyKey(unescape(values.get("idempotencyKey")));
        r.setReplayed(Boolean.parseBoolean(values.get("replayed")));
        return (T) r;
    }

    private String unescape(String raw) {
        if (raw == null || raw.equals("null")) {
            return null;
        }
        return raw.replace("\"", "");
    }
}
