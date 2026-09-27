# 幂等结算记账（Idempotent Settlement Ledger）

一套**纯本地、不依赖任何外部服务或真实支付通道**的双分录记账能力，重点保证：

- 转账按调用方幂等键安全重放：同键重复提交只真实入账一次，结果稳定；
- 同一幂等键提交不同内容被明确拒绝，且该原因与普通参数错误可区分；
- 并发下安全扣减：不透支、不重复扣款，余额与流水随时对得上；
- 并发争用失败与余额不足分开表达，调用方一眼看出"该重试还是该拒绝"；
- 流水可追溯、可冲正，冲正本身幂等且不破坏总额守恒；
- 核对能发现账目不平与被外部改动过的记录，并给出可判断的结论。

技术栈：Java 21 + Spring Boot 3.4，内存存储（`ConcurrentHashMap`），无数据库、无中间件。

---

## 1. 记账规则

### 1.1 双分录转账

每笔转账固定产生**两条金额相等、方向相反**的分录：

| 账户 | 方向 | 含义 | 余额变化 |
|------|------|------|----------|
| 转出方 `from` | `CREDIT`（贷方） | 资产减少 | `-amount` |
| 收款方 `to` | `DEBIT`（借方） | 资产增加 | `+amount` |

约定：系统内全部借贷发生额合计永远相等（有借必有贷、借贷必相等）。
金额必须为正数、最多两位小数；转出/收款账户不能相同；账户必须存在。

### 1.2 流水与防篡改哈希链

所有分录存在一条全局严格递增的链上：

- `seq` 从 1 连续递增；
- 每条分录持有 `prevHash`（上一条的 `hash`，创世为 64 个 `0`）和自身 `hash`；
- `hash = sha256(seq | prevHash | transferId | accountNo | direction | amount | reversalOf)`。

任何对历史分录（金额、方向、账户、所属单据）的外部改动，都会让该条重算 hash 对不上、
并破坏后续所有 `prevHash` 接龙，核对时必定暴露。

### 1.3 冲正规则

错误入账通过冲正回退：对目标转账**追加**两条反向分录，产生一笔独立的冲正单据
（新 `transferId`，新分录用 `reversalOf` 指向原转账），不删除、不改写历史：

- 原转出方补回（`DEBIT`），原收款方扣减（`CREDIT`）；
- 冲正后该笔转账净发生额为零，双方余额复原，总额守恒；
- 一笔转账**最多只能被成功冲正一次**；重复冲正确定性拒绝 `ALREADY_REVERSED`；
- 冲正不存在的单据返回 `TRANSFER_NOT_FOUND`；
- **冲正本身也走幂等闸门**：同一冲正键重复调用只重放，不会重复回退。

> 冲正只回退指定那一笔。被冲正的单据状态置为 `REVERSED`，冲正分录与原分录都永久保留。

---

## 2. 幂等约定

调用方在每次转账/冲正请求上携带 `idempotencyKey`（由调用方生成的唯一标识）。
服务端对每个键保存：请求内容指纹 `fingerprint = sha256(intent | from | to | amount)`、
生命周期状态、以及**稳定结果快照**（成功结果或确定性拒绝的错误码）。

处理顺序固定为三步，保证各类失败互不混淆：

1. **参数校验**：非法参数 -> `PARAM_INVALID` / `SAME_ACCOUNT` / `ACCOUNT_NOT_FOUND`；
2. **幂等闸门**：
   - 键不存在：原子占位（`PROCESSING`）后执行；
   - 键已存在且**指纹不同** -> `IDEMPOTENCY_CONFLICT`（换键，不要重试）；
   - 键存在且内容相同但仍在 `PROCESSING` -> `CONCURRENT_CONFLICT`（稍后重试同键）；
   - 键已 `COMPLETED`：直接回放稳定结果——成功返回同一 `transferId`（`replayed=true`），
     确定性业务拒绝（如余额不足）回放同一错误码；
3. **锁内业务执行**：账户存在性与余额校验在此进行。

要点：

- **同一请求重放结果稳定**：第一次成功，之后永远返回同一个 `transferId`，且 `replayed=true`；
  第一次余额不足，之后用同键同内容永远得到同一个 `INSUFFICIENT_FUNDS`；
- 幂等冲突（同键不同内容）与参数错误是**不同错误码**，不要混为一谈；
- 只有"可重试"的并发争用失败会释放占位；确定性失败会留下记录用于稳定重放。

---

## 3. 并发约定

- 每个账户编号映射到一把条带锁（`AccountLocks`，256 条带）。
- 一次转账需要同时持有转出方与收款方两把锁时，**一律按账户号升序加锁**，杜绝环路等待（死锁）。
- 使用 `tryLock` 带超时（默认 200ms）：超时立即返回 `CONCURRENT_CONFLICT`，
  语义是"此刻有争用，请重试"，**与 `INSUFFICIENT_FUNDS`（确定性拒绝，重试无意义）严格区分**。
- 余额校验与余额/流水更新都在同一对账户锁内完成，因此多笔转账同时打向同一账户时：
  不会透支、不会重复扣款；事后按流水重算的余额与账户余额必然一致。
- 幂等占位是原子的 `putIfAbsent`：同一键并发提交，只有一个线程进入真实记账。

### 错误码与处置方式

| errorCode | 含义 | HTTP | 调用方处置 |
|-----------|------|------|-----------|
| `PARAM_INVALID` / `SAME_ACCOUNT` | 参数错误 | 422 | 改请求，勿重试 |
| `ACCOUNT_NOT_FOUND` / `TRANSFER_NOT_FOUND` | 账户/单据不存在 | 404 | 勿原样重试 |
| `IDEMPOTENCY_CONFLICT` | 同键不同内容 | 409 | 更换幂等键 |
| `INSUFFICIENT_FUNDS` | 余额不足（确定性） | 422 | 勿重试（同键重放也得到同一结果） |
| `ALREADY_REVERSED` | 单据已冲正 | 422 | 勿重复冲正 |
| `CONCURRENT_CONFLICT` | 同键进行中 / 账户锁争用超时 | 409 | **用原请求稍后重试** |

注意 `IDEMPOTENCY_CONFLICT` 与 `CONCURRENT_CONFLICT` 同为 HTTP 409，
但调用方必须依据响应体里的 `errorCode` 区分：前者换键、后者重试。

---

## 4. 核对（Reconciliation）

`GET /ledger/reconcile` 返回结论 `balanced` 与明细，从四个维度校验：

1. **哈希链完整**：`seq` 连续、`prevHash` 接龙、每条 `hash` 重算一致（发现外部篡改）；
2. **逐户余额**：按 `初始余额 + 借方合计 - 贷方合计` 重算，与账户当前余额比对；
3. **全局守恒**：全部借方发生额合计 == 全部贷方发生额合计；
4. **双分录配对**：每个 `transferId` 恰好两条、金额相等、方向相反、账户不同、冲正标记一致。

任一不满足，`balanced=false`，`problems` 给出每条可判断的原因
（如 `entry tampered: seq=...`、`balance mismatch for A: ledger=..., computedFromEntries=...`、
`debit/credit not balanced: ...`），`accountDetails` 给出逐户借/贷/账面/重算余额。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ledger/accounts/{accountNo}` | 开户，body `{"initialBalance":1000.00}`（可省略，默认 0） |
| GET  | `/ledger/accounts/{accountNo}` | 查余额 |
| POST | `/ledger/transfers` | 转账，body 含 `idempotencyKey/fromAccountNo/toAccountNo/amount` |
| POST | `/ledger/transfers/{transferId}/reversal` | 冲正，body `{"idempotencyKey":"..."}` |
| GET  | `/ledger/reconcile` | 全量核对 |

转账成功响应：`{"transferId":"T...","idempotencyKey":"K1","replayed":false}`；
错误响应：`{"errorCode":"INSUFFICIENT_FUNDS","message":"..."}`。

---

## 6. 本地验证

无需任何数据库或外部服务，JDK 21+、Maven 即可。

### 6.1 运行测试（推荐，含并发/幂等/冲突重点场景）

```bash
mvn test
```

覆盖（共 15 个用例，日志中以 `[输入] ... [判定] ...` 打印输入与判定依据）：

- `LedgerBasicTest`：首次入账、同键稳定重放、同键不同内容冲突、余额不足稳定重放、错误码区分；
- `LedgerConcurrencyTest`：200 笔并发扣同一账户不透支且总额守恒；400 笔网状互转系统总额守恒；
- `LedgerRaceAndRetryTest`：同键并发仅一次真实入账、败者先收并发冲突再重放成功；
- `LockContentionTest` 与 `LedgerRaceAndRetryTest`：用 1ms 超时 + 跨线程占锁，
  确定性触发 `CONCURRENT_CONFLICT`，占锁期间每次都被争用拒绝且不留流水，释放后用原键重试成功（证明"可重试"语义）；
- `LedgerConcurrentReversalTest`：8 线程不同键并发冲正同一笔，恰一次成功、其余 `ALREADY_REVERSED`；
- `LedgerReversalReconcileTest`：冲正回退与幂等、重复/未知单据区分、流水篡改检测、余额被外部改动检测；
- `LedgerApiIT`：端到端 HTTP，验证各错误码与状态码、重放稳定、核对通过。

### 6.2 本地启动并用 curl 走全流程

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

另开终端：

```bash
B=http://localhost:8080/ledger
curl -s -X POST $B/accounts/A -H 'Content-Type: application/json' -d '{"initialBalance":100.00}'
curl -s -X POST $B/accounts/B -H 'Content-Type: application/json' -d '{"initialBalance":0}'

# 首次转账
curl -s -X POST $B/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountNo":"A","toAccountNo":"B","amount":30.00}'
# 同键同内容重放 -> replayed=true，transferId 不变
curl -s -X POST $B/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountNo":"A","toAccountNo":"B","amount":30.00}'
# 同键不同内容 -> 409 IDEMPOTENCY_CONFLICT
curl -s -X POST $B/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountNo":"A","toAccountNo":"B","amount":99}'
# 余额不足 -> 422 INSUFFICIENT_FUNDS
curl -s -X POST $B/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K3","fromAccountNo":"A","toAccountNo":"B","amount":9999}'
# 冲正（用上一步返回的 transferId）
curl -s -X POST $B/transfers/<transferId>/reversal \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"R1"}'
# 核对：balanced 应为 true
curl -s $B/reconcile
```

---

## 7. 代码结构

```
domain/   Account、Entry、Direction、TransferStatus、IdempotencyRecord/Status
error/    ErrorCode（可区分的失败原因）、LedgerException
repo/     AccountRepository、EntryRepository（seq+哈希链）、IdempotencyRepository（原子占位）
support/  Hashes（SHA-256）、AccountLocks（条带锁、升序加锁、tryLock 超时）
service/  LedgerService（转账/冲正/核对核心）、ReconciliationReport、dto
web/      LedgerController、GlobalExceptionHandler（错误码->HTTP 映射）
```

说明：当前为内存存储，进程重启数据清空，定位是"本地可运行、语义扎实"的能力底座；
记账、幂等、并发、核对的业务规则不依赖存储介质，后续可将三个 Repository 替换为数据库实现。
