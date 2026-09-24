# AgentAegis Wiki

AgentAegis 是面向 Spring Boot 3 的 **Agent 工作流守卫** starter：用注解为方法提供任务生命周期、步骤断点与可配置重试/降级。

| 模块 | 说明 |
|------|------|
| `agent-aegis-core` | 模型、仓储抽象、**RetryEngine**、`AgentRetryExhaustedException` |
| `agent-aegis-spring-boot-starter` | 三个注解、三个切面、自动装配、JDBC/内存仓储 |
| `agent-aegis-dashboard` | 看板（本篇不展开） |

技术栈：Java 21、Spring Boot 3.2.x、AOP、可选 JDBC（H2/MySQL）。

---

## 目录

1. [Quick Start](#1-quick-start)
2. [配置项](#2-配置项)
3. [`@AgentWorkflow` — 工作流入口](#3-agentworkflow--工作流入口)
4. [`@AgentStep` — 步骤断点](#4-agentstep--步骤断点)
5. [`@AgentRetry` — 方法级重试与降级](#5-agentretry--方法级重试与降级)
6. [重试引擎 RetryEngine](#6-重试引擎-retryengine)
7. [三注解协作与调用约束](#7-三注解协作与调用约束)
8. [状态、异常与数据表](#8-状态异常与数据表)
9. [已知限制](#9-已知限制)

---

## 1. Quick Start

### 1.1 引入依赖

本仓库多模块构建后安装到本地仓库：

```bash
mvn -pl agent-aegis-core,agent-aegis-spring-boot-starter -am install
```

业务工程 `pom.xml`：

```xml
<dependency>
    <groupId>ascion.agent.aegis</groupId>
    <artifactId>agent-aegis-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

依赖 `spring-boot-starter-aop`（starter 已传递引入）。自动配置通过：

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`  
→ `AgentAegisAutoConfiguration`

**默认零配置即可用**（内存仓储 + 三个切面已注册）。

### 1.2 最小可运行示例

**步骤 Bean（必须经 Spring 代理调用，勿在同类内 `this.step()`）：**

```java
@Component
public class OrderSteps {

    @AgentStep(name = "deduct_stock")
    public String deductStock(String sku) {
        // … 扣库存
        return "stock_ok:" + sku;
    }

    @AgentStep(name = "charge")
    @AgentRetry(maxRetries = 3, baseDelayMs = 200, fallbackMethod = "chargeFallback")
    public String charge(String orderId) {
        // 偶发失败会自动重试；耗尽后走 chargeFallback
        return "paid:" + orderId;
    }

    public String chargeFallback(String orderId, Throwable cause) {
        return "pay_degraded:" + orderId; // cause 为业务原异常（非包装类）
    }

    @AgentStep(name = "notify")
    public void notifyUser(String orderId) { /* … */ }
}
```

**工作流入口：**

```java
@Service
public class PlaceOrderWorkflow {

    @Autowired
    private OrderSteps steps;

    @AgentWorkflow(name = "place-order")
    public AgentWorkflowResult<String> place(String orderId) {
        String stock = steps.deductStock("SKU-1");
        String paid  = steps.charge(orderId);
        steps.notifyUser(orderId);
        return AgentWorkflowResult.of(stock + "|" + paid);
    }
}
```

**调用：**

```java
AgentWorkflowResult<String> r = workflow.place("ORD-1001");
// r.getTaskId() / r.getTaskStatus() / r.getData()
// 失败时方法抛出异常；任务状态为 FAILED（详见第 8 节）
```

### 1.3 使用 JDBC 持久化（可选）

```properties
agent-aegis.enabled=true
agent-aegis.repository-type=JDBC
# INHERIT（默认）：复用宿主 DataSource/JdbcTemplate
# ISOLATED：使用 agent-aegis.datasource.custom.* 独立库
agent-aegis.datasource.mode=INHERIT

spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema-mysql.sql
```

建表脚本：`agent-aegis-spring-boot-starter/src/main/resources/schema-mysql.sql`  
（任务表 `agent_guardian_task`、打卡表 `agent_guardian_checkpoint`。）

### 1.4 跑通本仓库测试

```powershell
mvn -pl agent-aegis-core,agent-aegis-spring-boot-starter -am test
```

端到端三切面用例见 `AgentAegisEndToEndTest`（Happy path / Retry 耗尽 / REPLAY）。

---

## 2. 配置项

前缀：`agent-aegis`（`AgentAegisProperties`）

| 配置 | 默认 | 说明 |
|------|------|------|
| `agent-aegis.enabled` | `true` | `false` 时整套自动装配（切面 + 仓储）不生效 |
| `agent-aegis.repository-type` | `MEMORY` | `MEMORY` \| `JDBC` |
| `agent-aegis.datasource.mode` | `INHERIT` | `INHERIT` 复用宿主库；`ISOLATED` 独立数据源 |
| `agent-aegis.datasource.custom.*` | — | `ISOLATED` 时的 url/username/password/driver 等 |

切面 `@Order`（同 join point 多注解时由外到内）：

| 切面 | Order |
|------|-------|
| `AgentStepAspect` | `100` |
| `AgentRetryAspect` | `200` |
| `AgentWorkflowAspect` | 未声明（最低优先级） |

跨方法调用时包裹关系由 **调用栈** 决定，不依赖上述 Order。

---

## 3. `@AgentWorkflow` — 工作流入口

**切面**：`AgentWorkflowAspect`  
**职责**：单次 workflow 执行生命周期（taskId 认领 → 绑上下文 → RUNNING 落库 → 执行 → 终态落库 → 清上下文）+ **仅 SUCCESS 再入策略**（REPLAY / THROW）。僵尸判定与后台清扫不在切面职责内（预留定时任务后置）；workflow 级重试次数上限已移除（执行内重试见 `@AgentRetry`）。

### 3.1 属性

| 属性 | 默认 | 说明 |
|------|------|------|
| `name` | `""` | **workflow 定义键**（写入 `TaskContext.name`，与 Registry 对齐）；空白则用方法名 |
| `conflictStrategy` | `REPLAY` | 同 `taskId` 已 **SUCCESS** 时：`REPLAY` 反序列化上次结果返回（**不落库**）；`THROW_EXCEPTION` 抛 `TaskAlreadyExistsException` |
| `zombieTimeoutSeconds` | `300` | **仅供后续后台清扫任务**（预留）；切面不读取、不做僵尸接管 |
| `ignoreOutput` | `false` | 成功时不写 `outputPayload` |
| `failFast` | `true` | **注解已声明，当前实现未接入**（Step 失败靠异常上抛使实例 FAILED） |

`ConflictStrategy.REPLAY` 与 `ignoreOutput=true` **不能同时使用**（入口即抛 `IllegalArgumentException`）。

### 3.1.1 定义键 ↔ 实例 name（术语边界）

| 侧 | 词汇 | 含义 |
|----|------|------|
| **Workflow（定义）** | `@AgentWorkflow.name`、Registry 键、`WorkflowDescription` | 静态入口；扫描注册 |
| **Task（实例）** | `taskId`、`TaskContext`、`TaskStatus`、表 `*_task` | 一次运行：状态/重试/出入参 |

**唯一约定的 JOIN**：定义键写入实例 `TaskContext.name`（表列 `name`），恢复时用 `task.name` 反查 Registry。  
`AgentWorkflowResult` 类名属调用的 workflow 入口，字段 `taskId`/`taskStatus` 属本次运行实例。  
冲突异常分侧：`DuplicateWorkflowException` = 定义同名；`TaskAlreadyExistsException` = 实例冲突。

### 3.2 生命周期（单次执行）

```
进入方法
  → 校验配置
  → 解析/生成 taskId（优先 ThreadLocal 中已有 id，否则 task_UUID）
  → 查库：SUCCESS → REPLAY（不落库）或 THROW 拒绝；PAUSED → 显式拒绝；RUNNING/FAILED → 按重跑覆盖更新
  → TaskContextHolder.setContext + saveTask(RUNNING)
  → 执行业务体（内部可调 @AgentStep）
  → 成功：saveTask(SUCCESS)，必要时包装 AgentWorkflowResult
  → 异常：saveTask(FAILED)，原样抛出
  → finally：TaskContextHolder.clear()
```

### 3.3 返回值 `AgentWorkflowResult<T>`

方法返回类型为 `AgentWorkflowResult<T>` 时，切面会回填：

- `taskId`
- `taskStatus`（`SUCCESS` / `FAILED` / …）
- `data`：业务真实数据（可用 `AgentWorkflowResult.of(x)` 构造）

返回普通类型则原样返回（仍会完成任务落库）。

### 3.4 固定 taskId（测试 / 断点再入）

```java
TaskContextHolder.setContext(
    TaskContext.builder().taskId("task_order_1001").build());
AgentWorkflowResult<String> r = workflow.place("ORD-1001");
// 结束后上下文会被 clear；测试里请自行管理 set/clear
```

---

## 4. `@AgentStep` — 步骤断点

**切面**：`AgentStepAspect`（`@Order(100)`）  
**职责**：按 `(taskId, stepName)` 打卡、SUCCESS 回放、超时控制、失败堆栈。

### 4.1 属性

| 属性 | 默认 | 说明 |
|------|------|------|
| `name` | `""` | 步骤名；空白则用方法名。同任务内唯一键为 `(task_id, step_name)` |
| `description` | `""` | 描述（元数据） |
| `replay` | `true` | 已有 **SUCCESS** 打卡时：反序列化 `outputPayload` 直接返回，**跳过真实执行**；`false` 则强制重跑 |
| `timeout` | `0` | `>0` 时在独立线程池执行并限时；超时主线程抛 `TimeoutException`，打卡 FAILURE |
| `timeoutUnit` | `SECONDS` | 超时单位 |
| `ignoreOutput` | `false` | 成功不写 `outputPayload` |

### 4.2 执行流程

```
读 TaskContextHolder.taskId
  → 查 (taskId, stepName)
  → SUCCESS && replay → 反序列化返回（不执行 body）
  → 否则：新建/复用 Checkpoint，status=RUNNING，先落库
  → timeout<=0：同步执行；成功 SUCCESS + 出参；失败 FAILURE + exceptionStack，再抛出
  → timeout>0：异步池 + 主线程等待；超时则取消并 FAILURE
```

### 4.3 使用要点

1. **必须在 `@AgentWorkflow` 上下文内调用**（或自行 `TaskContextHolder.setContext`），否则 `taskId` 为空，内存仓储会 NPE、JDBC 会约束失败。  
2. **必须经代理**：注入其他 Bean 调用，或 `((OrderSteps) AopContext.currentProxy()).charge(...)`（自动配置已 `exposeProxy=true`）。  
3. 同类 `this.charge()` **不会**进切面（无打卡、无回放）。

---

## 5. `@AgentRetry` — 方法级重试与降级

**切面**：`AgentRetryAspect`（`@Order(200)`）  
**职责**：调用 `RetryEngine.execute`；耗尽后可选 Fallback。

### 5.1 属性

| 属性 | 默认 | 说明 |
|------|------|------|
| `maxRetries` | `3` | **最大重试次数**（总调用次数 ≈ 1 次初试 + maxRetries 次重试） |
| `baseDelayMs` | `2000` | 基础延迟（Full Jitter 上限基数） |
| `maxDelayMs` | `60000` | 单次延迟上限 |
| `backoffFactor` | `2.0` | 指数因子 |
| `retryFor` | `IOException`, `TimeoutException`, `RuntimeException` | 允许重试的异常类型（`isInstance` 匹配） |
| `noRetryFor` | `IllegalArgumentException`, `NullPointerException`, `IllegalStateException`, `SecurityException` | **禁止**重试；优先于 `retryFor` |
| `fallbackMethod` | `""` | 空：耗尽/异常直接上抛；非空：调用同类该方法作降级 |

### 5.2 异常语义

| 情况 | 抛出 |
|------|------|
| 异常命中 `noRetryFor`（或不在 `retryFor` 内） | **原异常**，不包装、不重试 |
| 可重试且打满 `maxRetries` | `AgentRetryExhaustedException`（`message` 含 `maxRetries=N`，**`getCause()` 为业务原异常**） |
| 配置了 `fallbackMethod` | 耗尽后进入降级；方法若多一个 `Throwable` 参数，传入的是 **`e.getCause()`（原异常）**，不是包装类 |

### 5.3 Fallback 示例

```java
@AgentRetry(maxRetries = 2, baseDelayMs = 100, fallbackMethod = "onError")
public String callRemote(String id) {
    throw new RuntimeException("RPC 超时");
}

// 签名二选一：
public String onError(String id) { return "default"; }
public String onError(String id, Throwable cause) {
    // cause.getMessage() == "RPC 超时"
    return "default:" + cause.getMessage();
}
```

### 5.4 与 `@AgentStep` 同方法

Step(`100`) 包 Retry(`200`)：

```
Step 打卡 RUNNING → Retry 循环执行 body → 最终一次由 Step 写 SUCCESS/FAILURE
```

重试过程中**不会**为每次 attempt 各写一条 Step 打卡；同名步骤始终 upsert 一行。

---

## 6. 重试引擎 RetryEngine

包：`ascion.agent.aegis.core.retry`（core 模块，可脱离注解单独使用）。

### 6.1 算法

- **Full Jitter 指数退避**：  
  `cap = min(maxDelay, baseDelay * factor^min(attempt-1, 30))`  
  实际 sleep ∈ `[0, cap]`（随机 jitter）。  
  `cap <= 0` 或 `baseDelay = 0` → 不 sleep。
- `isRetryable`：
  1. `noRetryFor` 命中 → `false`；
  2. `retryFor` 非空 → 仅类型在集合中为 `true`，否则 `false`；
  3. `retryFor` 为空 → 所有 `Exception` 为 `true`。

### 6.2 直接使用（无 Spring）

```java
import ascion.agent.aegis.core.retry.RetryConfig;
import ascion.agent.aegis.core.retry.RetryEngine;
import ascion.agent.aegis.core.retry.RetryTask;

import java.time.Duration;
import java.util.Set;

RetryConfig config = RetryConfig.builder()
        .maxRetries(3)
        .baseDelay(Duration.ofMillis(100))
        .maxDelay(Duration.ofSeconds(2))
        .backoffFactor(2.0)
        .retryFor(Set.of(RuntimeException.class))
        .noRetryFor(Set.of(IllegalArgumentException.class))
        .build();

try {
    String result = RetryEngine.execute(() -> {
        // 业务逻辑，可 throw
        return callFlakyService();
    }, config);   // throws Throwable
    System.out.println(result);
} catch (AgentRetryExhaustedException e) {
    Throwable cause = e.getCause(); // 业务原异常
} catch (Throwable t) {
    // 不可重试异常：原样抛出
}
```

`RetryTask<T>` 为函数式接口：`T run() throws Throwable`。

### 6.3 `RetryConfig` 字段

| 字段 | builder 默认 | 注解对应 |
|------|--------------|----------|
| `maxRetries` | `1` | `@AgentRetry.maxRetries`（注解默认 3） |
| `baseDelay` | `2000ms` | `baseDelayMs` |
| `maxDelay` | `30s` | `maxDelayMs` |
| `backoffFactor` | `2.0` | `backoffFactor` |
| `retryFor` / `noRetryFor` | 空集合 | 注解有非空默认值 |

注意：**注解默认 `retryFor/noRetryFor` 与 `RetryConfig` 空默认不一致**；经 `@AgentRetry` 时以注解为准。

### 6.4 公开 API

```java
Duration calculateDelay(int attempt, Duration base, Duration max, double factor);
Duration calculateDelay(int attempt, RetryConfig config);
boolean   isRetryable(Throwable t, Set<Class<? extends Throwable>> retryFor,
                      Set<Class<? extends Throwable>> noRetryFor);
<T> T     execute(RetryTask<T> task, RetryConfig config) throws Throwable;
```

---

## 7. 三注解协作与调用约束

### 7.1 推荐结构（线性工作流）

```
@AgentWorkflow 方法A          ← 任务级：建/查任务、SUCCESS/FAILED
    ├── @AgentStep 方法B      ← 步骤级：打卡 + 回放 + 可选超时
    │       └── @AgentRetry    ← 可与 Step 同方法，或单独方法
    ├── @AgentStep 方法C
    └── @AgentStep 方法D
```

跨方法时上下文顺序天然正确：Workflow 先 `setContext`，内部 Step 再读 `taskId`。

### 7.2 调用约束

| 约束 | 原因 |
|------|------|
| Step/Workflow 方法须为 **public + 经代理** | Spring AOP 限制 |
| 避免同类 `this.xxx()` | 自调用不进切面 |
| Step 应在 Workflow 体内（或已有 context） | 否则无 `taskId` |
| **不要**在 Step 内再调另一个 `@AgentWorkflow` 且期望「子 workflow 运行实例」 | 当前会复用父 `taskId` → 易 `TaskAlreadyExistsException`（见已知限制） |
| 同一方法同时标 `@AgentWorkflow` + `@AgentStep` | Step Order 更靠外，可能在 context 绑定前读到空 id；不推荐 |

### 7.3 Retry 放置

- **Step 内敏感远程调用**：`@AgentStep` + `@AgentRetry` 同方法（推荐先 Step 后 Retry 包裹语义）。  
- **独立工具方法**：仅 `@AgentRetry`，由 Workflow/Step 调用。

---

## 8. 状态、异常与数据表

### 8.1 运行实例 `TaskStatus`

| 状态 | 含义 |
|------|------|
| `RUNNING` | 执行中；超时未更新可由**后续后台清扫**判定僵尸（切面不接管） |
| `SUCCESS` | 成功 |
| `FAILED` | 失败；再次进入按**重跑**覆盖更新（无次数上限） |
| `PAUSED` | 预留（HITL）；切面**显式拒绝**进入执行 |

### 8.2 步骤 `StepStatus`

`NOT_STARTED` → `RUNNING` → `SUCCESS` / `FAILURE`（另有 `SKIPPED` 枚举值）

### 8.3 异常

| 异常 | 模块 | 场景 |
|------|------|------|
| `AgentRetryExhaustedException` | core | Retry 打满；`getCause()` 为原异常 |
| `TaskAlreadyExistsException` | starter | SUCCESS 且策略为 THROW |
| `IllegalStateException` | starter | PAUSED 再入（预留状态，拒绝执行） |
| `IllegalArgumentException` | starter | `REPLAY` + `ignoreOutput=true` |
| `TimeoutException` | starter | `@AgentStep` 超时 |

### 8.4 表结构（摘要）

**`agent_guardian_task`**：`task_id`(UK)、`name`、`status`、`retries`、`input_payload`、`output_payload`、`metadata_json`、时间戳  

**`agent_guardian_checkpoint`**：`(task_id, step_name)` UK、`status`、出入参、`exception_stack`、`execution_time_ms`、时间戳  

完整 DDL：starter 内 `schema-mysql.sql`。

---

## 9. 已知限制

1. **`failFast` 注解属性已声明，切面未实现**；Step 失败依赖异常传播将任务标为 FAILED。  
2. **不支持嵌套子 Workflow**：子方法会复用父 `taskId` 并可能触发 `TaskAlreadyExistsException`；`finally` 为 `clear()` 而非栈式 pop。跨次断点恢复还依赖 **稳定 taskId**（默认每次新 UUID 时无法对上历史 checkpoint）。  
3. **`@AgentWorkflow` 的 SpEL `taskId` 未实现**（当前仅 ThreadLocal 复用或生成 UUID）。  
4. **同方法双注解**（Workflow+Step）顺序不理想，勿依赖。  
5. **自调用** 需代理；诊断日志见 `SelfInvocationDiagnosisUtil`。  
6. **`PAUSED` / HITL** 仅有枚举与切面拒绝分支，恢复/继续执行未完整实现。  
7. **已知缺陷：同一 taskId 并发再入**（双线程同时进入 RUNNING/FAILED 重跑）无乐观锁，可能互相覆盖落库；后续引入乐观锁与分布式认领（AEGIC-23）。  
8. **僵尸清扫 / 后台恢复顺序** 尚未落地（`zombieTimeoutSeconds`、`RecoveryOrder` 为预留）。

---

## 附录：端到端测试对照

`AgentAegisEndToEndTest`：

| 用例 | 验证点 |
|------|--------|
| E2E-1 | 三切面 Bean、JDBC 仓储、业务 Bean 为 AOP 代理 |
| E2E-2 | Workflow→Step→Retry 先败后成；任务与 3 条打卡 SUCCESS |
| E2E-3 | Retry 耗尽 → `AgentRetryExhaustedException` → 任务 FAILED，后续 Step 不执行 |
| E2E-4 | 同 taskId 再入 → REPLAY，body 不重跑 |

```powershell
mvn -pl agent-aegis-spring-boot-starter -am test "-Dtest=AgentAegisEndToEndTest"
```
