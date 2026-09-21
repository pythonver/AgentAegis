

# 📐 系统设计说明书（Architecture & Design Specification）

**项目名称**：`Agent-Guardian`

**文档版本**：V1.0

**适用场景**：Java / Spring 生态的 Agent 任务守护、状态持久化与断点复活中间件

## 1. 整体架构设计 (Overall Architecture)

`Agent-Guardian` 采用**模块化分层架构**设计，遵循“核心引擎解耦、Spring Starter 自动装配、轻量级 UI 嵌入”的原则。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        用户业务层 (User Business)                       │
│        @AgentStep / @AgentRetry / TaskContext / AgentTemplate           │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│              agent-guardian-spring-boot-starter (装配与切面层)           │
│  ┌───────────────────────┐  ┌────────────────────┐  ┌─────────────────┐ │
│  │   AOP Interceptors    │  │ BeanPostProcessor  │  │ Boot AutoConfig │ │
│  └───────────┬───────────┘  └─────────┬──────────┘  └────────┬────────┘ │
└──────────────┼────────────────────────┼──────────────────────┼──────────┘
               │                        │                      │
┌──────────────▼────────────────────────▼──────────────────────▼──────────┐
│                      agent-guardian-core (核心引擎层)                   │
│  ┌─────────────────┐    ┌────────────────────┐    ┌──────────────────┐  │
│  │  Retry Engine   │    │ Checkpoint Engine  │    │ Workflow Engine  │  │
│  │ (Exponential    │    │ (Interceptor,      │    │ (Step Registry,  │  │
│  │  Backoff/Jitter)│    │  Replay, Recovery) │    │  Chain Builder)  │  │
│  └─────────────────┘    └─────────┬──────────┘    └──────────────────┘  │
└───────────────────────────────────┼─────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────────────┐
│                    Persistence SPI (存储抽象与扩展)                     │
│  ┌────────────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │
│  │ JdbcCheckpointRepository│ │ RedisCheckpoint...│ │ (Custom Repos)  │  │
│  └───────────┬────────────┘  └────────┬─────────┘  └─────────────────┘  │
└──────────────┼────────────────────────┼─────────────────────────────────┘
               │                        │
┌──────────────▼────────────────────────▼─────────────────────────────────┘
│                         agent-guardian-dashboard                        │
│             Embedded Web Console (Vue 3 + Element Plus)                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.1 核心模块划分

- `agent-guardian-core`：纯 Java 实现，无 Spring 依赖。包含退避重试算法、Checkpoint 模型、上下文容器（`TaskContext`）、SPI 存储接口。

- `agent-guardian-spring-boot-starter`：负责 Spring 环境集成。包含 `@AgentStep` 和 `@AgentRetry` 的 AOP 切面、`BeanPostProcessor` 映射扫描器、`ApplicationReadyEvent` 启动恢复监听器及 AutoConfiguration。

- `agent-guardian-dashboard`：前端控制台。采用 Vue 3 构建后打包为静态文件打包至 Jar 包，内部自带 REST API 路由。

## 2. 技术栈选型 (Technology Stack)

| **维度**    | **选型技术/组件**                     | **版本/规格**       | **选型理由**                                 |
| --------- | ------------------------------- | --------------- | ---------------------------------------- |
| **编程语言**  | Java                            | 17+ (兼容 Java 8) | 兼顾现代化语法支持与旧系统兼容性                         |
| **基础框架**  | Spring Boot                     | 2.7.x / 3.x     | 主流 Java 企业级开发框架，方便 Starter 自动装配          |
| **切面技术**  | AspectJ / Spring AOP            | -               | 实现对 `@AgentRetry` 和 `@AgentStep` 无感拦截    |
| **序列化**   | Jackson / Fastjson2             | -               | 用于 `TaskContext` 及 Step 出入参的高精度 JSON 序列化 |
| **数据持久化** | Spring JDBC Template / MyBatis  | -               | 零强依赖 ORM 框架，仅依赖轻量级 JDBC 即可驱动             |
| **前端框架**  | Vue.js + Element Plus           | Vue 3           | 构建现代化、轻量级的 Dashboard 界面                  |
| **前端打包**  | Vite + `vite-plugin-singlefile` | -               | 将 Web 构建结果打包为静态资源内置在 Starter 中           |

## 3. 核心机制详细设计 (Detailed Mechanism Design)

### 3.1 退避重试与异常识别流程

```
[触发方法调用] ──► [判断是否有 @AgentRetry]
                         │
                         ▼
                  [尝试执行捕获 Exception]
                         │
                         ├─► (无异常) ──► [正常返回结果]
                         │
                         ▼
                [匹配 noRetryFor?] ──► (Yes) ──► [直接向上抛出]
                         │ (No)
                         ▼
                 [匹配 retryFor?]  ──► (No)  ──► [直接向上抛出]
                         │ (Yes)
                         ▼
               [尝试次数 < maxAttempts?] ──► (No) ──► [抛出重试耗尽异常]
                         │ (Yes)
                         ▼
             [计算 Full Jitter 退避时间]
                         │
                         ▼
             [Thread.sleep(T_wait)]
                         │
                         └──► [重新发起调用]
```

**退避时间计算核心代码逻辑**：

$$T_{backoff} = \min(backoffMs \times multiplier^{(attempt - 1)}, maxBackoffMs)$$

$$T_{wait} = \text{ThreadLocalRandom.current().nextLong}(0, T_{backoff})$$

### 3.2 Checkpoint 拦截与回放逻辑 (Replay Algorithm)

当进入 `@AgentStep` 方法时，切面按以下顺序执行：

1. **提取上下文**：获取当前线程绑定 `taskId` 与当前 `@AgentStep.name`。

2. **查询数据库/缓存**：调用 `CheckpointRepository.findByTaskIdAndStepName(taskId, stepName)`。

3. **状态判定**：
- **状态等于 `SUCCESS`**：
  
  - 从 DB 记录中获取 `output_json`。

    - 使用 Jackson 将其反序列化为当前方法的返回值类型（包含泛型推导）。
    
    
    
    - **直接中断原生方法执行（`ProceedingJoinPoint.proceed()` 不调用）**，直接返回反序列化对象。

- **状态不等于 `SUCCESS`（无记录 / FAILED / RUNNING）**：
  
  - 在 DB 中插入或更新记录，设置状态为 `RUNNING`。

    - 调用 `joinPoint.proceed()` 执行真实业务逻辑（如请求大模型 API）。
    
    
    
    - 若执行成功，将返回值序列化为 JSON，更新 DB 状态为 `SUCCESS`，并刷新当前 `TaskContext`。
    
    
    
    - 若执行抛出异常，更新状态为 `FAILED` 并记录错误信息。

## 4. 数据库与存储设计 (Database Design)

为保障极致性能与轻量性，中间件仅需两张核心表：

### 4.1 任务主表：`agent_guardian_task`

存储 Agent 任务实例的整体生命周期状态。

SQL

```
CREATE TABLE `agent_guardian_task` (
  `task_id` VARCHAR(64) NOT NULL COMMENT '任务全局唯一ID',
  `task_name` VARCHAR(128) NOT NULL COMMENT '任务类型/名称',
  `status` VARCHAR(20) NOT NULL COMMENT '状态: RUNNING, SUCCESS, FAILED, TERMINATED',
  `context_json` LONGTEXT COMMENT 'TaskContext 全局序列化快照',
  `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`task_id`),
  INDEX `idx_status_updated` (`status`, `updated_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务实例表';
```

### 4.2 步骤打卡表：`agent_guardian_checkpoint`

存储每个任务下各个 `@AgentStep` 的执行打卡记录。

SQL

```
CREATE TABLE `agent_guardian_checkpoint` (
  `id` BIGINT AUTO_INCREMENT COMMENT '自增主键',
  `task_id` VARCHAR(64) NOT NULL COMMENT '关联的任务ID',
  `step_name` VARCHAR(128) NOT NULL COMMENT '步骤唯一标识',
  `status` VARCHAR(20) NOT NULL COMMENT '步骤状态: RUNNING, SUCCESS, FAILED',
  `input_json` LONGTEXT COMMENT '步骤输入参数(JSON)',
  `output_json` LONGTEXT COMMENT '步骤输出结果(JSON)',
  `retry_count` INT DEFAULT 0 COMMENT '步骤内发生的重试次数',
  `error_stack` TEXT COMMENT '失败异常栈摘要',
  `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_step` (`task_id`, `step_name`),
  INDEX `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent步骤检查点表';
```

## 5. Web Dashboard & UI 风格设计 (UI/UX Design Specification)

为提升可观测性，嵌入式 Web UI 采用科技感暗色与高对比度结合（Deep Slate / Tech Dark）风格设计，强调诊断、排查与干预效率。

### 5.1 视觉视觉与色彩规范 (Design System)

- **背景基调**：深灰色系（Deep Charcoal `#1E1E2E` / `#181825`），减少长时间观测的视觉疲劳。

- **状态主色**：

- **SUCCESS (成功)**：翡翠绿（Emerald `#A6E3A1`）

- **RUNNING (运行中)**：天蓝色（Sky Blue `#89B4FA` / 带 Pulse 呼吸灯动画）

- **FAILED (失败)**：珊瑚红（Flamingo `#F38BA8`）

- **SKIPPED/REPLAY (跳过/回放)**：薰衣草紫（Lavender `#CBA6F7`）

- **字体与代码区**：字号基于 14px，代码/JSON 展示区统一采用等宽字体（`JetBrains Mono` / `Fira Code`）。

### 5.2 核心界面布局设计

#### A. 任务监控列表页 (Task Monitor Page)

- **顶部栏**：数据卡片展示（当前 Running 任务、24h 成功率、累计 Retry 耗时）。

- **主体表格**：

- 每一行包含：`Task ID`、`任务名`、`进度条(已完成Step/总Step)`、`最新状态`、`更新时间`。

- 操作列提供 **[查看详情]**、**[手动断点重试]**、**[终止]** 三个快捷按钮。

#### B. 步骤时序与 JSON 快照详情页 (Step Timeline & Inspector)

采用 **双栏 split 布局**，左侧为步骤链路，右侧为详情审查：

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Task ID: task_20260920_001 [FAILED]                         [手动重试] │
├───────────────────────────────┬─────────────────────────────────────────┤
│ 步骤执行链路 (Timeline)       │ 步骤详情与 JSON Inspector               │
│                               │                                         │
│ 🅂 Step 1: UserIntentAnalyze  │ 📌 Step: LLM_SearchAgent                 │
│    └─ Status: SUCCESS (120ms) │ Status: FAILED                          │
│                               │ Retry Attempts: 3/3                     │
│ 🅂 Step 2: LLM_SearchAgent    │ Error: Http 504 Gateway Timeout         │
│    └─ Status: FAILED (4500ms) │                                         │
│                               │ ┌─ Input JSON ────────────────────────┐ │
│ 🄶 Step 3: FormatReport       │ │ { "query": "Latest AI news" }       │ │
│    └─ Status: PENDING         │ └─────────────────────────────────────┘ │
│                               │ ┌─ TaskContext Snapshot ──────────────┐ │
│                               │ │ { "user_id": "10086", "intent":.. } │ │
│                               │ └─────────────────────────────────────┘ │
│                               │ [强行跳过此步骤(Skip & Inject Mock)]    │
└───────────────────────────────┴─────────────────────────────────────────┘
```

## 6. REST API 接口设计 (Dashboard API)

中间件后端向嵌入式前端开放以下标准 REST 接口（路径前缀统一为 `/agent-guardian/api`）：

HTTP

```
### 1. 分页查询任务列表
GET /agent-guardian/api/tasks?page=1&size=10&status=FAILED&taskId=xxx
Response:
{
  "code": 200,
  "data": {
    "total": 42,
    "items": [
      {
        "taskId": "task_20260920_001",
        "taskName": "MarketAnalysisTask",
        "status": "FAILED",
        "currentStep": "LLM_SearchAgent",
        "createdTime": "2026-09-20 20:00:00",
        "updatedTime": "2026-09-20 20:01:15"
      }
    ]
  }
}

### 2. 获取任务详情与 Checkpoint 时序链
GET /agent-guardian/api/tasks/{taskId}
Response:
{
  "code": 200,
  "data": {
    "taskId": "task_20260920_001",
    "status": "FAILED",
    "context": { "userId": "10086", "query": "AI Agents" },
    "checkpoints": [
      {
        "stepName": "UserIntentAnalyze",
        "status": "SUCCESS",
        "outputJson": "{\"intent\":\"SEARCH\"}",
        "retryCount": 0,
        "updatedTime": "2026-09-20 20:00:05"
      },
      {
        "stepName": "LLM_SearchAgent",
        "status": "FAILED",
        "errorStack": "java.net.SocketTimeoutException: Read timed out",
        "retryCount": 3,
        "updatedTime": "2026-09-20 20:01:15"
      }
    ]
  }
}

### 3. 手动断点重试任务
POST /agent-guardian/api/tasks/{taskId}/retry
Response:
{ "code": 200, "message": "Task retry triggered successfully" }

### 4. 强行跳过指定步骤并注入 Mock 结果
POST /agent-guardian/api/tasks/{taskId}/steps/{stepName}/skip
Content-Type: application/json
{
  "mockOutputJson": "{\"status\": \"mocked_success\", \"data\": []}"
}
Response:
{ "code": 200, "message": "Step force-skipped successfully" }
```

## 7. 安全与异常处理策略 (Security & Edge Cases)

1. **并发锁与防重机制**：
- 在分布式环境下，为了防止两个节点同时拉起复活同一个 Task，底层在执行恢复时需竞争基于数据库的行锁（`SELECT ... FOR UPDATE`）或 Redis 分布式锁。
2. **大 JSON 文本优化**：
- 若 `TaskContext` 或 Step 输出的 JSON 体积过大（例如超过 2MB），框架在持久化前自动启用 GZIP 压缩，读取时解压，降低 DB 存储开销。
3. **Dashboard 访问控制**：
- 可通过配置 `agent.guardian.dashboard.username` 和 `password` 开启轻量级的 Basic Auth，防止敏感数据泄露。
