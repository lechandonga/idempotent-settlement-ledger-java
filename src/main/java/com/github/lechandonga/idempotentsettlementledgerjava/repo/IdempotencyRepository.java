package com.github.lechandonga.idempotentsettlementledgerjava.repo;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.IdempotencyRecord;
import com.github.lechandonga.idempotentsettlementledgerjava.domain.IdempotencyStatus;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 幂等记录内存仓库。putIfAbsent 充当原子的"请求是否已见过"闸门。 */
@Repository
public class IdempotencyRepository {

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return Optional.ofNullable(records.get(idempotencyKey));
    }

    /**
     * 原子占位：键不存在时放入传入记录（通常 PROCESSING）并返回 Optional.empty()；
     * 已存在则返回已有记录，由调用方判断重放 / 冲突 / 进行中。
     */
    public Optional<IdempotencyRecord> putIfAbsent(IdempotencyRecord record) {
        IdempotencyRecord existing = records.putIfAbsent(record.getIdempotencyKey(), record);
        return Optional.ofNullable(existing);
    }

    /** 仅当当前仍为 PROCESSING 时，用 updater 产出的 COMPLETED 记录替换。 */
    public void complete(String idempotencyKey, Supplier<IdempotencyRecord> updater) {
        records.computeIfPresent(idempotencyKey, (key, current) ->
                current.getStatus() == IdempotencyStatus.PROCESSING ? updater.get() : current);
    }

    /** 可重试失败后释放占位，允许调用方用同键重试。 */
    public void delete(String idempotencyKey) {
        records.computeIfPresent(idempotencyKey, (key, current) ->
                current.getStatus() == IdempotencyStatus.PROCESSING ? null : current);
    }
}
