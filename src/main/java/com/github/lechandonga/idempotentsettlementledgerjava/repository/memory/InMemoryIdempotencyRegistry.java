package com.github.lechandonga.idempotentsettlementledgerjava.repository.memory;

import com.github.lechandonga.idempotentsettlementledgerjava.error.ErrorCode;
import com.github.lechandonga.idempotentsettlementledgerjava.error.LedgerException;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.IdempotentExecution;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.IdempotencyOutcome;
import com.github.lechandonga.idempotentsettlementledgerjava.repository.IdempotencyRegistry;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 内存幂等登记册。
 *
 * <p>每个幂等键对应一个槽位，槽位记录请求内容指纹与处理结果：
 * <ul>
 *   <li>首个请求原子地占位并执行真实动作；只有成功才发布结果，
 *       失败（含可重试争用、余额不足等）会移除槽位，保证调用方可用同一键改正后重试；</li>
 *   <li>并发的相同请求等待首个请求完成：成功则回放同一结果（绝不二次入账），
 *       首个失败则自己重新占位执行；</li>
 *   <li>同键但指纹不同（请求内容不同）立即拒绝
 *       {@link ErrorCode#IDEMPOTENCY_CONFLICT}，
 *       与 {@link ErrorCode#PARAM_INVALID} 普通参数错误明确区分。</li>
 * </ul>
 */
public class InMemoryIdempotencyRegistry implements IdempotencyRegistry {

    private static final class Slot {
        final String fingerprint;
        final CompletableFuture<String> future = new CompletableFuture<>();

        Slot(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public IdempotentExecution execute(String idempotencyKey, String fingerprint, Supplier<String> action) {
        while (true) {
            Slot mine = new Slot(fingerprint);
            Slot existing = slots.putIfAbsent(idempotencyKey, mine);

            if (existing == null) {
                // 首个提交者：执行真实动作。失败时移除自己的槽位并异常完成等待者。
                String transactionId;
                try {
                    transactionId = action.get();
                } catch (RuntimeException ex) {
                    slots.remove(idempotencyKey, mine);
                    mine.future.completeExceptionally(ex);
                    throw ex;
                }
                mine.future.complete(transactionId);
                return new IdempotentExecution(transactionId, false);
            }

            // 已存在该键：先做内容冲突判定，再等待首个请求的结果。
            if (!existing.fingerprint.equals(fingerprint)) {
                throw new LedgerException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "幂等标识 " + idempotencyKey + " 已被内容不同的请求占用，拒绝重复提交");
            }
            try {
                String transactionId = existing.future.join();
                return new IdempotentExecution(transactionId, true);
            } catch (java.util.concurrent.CompletionException e) {
                // 首个请求失败：下一轮重新占位，自己尝试执行。
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public Optional<IdempotencyOutcome> peek(String idempotencyKey) {
        Slot slot = slots.get(idempotencyKey);
        if (slot == null || !slot.future.isDone()
                || slot.future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.of(IdempotencyOutcome.success(slot.future.join()));
    }
}
