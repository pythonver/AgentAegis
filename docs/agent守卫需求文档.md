修改后的 PRD 详细功能需求说明如下，已**全面移除通过 `@AgentStep(order = N)` 定义顺序的需求**，并对每一项功能性需求进行了深入到工程实现级的细化扩写：

---

# 📄 产品需求文档（PRD）：Agent-Guardian 功能性需求规格说明

## 1. 核心架构设计理念调整说明

在取消 `order` 顺序属性后，中间件的步骤控制彻底解耦了硬编码的顺序逻辑：

1. **声明式（代码即流程）**：直接依赖 Java 语言本身的天然控制流（如方法调用顺序、`if-else` 分支、`for` 循环），切面仅负责在方法执行前后进行状态拦截、校验与结果回放。
2. **编排式（链式/DAG 驱动）**：通过 SDK（如 `AgentTemplate.createFlow()`）在代码中显式构建步骤调用链或图结构，实现动态编排。

---

## 2. 功能性需求（Functional Requirements）详细规格

### 2.1 微观重试与容错 Engine (Retry Engine)

#### FR-1.1 声明式重试注解 (`@AgentRetry`)

* **需求描述**：
提供方法级别的切面注解 `@AgentRetry`，允许开发者将其标注在任意直接调用大模型（LLM API）或外部工具（Tool API）的方法上。
* **属性详细配置**：
* `maxAttempts` (int)：最大尝试总次数（含首次正常调用）。默认值为 `3`，最小可配值为 `1`（即不重试）。
* `backoffMs` (long)：基础退避延迟时长，单位为毫秒（ms）。默认值为 `1000`。
* `maxBackoffMs` (long)：退避最大上限时长（ms），防止指数退避计算出的等待时间过长。默认值为 `30000`。
* `multiplier` (double)：退避时间递增乘数。默认值为 `2.0`。


* **拦截与执行机制**：
* 当被标注方法抛出指定异常时，切面自动捕捉异常并在当前线程进行休眠等待（`Thread.sleep` 或基于 `ScheduledExecutorService` 的非阻塞等待），休眠结束后重新触发该方法的执行。
* 重试过程需记录日志，日志中需明确包含 `taskId`、`methodName`、当前重试第几次及本次等待时间。



#### FR-1.2 智能退避与抖动算法 (Backoff & Jitter)

* **需求描述**：
为了防止网络抖动或下游 API 限流（如 429 Rate Limit）时，大量并发请求在同一时刻集中重试导致下游服务彻底瘫痪（雪崩效应），必须内置带有随机抖动的指数退避计算公式。
* **算法计算公式**：
* 无抖动指数退避：$T_{backoff} = \min(backoffMs \times multiplier^{(attempt - 1)}, maxBackoffMs)$
* 全抖动（Full Jitter）策略：$T_{wait} = \text{random}(0, T_{backoff})$


* **执行规范**：
* 框架需默认启用 Full Jitter 策略，确保并发任务在重试时的时间戳均匀分散。



#### FR-1.3 异常分类识别与精确拦截

* **需求描述**：
重试机制必须具备异常区分能力，避免对“不可恢复的错误”（如参数非法、Token 超限）进行无意义的重试，浪费资源并增加耗时。
* **配置属性**：
* `retryFor` (Class<? extends Throwable>[])：指定触发重试的异常类型数组。例如 `SocketTimeoutException.class`、`Http504Exception.class`、`RateLimitException.class`。默认包含 `Throwable.class`。
* `noRetryFor` (Class<? extends Throwable>[])：指定绝不触发重试的排除异常类型数组。例如 `IllegalArgumentException.class`、`ContextLengthExceededException.class`（HTTP 400 提示 prompt 过长）、`UnauthorizedException.class`（HTTP 401 密钥失效）。


* **匹配优先逻辑**：
* 判定顺序：当抛出异常 $E$ 时，先校验是否匹配 `noRetryFor`（包含继承关系），若匹配则**立即向上抛出异常并终止重试**；若不匹配，再校验是否满足 `retryFor`，满足则触发退避重试；若均不满足，则直接抛出。



---

### 2.2 状态持久化与断点复活 Engine (Checkpoint Engine)

#### FR-2.1 检查点（Checkpoint）数据模型与打卡机制

* **需求描述**：
当一个 Agent 步骤（`@AgentStep`）执行成功后，中间件必须将该步骤的执行状态与输出结果原子化地写入持久化介质（数据库/Redis），作为未来复活的“检查点”。
* **存储表/结构设计 (`agent_guardian_checkpoint`)**：
* `task_id` (String, PK)：任务实例全局唯一 ID。
* `step_name` (String, PK)：步骤名称/唯一标识。
* `status` (String)：当前步骤状态，枚举值：`RUNNING`（执行中）、`SUCCESS`（执行成功）、`FAILED`（执行失败）。
* `input_json` (Text)：步骤入参的 JSON 序列化字符串（可选）。
* `output_json` (LongText)：步骤返回值的 JSON 序列化结果（若无返回值则存 `null`）。
* `retry_count` (int)：该步骤实际发生的重试次数。
* `updated_time` (DateTime)：最后更新时间。


* **写入逻辑**：
* 进入步骤时，插入或更新记录为 `RUNNING`。
* 步骤正常返回时，更新为 `SUCCESS` 并保存 `output_json`。
* 步骤最终重试失败后，更新为 `FAILED` 并记录异常栈摘要。



#### FR-2.2 步骤自动拦截与历史结果回放 (Interceptor & Replay)

* **需求描述**：
在触发带有 `@AgentStep` 的方法前，切面必须先检索当前 `taskId` + `name` 的历史 Checkpoint 状态，决定是否真正执行该方法。
* **执行分支**：
* **分支 A（未完成/无记录）**：如果 DB 中无记录或状态为 `FAILED`/`RUNNING`，则正常放行执行业务方法体。
* **分支 B（已成功）**：如果 DB 中记录状态为 `SUCCESS`，切面**直接阻止方法体真正执行**（阻止网络请求与底层计算），将 `output_json` 反序列化为该方法的返回类型对象，并直接返回。


* **类型反序列化保障**：
* 支持泛型、复杂对象及集合类型的准确反序列化，确保业务代码感知不到“这是一次历史回放”。



#### FR-2.3 全局上下文容器 (`TaskContext`) 传递与落库

* **需求描述**：
提供线程安全的上下文容器 `TaskContext`，用于在不同步骤之间共享临时变量、LLM 产生的中间对话历史（Messages）及 Tool 执行结果。
* **功能规格**：
* 提供 `TaskContext.set(String key, Object value)` 与 `TaskContext.get(String key, Class<T> clazz)` API。
* **自动落库**：每次 Checkpoint 打卡时，整个 `TaskContext` 的 Map 结构将作为全局状态同步持久化至 `agent_guardian_task` 主表中的 `context_json` 字段。
* **恢复装载**：断点复活时，中间件首先从数据库读取 `context_json` 并反序列化回 `TaskContext`，随后绑定至当前执行线程（ThreadLocal）。



#### FR-2.4 宕机/孤儿任务自动扫描与复活 (Crash Recovery Listener)

* **需求描述**：
当应用所在的 JVM 进程因 OOM、崩溃、宿主机断电或容器重启（如 Kubernetes 节点迁移）而异常终止时，系统重启后需具备自动恢复能力。
* **实现细节**：
* 实现 Spring Boot `ApplicationListener<ApplicationReadyEvent>` 监听器，在 Spring 容器完全初始化后启动恢复逻辑。
* **孤儿任务扫描**：查询数据库中状态为 `RUNNING` 且 `updated_time` 距离当前时间已超过设定阈值（例如 5 分钟未更新，说明原进程已死亡）的任务记录。
* **线程拉起**：由中间件内置的恢复线程池（`RecoveryExecutor`）接管该 `taskId`，重新调用入口函数。依靠 FR-2.2 的拦截机制，已完成的步骤自动回放，从最后失败/未执行的步骤起继续向下跑。



---

### 2.3 步骤控制与编排 Engine (Workflow Engine)

#### 模式 A：声明式模式 (AOP + 自然代码流)

##### FR-3.1 步骤切面注解 (`@AgentStep`)

* **需求描述**：
标注在 Spring 托管的 Bean 方法上，作为 Checkpoint 的打卡锚点。
* **属性配置**：
* `name` (String)：步骤的全局唯一标识。若为空则默认使用 `类名#方法名`。
* `description` (String)：业务描述（用于 Dashboard 显示）。



##### FR-3.2 Spring 容器方法注册表 (Bean Post Processor)

* **需求描述**：
中间件需要在 Spring 启动阶段感知所有被 `@AgentStep` 标注的方法，以便在进行任务恢复或 API 触发重试时能够找到对应的方法进行反射调用。
* **实现规格**：
* 实现 `BeanPostProcessor` 接口，在 `postProcessAfterInitialization` 阶段扫描每个 Bean 的 Class 定义。
* 解析带有 `@AgentStep` 的 Method，将其注册进内存映射表：`Map<String, StepMethodInvocation>`，其中 Key 为 `name`，Value 包含 `Object targetBean` 与 `Method method`。



##### FR-3.3 代码自然控制流支持 (Code as Workflow)

* **需求描述**：
无需强加任何 `order` 顺序号约束，完全尊重 Java 语言原本的控制流。
* **场景覆盖**：
* **顺序结构**：在 Service 方法内依次调用 `stepA()` -> `stepB()` -> `stepC()`。
* **分支结构**：根据 `stepA()` 返回的意图识别结果，执行 `if (intent == 'SEARCH') stepB() else stepC()`。断点复活时，`stepA()` 回放出意图结果，代码天然走入对应的分支步骤。
* **循环结构**：在 `for` 或 `while` 循环中调用 `stepX(index)`（通过将 index 作为 stepName 的一部分如 `stepX_1`，实现循环体内的断点续跑）。



#### 模式 B：编排式模式 (SDK / Builder 驱动)

##### FR-3.4 编排步骤统一接口 (`AgentStepTask`)

* **需求描述**：
对于希望将步骤解耦为独立 Class 的场景，提供统一的接口定义。
* **接口定义**：
```java
public interface AgentStepTask<I, O> {
    String getStepName();
    O execute(I input, TaskContext context) throws Exception;
}

```


* **自动收集**：Spring 启动时，自动依赖注入所有实现该接口的 Bean，并维护在 `Map<String, AgentStepTask>` 中。

##### FR-3.5 链式编排 SDK (`AgentTemplate`)

* **需求描述**：
提供 Fluent 风格的链式 API，允许在不创建大量切面注解的情况下，动态组装流程。
* **API 示例与需求**：
```java
AgentTemplate.createFlow("MarketAnalysisTask")
    .then("fetchDataStep", ctx -> dataService.fetch(ctx))
    .then("llmAnalyzeStep", ctx -> llmService.analyze(ctx))
    .then("generateReportStep", ctx -> reportService.build(ctx))
    .execute(initialContext);

```


* **执行与打卡集成**：
每一个 `.then()` 节点内部均封装 Checkpoint 校验逻辑，效果与 `@AgentStep` 完全一致。

---

### 2.4 可观测性与 Web UI Dashboard

#### FR-4.1 全局概览大盘 (Overview)

* **需求描述**：
提供单页大盘展示当前中间件托管的所有 Agent 任务的整体健康度。
* **数据卡片与指标**：
* **实时运行数**：当前状态为 `RUNNING` 的 Task 总数。
* **24h 成功率/失败率**：过去 24 小时内标记为 `SUCCESS` vs `FAILED` 的百分比饼图。
* **重试统计**：过去 24 小时累计发生的 `@AgentRetry` 次数。
* **异常类型分布**：按 `TimeoutException`、`429 RateLimit`、`500 Internal Error` 归类的柱状图。



#### FR-4.2 任务实例列表 (Task Monitor)

* **需求描述**：
提供支持高频查询的任务实例数据表格。
* **查询与过滤条件**：
* 支持输入 `Task ID` 精确搜索。
* 支持按 `Status`（RUNNING / SUCCESS / FAILED / TERMINATED）筛选。
* 支持按 `Created Time` 时间范围筛选。


* **列表展现列**：
* `Task ID`、`任务名称`、`当前停留 Step`、`累计重试次数`、`总执行耗时`、`创建时间`、`操作按钮区`。



#### FR-4.3 步骤级执行时序图 (Step Timeline)

* **需求描述**：
点击任一 Task 展开详情页，以水平或垂直时序轴（Timeline）形式展现该任务下所有 Step 的生命周期。
* **展示元素**：
* 每个 Step 节点显示：`Step Name`、`状态标识`（绿:成功，红:失败，蓝:运行中，灰:跳过/回放）。
* 节点展开后可显示：该 Step 的耗时（ms）、内部发生重试的次数、重试抛出的具体异常栈。



#### FR-4.4 Context 与 Checkpoint 快照查看器

* **需求描述**：
在 Step 详情侧边栏中提供交互式 JSON 格式查看器。
* **功能规格**：
* **Input/Output JSON 渲染**：高亮展示当前 Step 的入参与出参，支持节点展开、折叠与一键复制。
* **Global TaskContext 快照**：展示该步骤打卡时刻，`TaskContext` 内部所有 Key-Value 的完整快照。



#### FR-4.5 在线运维控制台 (Manual Operations)

* **需求描述**：
提供针对卡死、报错或异常任务的人工运维干预手段。
* **核心功能按钮**：
1. **手动断点重试 (Retry Task)**：对于处于 `FAILED` 或卡死状态的 Task，点击后由后端后台线程调用恢复引擎，从失败步骤重新触发任务。
2. **强行跳过当前步骤 (Force Skip Step)**：当某个步骤因外部 Tool 接口永久损坏而无法通过重试解决时，运维人员可选中该 Step，手动输入一段 Mock 的 JSON 结果，强制将该 Step 状态改为 `SUCCESS` 并保存该 Mock 结果，同时驱动 Task 继续向下执行。
3. **终止任务 (Terminate Task)**：将卡在 `RUNNING` 状态的垃圾任务强制变更状态为 `TERMINATED`，防止复活逻辑继续对其进行无意义拉起。