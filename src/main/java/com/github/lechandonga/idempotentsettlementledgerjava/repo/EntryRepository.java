package com.github.lechandonga.idempotentsettlementledgerjava.repo;

import com.github.lechandonga.idempotentsettlementledgerjava.domain.Entry;
import com.github.lechandonga.idempotentsettlementledgerjava.support.Hashes;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 流水分录内存仓库。append 串行化：seq 全局严格递增，prevHash 指向上一条 hash，
 * 任何外部改动（金额/方向/账户）都会导致后续链校验失败。
 */
@Repository
public class EntryRepository {

    private static final String GENESIS = "0".repeat(64);

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    /** 链尾哈希，无分录时为 64 个 0（创世哈希）。 */
    public synchronized String lastHash() {
        if (entries.isEmpty()) {
            return GENESIS;
        }
        return entries.get(entries.size() - 1).getHash();
    }

    /**
     * 分配 seq、接哈希链并落盘。调用方需已填好除 seq/prevHash/hash 外的全部字段。
     * 注意：余额与流水的原子性由服务层在账户锁内调用本方法保证（异常则不提交余额）。
     */
    public synchronized Entry append(Entry entry) {
        entry.setSeq(entries.size() + 1L);
        entry.setPrevHash(lastHash());
        entry.setHash(Hashes.sha256(canonical(entry)));
        entries.add(entry);
        return entry;
    }

    /** 参与哈希的规范串：含全部业务字段，改任一字段都会使 hash 对不上。 */
    private String canonical(Entry e) {
        return e.getSeq() + "|"
                + e.getPrevHash() + "|"
                + e.getTransferId() + "|"
                + e.getAccountNo() + "|"
                + e.getDirection() + "|"
                + e.getAmount().stripTrailingZeros().toPlainString() + "|"
                + (e.getReversalOf() == null ? "" : e.getReversalOf());
    }

    public List<Entry> findAll() {
        return new ArrayList<>(entries);
    }

    public List<Entry> findByTransfer(String transferId) {
        return entries.stream().filter(e -> transferId.equals(e.getTransferId())).toList();
    }

    /** 找到冲正该转账所产生的任意一条分录，即表示已冲正。 */
    public Optional<Entry> findByReversalOf(String transferId) {
        return entries.stream().filter(e -> transferId.equals(e.getReversalOf())).findFirst();
    }
}
