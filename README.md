# 幂等结算记账台（本地版）

一套**完全本地、不依赖任何外部服务或真实支付通道**的双分录记账能力，重点保证：

- 转账按调用方幂等标识安全重放，重复提交只入账一次；
- 并发下账户余额安全扣减，不透支、不重复扣款；
- 每笔记账有可追溯的哈希链流水，支持幂等冲正与账实核对。

技术栈：Java 21 + Spring Boot 3.4，数据全部保存在内存（重启清空），无需数据库。

---

## 1. 记账规则

### 1.1 双分录

金额单位为**最小整数货币单位**（如“分”），代码中用 `long`，不使用浮点。

- 一笔转账产生两条金额相等、方向相反的分录：
  - **借（debit）收款方**：收款方余额增加；
  - **贷（credit）付款方**：付款方余额减少。
- 记账视角为“借增贷减”，任意时刻：所有借方金额合计 == 所有贷方金额合计。
- 开户的初始余额也走双分录：`借 新账户 / 贷 SYSTEM_OPENING（系统开业配平科目）`。
  因此每个账户的余额都能由流水完整重算，全系统（含开业科目）净额恒为 0；
  客户资金在转账/冲正前后总额守恒。`SYSTEM_OPENING` 不出现在客户账户列表中。

### 1.2 流水与哈希链

流水只追加、不可变（`TransactionJournal`）。每条分录（`LedgerEntry`）携带：

- 对自身业务字段（分录号、交易号、账户号、金额、方向、序号、前驱哈希）计算的 **SHA-256**；
- 全局单调递增序号 `seq` 与上一条分录的哈希 `prevHash`，构成一条**哈希链**，
  首条指向 64 个 0 的创世值。

外部直接改动任何一条分录的金额/方向/账户，会导致内容哈希不匹配；
删除或重排分录会导致哈希链断裂。

### 1.3 冲正

- 冲正**不删除、不修改**原交易，而是追加一笔方向相反的新交易：
  原付款方收回资金（借）、原收款方退回资金（贷），金额与原交易相同，总额守恒。
- 原交易会被标记 `reversedById`；冲正交易带 `reversalOfId`。
- 冲正交易本身**不能再被冲正**；已冲正的交易再次冲正会被拒绝
  （`TRANSACTION_ALREADY_REVERSED`）。
- 冲正与普通转账走同一套幂等机制，重复调用同一冲正键只产生一次冲正。

---

## 2. 幂等约定

每个写请求必须带调用方提供的 `idempotencyKey`（字符串，必填）。

幂等登记册（`IdempotencyRegistry`）对每个键记录**请求内容指纹**（SHA-256）与处理结果：

| 场景 | 行为 |
|---|---|
| 新键 | 执行真实入账，成功后发布结果（交易号） |
| 同键 + **相同内容**（无论顺序还是并发） | 等待首个请求完成，回放同一交易号，`replayed=true`，**绝不二次入账** |
| 同键 + **不同内容**（收付方或金额不同） | 立即拒绝 `IDEMPOTENCY_CONFLICT`（HTTP 409，不可重试） |
| 键缺失/金额非法/收付方相同等 | `PARAM_INVALID`（HTTP 400），与幂等冲突明确区分 |

内容指纹只参与业务字段：`fromAccountId`、`toAccountId`、`amount`；
备注 `reference` 不参与，因此“同一笔业务换个备注重提”仍视为同一请求。

**失败不占用幂等结果**：余额不足、锁争用等失败发生后，幂等槽位会释放，
调用方可用同一个键在条件满足后重试，不会被回放成“永久失败”。

---

## 3. 并发与余额一致性约定

- 每个账户一把可重入锁（`AccountLocks`）。涉及两个账户的操作
  **始终按账户号字典序依次加锁**，所有事务遵循同一加锁顺序，因此不会出现 AB/BA 死锁。
- 余额检查与“落流水 + 改余额”在同一组账户锁内一次完成，锁外看不到中间态。
- 加锁使用 `tryLock(ledger.lock-timeout-millis)`（默认 200ms）：
  - 超时 → `RETRYABLE_CONTENTION`（HTTP 409，**`retryable=true`**）：没有任何入账，
    调用方应退避后用**新的幂等键**重试；
  - 余额不足 → `INSUFFICIENT_FUNDS`（HTTP 422，**`retryable=false`**）：业务拒绝，不应重试。
- 账户余额在仓储层用 `ConcurrentHashMap.compute` 做单键原子读改写，版本号 `version` 递增，
  杜绝丢失更新。

调用方据此可以清楚区分三类结果：成功 / 拒绝（参数错、冲突、余额不足）/ 可重试争用。

### 错误码一览

| 错误码 | HTTP | retryable | 含义 |
|---|---|---|---|
| `PARAM_INVALID` | 400 | false | 参数错误（键为空、金额非正、收付方相同等） |
| `IDEMPOTENCY_CONFLICT` | 409 | false | 同一幂等键提交了不同内容 |
| `DUPLICATE_ACCOUNT` | 409 | false | 账户已存在 |
| `ACCOUNT_NOT_FOUND` / `TRANSACTION_NOT_FOUND` | 404 | false | 账户/交易不存在 |
| `INSUFFICIENT_FUNDS` | 422 | false | 余额不足，拒绝 |
| `RETRYABLE_CONTENTION` | 409 | **true** | 并发争用未拿到锁，可安全重试 |
| `TRANSACTION_ALREADY_REVERSED` | 409 | false | 交易已被冲正 |

错误响应体形如：
```json
{"errorCode":"INSUFFICIENT_FUNDS","retryable":false,"message":"账户 C 余额 0 不足以支付 1"}
```

---

## 4. 流水核对

`POST /api/ledger/reconcile`（或 `ReconciliationService.reconcile()`）在**全部账户锁内**
取一致快照，做四类检查，结论优先级 `TAMPERED > UNBALANCED > OK`：

1. `ENTRY_HASH_MISMATCH`：重算每条分录的 SHA-256，与存储值不符（记录被外部改动）；
2. `HASH_CHAIN_BROKEN`：序号不连续或前驱哈希对不上（删除/重排/篡改）；
3. `ENTRIES_NOT_BALANCED`：借方合计 != 贷方合计；
4. `ACCOUNT_BALANCE_MISMATCH`：由流水按“借增贷减”重算的账户余额 != 账户当前余额
   （外部绕开流水直接改余额会在这里暴露）。

报告含总体结论、账户余额合计、流水净额、各账户由流水重算的余额，以及逐条 findings
（带类型、涉及分录/账户号与判定依据），可直接据此人工判断。

---

## 5. HTTP 接口速览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ledger/accounts` | 开户 `{id,name,initialBalance}` |
| GET | `/api/ledger/accounts` | 客户账户列表 |
| GET | `/api/ledger/accounts/{id}` | 账户详情（含余额、版本） |
| POST | `/api/ledger/transfers` | 转账 `{idempotencyKey,fromAccountId,toAccountId,amount,reference}` |
| GET | `/api/ledger/transactions/{id}` | 交易详情（含冲正标记） |
| POST | `/api/ledger/transactions/{id}/reversals` | 冲正，body 可空或 `{idempotencyKey}` |
| POST | `/api/ledger/reconcile` | 全量核对，返回核对报告 |

---

## 6. 本地验证

### 6.1 跑单元测试（重点覆盖并发/重复/冲突）

```bash
mvn test
```

测试不依赖 Spring 容器（上下文冒烟除外），直接手工装配内存组件：

- `AccountAndTransferTest`：双分录守恒、余额不足拒绝、参数校验、冲正回退；
- `IdempotencyTest`：顺序/并发重复提交只入账一次、同键不同内容判冲突、与参数错误区分、
  失败不毒化幂等键；
- `ConcurrentTransferTest`：60 笔并发打向同一热点账户不透支、总额守恒；
  持锁制造争用验证 `RETRYABLE_CONTENTION` 与 `INSUFFICIENT_FUNDS` 可区分；
  调用方退避重试后成功且无重复入账；
- `ReconciliationTest`：正常账目 OK、篡改分录金额判 TAMPERED、直接改余额判 UNBALANCED、
  双重冲正防护、冲正幂等重放。

测试用 SLF4J 打印每个场景的**输入**与**判定依据**（成功/各类失败计数、余额、守恒口径），
日志见控制台或 `target/surefire-reports/`。

### 6.2 启动应用手工验证

```bash
mvn spring-boot:run        # 或 mvn package 后 java -jar target/*.jar
```

启动后自动创建演示账户：A=100000、B=100000、C=0（单位：最小货币单位）。

```bash
# 转账
curl -s -X POST localhost:8080/api/ledger/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountId":"A","toAccountId":"B","amount":30000}'

# 同一键重复提交 -> 相同交易号、replayed=true，余额不再变化
curl -s -X POST localhost:8080/api/ledger/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountId":"A","toAccountId":"B","amount":30000}'

# 同键不同内容 -> 409 IDEMPOTENCY_CONFLICT（区别于 400 PARAM_INVALID）
curl -s -X POST localhost:8080/api/ledger/transfers -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"K1","fromAccountId":"A","toAccountId":"C","amount":30000}'

# 核对
curl -s -X POST localhost:8080/api/ledger/reconcile

# 冲正（把上一步返回的交易号替换进去），重复调用同键返回 replayed=true
curl -s -X POST localhost:8080/api/ledger/transactions/<TX>/reversals \
  -H 'Content-Type: application/json' -d '{}'
```

### 6.3 配置项（`src/main/resources/application.properties`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 本地端口 |
| `ledger.lock-timeout-millis` | 200 | 账户锁等待超时，超时即判为可重试争用 |
| `ledger.seed-demo-data` | true | 启动时写入 A/B/C 演示账户 |

> 说明：内存实现面向本地验证与教学，进程重启数据即清空；
> 接口形态（稳定错误码、幂等键、retryable 标记）按可直接替换为持久化实现的边界设计。
