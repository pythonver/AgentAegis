package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.config.AgentAegisAutoConfiguration;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.exception.TaskAlreadyExistsException;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import ascion.agent.aegis.spring.boot.starter.service.TestWorkflowService;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentWorkflowAspect 全分支覆盖测试。
 *
 * <p>使用 test 环境配置：{@code @ActiveProfiles("test")} → application-test.properties
 * （H2 + schema-mysql.sql + agent-aegis.repository-type=jdbc）。
 *
 * <p>分支清单（对应 AgentWorkflowAspect#agentWorkflow）：
 * <ol>
 *   <li>配置校验：REPLAY + ignoreOutput=true → IllegalArgumentException</li>
 *   <li>getTaskId：ThreadLocal 有 id / 无 id / id 为空白 → 复用或生成</li>
 *   <li>resolveWorkflowKey：name 为空白 → 方法名；否则 → 注解 name（写入 TaskContext.name）</li>
 *   <li>已存在任务：name 空白回填 / 非空白保留</li>
 *   <li>SUCCESS：REPLAY 反序列化（不落库）/ void / payload 空 / THROW_EXCEPTION 抛异常</li>
 *   <li>RUNNING：按重跑覆盖更新（无僵尸接管）</li>
 *   <li>FAILED：按重跑覆盖更新（无次数上限）</li>
 *   <li>PAUSED：显式拒绝进入执行</li>
 *   <li>全新任务初始化（else 分支）</li>
 *   <li>proceedDirectly：成功落库 / ignoreOutput 置空 / 结果为 null / 异常置 FAILED 后重抛</li>
 *   <li>buildResult：AWR 实例回填 / null 容器兜底 / 非 AWR 原样返回</li>
 *   <li>finally：TaskContextHolder.clear()</li>
 * </ol>
 */
@SpringBootTest(classes = AgentWorkflowAspectTest.TestApplication.class)
@ActiveProfiles("test")
@Rollback(false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgentWorkflowAspectTest {

    @SpringBootApplication
    @Import(AgentAegisAutoConfiguration.class)
    static class TestApplication {
    }

    @Autowired
    private TestWorkflowService testWorkflowService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM agent_guardian_task");
        TaskContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TaskContextHolder.clear();
    }

    // ==================== 辅助方法 ====================

    private void useTaskId(String taskId) {
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());
    }

    private void seed(TaskContext task) {
        checkpointRepository.saveTask(task);
    }

    private TaskContext requireTask(String taskId) {
        Optional<TaskContext> opt = checkpointRepository.findTaskById(taskId);
        assertTrue(opt.isPresent(), "数据库中应存在任务: " + taskId);
        return opt.get();
    }

    // ==================== 1. 配置校验分支 ====================

    @Nested
    @DisplayName("分支1：注解配置校验")
    class ConfigValidation {

        @Test
        @DisplayName("REPLAY + ignoreOutput=true → IllegalArgumentException，且不落库")
        void replayWithIgnoreOutputRejected() {
            useTaskId("task_cfg_invalid_001");

            assertThrows(IllegalArgumentException.class,
                    () -> testWorkflowService.processInvalidReplayIgnoreOutput("x"));

            assertFalse(checkpointRepository.findTaskById("task_cfg_invalid_001").isPresent(),
                    "配置非法时不应创建任务记录");
        }

        @Test
        @DisplayName("THROW_EXCEPTION + ignoreOutput=true → 合法，outputPayload 为 null")
        void throwStrategyWithIgnoreOutputAllowed() {
            useTaskId("task_cfg_ignore_ok_001");

            String result = testWorkflowService.processIgnoreOutput("hello");

            assertEquals("sensitive: hello", result);
            TaskContext saved = requireTask("task_cfg_ignore_ok_001");
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
            assertNull(saved.getOutputPayload(), "ignoreOutput=true 时 outputPayload 必须为 null");
        }
    }

    // ==================== 2. taskId / workflow 定义键 生成分支 ====================

    @Nested
    @DisplayName("分支2：taskId 与 workflow 定义键（TaskContext.name）解析")
    class IdAndNameResolution {

        @Test
        @DisplayName("ThreadLocal 已有 taskId → 复用该 id 落库")
        void reuseTaskIdFromContext() {
            useTaskId("task_ctx_reuse_001");

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("a");

            assertEquals("task_ctx_reuse_001", result.getTaskId());
            assertEquals("named-workflow", requireTask("task_ctx_reuse_001").getName());
        }

        @Test
        @DisplayName("无 ThreadLocal 上下文 → 自动生成 task_ 前缀 id")
        void generateTaskIdWhenNoContext() {
            TaskContextHolder.clear();

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("a");

            assertNotNull(result.getTaskId());
            assertTrue(result.getTaskId().startsWith("task_"),
                    "生成的 taskId 应以 task_ 开头: " + result.getTaskId());
            assertEquals(TaskStatus.SUCCESS, requireTask(result.getTaskId()).getStatus());
        }

        @Test
        @DisplayName("ThreadLocal 中 taskId 为空白 → 视为无 id，走生成分支")
        void generateTaskIdWhenContextIdBlank() {
            useTaskId("   ");

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("a");

            assertNotNull(result.getTaskId());
            assertFalse(result.getTaskId().isBlank());
            assertTrue(result.getTaskId().startsWith("task_"));
        }

        @Test
        @DisplayName("name 空白 → 回填为方法名（FAILED 重跑落库时可见）")
        void blankNameFallbackToMethodName() {
            String taskId = "task_name_blank_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("")
                    .status(TaskStatus.FAILED)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            testWorkflowService.processRaw("x");

            assertEquals("processRaw", requireTask(taskId).getName());
        }

        @Test
        @DisplayName("name 非空白 → 保留原 name，不被注解覆盖")
        void nonBlankNamePreserved() {
            String taskId = "task_name_keep_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("keep-me")
                    .status(TaskStatus.FAILED)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            testWorkflowService.processRaw("x");

            assertEquals("keep-me", requireTask(taskId).getName());
        }

        @Test
        @DisplayName("注解 name 空白 → 新任务 name 使用方法名")
        void blankAnnotationNameUsesMethodName() {
            useTaskId("task_name_method_001");

            testWorkflowService.processRaw("x");

            assertEquals("processRaw", requireTask("task_name_method_001").getName());
        }
    }

    // ==================== 3. SUCCESS 分支 ====================

    @Nested
    @DisplayName("分支3：已存在 SUCCESS 任务")
    class SuccessTask {

        private static final String TASK_ID = "task_success_001";

        private void seedSuccess(String name, String outputPayload) {
            useTaskId(TASK_ID);
            seed(TaskContext.builder()
                    .taskId(TASK_ID)
                    .name(name)
                    .status(TaskStatus.SUCCESS)
                    .outputPayload(outputPayload)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        }

        @Test
        @DisplayName("REPLAY + 有 outputPayload → 反序列化还原并回填 taskId/taskStatus（不落库）")
        void replayDeserializesPayload() throws Exception {
            AgentWorkflowResult<String> payload = AgentWorkflowResult.of("cached-value");
            payload.setTaskId(TASK_ID);
            payload.setTaskStatus(TaskStatus.SUCCESS.name());
            String json = SerializeUtil.getMapper().writeValueAsString(payload);
            Instant before = Instant.now().minusSeconds(60);
            useTaskId(TASK_ID);
            seed(TaskContext.builder()
                    .taskId(TASK_ID)
                    .name("named-workflow")
                    .status(TaskStatus.SUCCESS)
                    .outputPayload(json)
                    .retries(0)
                    .createdAt(before)
                    .updatedAt(before)
                    .build());

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("ignored-arg");

            assertEquals(TASK_ID, result.getTaskId());
            assertEquals("SUCCESS", result.getTaskStatus());
            assertEquals("cached-value", result.getData());
            TaskContext saved = requireTask(TASK_ID);
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
            assertEquals(before, saved.getUpdatedAt(), "REPLAY 回放不应落库更新 updatedAt");
        }

        @Test
        @DisplayName("REPLAY + 原始 String 返回类型 → 反序列化为 String 原样返回")
        void replayRawStringReturnType() {
            seedSuccess("processRaw", "\"raw:from-db\"");

            String result = testWorkflowService.processRaw("x");

            assertEquals("raw:from-db", result);
        }

        @Test
        @DisplayName("REPLAY + void 返回类型 → 不触发反序列化，正常返回不抛异常")
        void replayVoidReturnTypeReturnsNull() {
            seedSuccess("void-flow", "{\"taskId\":\"x\"}");

            assertDoesNotThrow(() -> testWorkflowService.processVoid());
        }

        @Test
        @DisplayName("REPLAY + outputPayload 为空白 → 返回带 taskId 的空容器")
        void replayBlankPayloadReturnsEmptyContainer() {
            seedSuccess("named-workflow", null);

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("x");

            assertNotNull(result);
            assertEquals(TASK_ID, result.getTaskId());
            assertEquals("SUCCESS", result.getTaskStatus());
            assertNull(result.getData());
        }

        @Test
        @DisplayName("THROW_EXCEPTION + SUCCESS → TaskAlreadyExistsException")
        void throwStrategyOnSuccess() {
            useTaskId(TASK_ID);
            seed(TaskContext.builder()
                    .taskId(TASK_ID)
                    .name("processThrowOnDuplicate")
                    .status(TaskStatus.SUCCESS)
                    .outputPayload("{}")
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            assertThrows(TaskAlreadyExistsException.class,
                    () -> testWorkflowService.processThrowOnDuplicate("x"));
        }

        @Test
        @DisplayName("THROW_EXCEPTION + ignoreOutput + SUCCESS → 配置合法但仍抛重复异常")
        void throwStrategyIgnoreOutputOnSuccess() {
            useTaskId(TASK_ID);
            seed(TaskContext.builder()
                    .taskId(TASK_ID)
                    .name("processIgnoreOutput")
                    .status(TaskStatus.SUCCESS)
                    .outputPayload(null)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            assertThrows(TaskAlreadyExistsException.class,
                    () -> testWorkflowService.processIgnoreOutput("x"));
        }

        @Test
        @DisplayName("SUCCESS 但 name 空白 → 内存对象回填 name（命中 !hasText(name) 分支）")
        void successWithBlankNameBackfilled() throws Exception {
            AgentWorkflowResult<String> payload = AgentWorkflowResult.of("v");
            String json = SerializeUtil.getMapper().writeValueAsString(payload);
            seedSuccess("", json);

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("x");
            assertEquals("v", result.getData());
        }
    }

    // ==================== 4. RUNNING 分支 ====================

    @Nested
    @DisplayName("分支4：已存在 RUNNING 任务（按重跑覆盖更新）")
    class RunningTask {

        @Test
        @DisplayName("RUNNING → 按重跑执行，覆盖更新为 SUCCESS，body 实际执行")
        void runningRerunsAndOverwrites() {
            String taskId = "task_running_live_001";
            useTaskId(taskId);
            Instant old = Instant.now().minusSeconds(5);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("processRaw")
                    .status(TaskStatus.RUNNING)
                    .createdAt(old)
                    .updatedAt(old)
                    .retries(0)
                    .build());

            String result = testWorkflowService.processRaw("x");

            assertEquals("raw: x", result);
            TaskContext saved = requireTask(taskId);
            assertEquals(TaskStatus.SUCCESS, saved.getStatus(), "RUNNING 重跑成功后应覆盖为 SUCCESS");
            assertTrue(saved.getInputPayload().contains("\"x\""), "重跑应刷新 inputPayload");
        }

        @Test
        @DisplayName("RUNNING 且业务失败 → 覆盖更新为 FAILED 原样抛出")
        void runningRerunFailurePersistsFailed() {
            String taskId = "task_running_fail_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("processAlwaysFail")
                    .status(TaskStatus.RUNNING)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .retries(0)
                    .build());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> testWorkflowService.processAlwaysFail("x"));
            assertEquals("Biz Error", ex.getMessage());
            assertEquals(TaskStatus.FAILED, requireTask(taskId).getStatus());
        }
    }

    // ==================== 5. FAILED 分支 ====================

    @Nested
    @DisplayName("分支5：已存在 FAILED 任务（按重跑，无次数上限）")
    class FailedTask {

        @Test
        @DisplayName("FAILED → 直接重跑成功，覆盖更新且不自增 retries")
        void failedRerunsWithoutRetryCounting() {
            String taskId = "task_failed_retry_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("named-workflow")
                    .status(TaskStatus.FAILED)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("x");

            assertEquals("SUCCESS", result.getTaskStatus());
            TaskContext saved = requireTask(taskId);
            assertEquals(0, saved.getRetries(), "workflow 级重跑不自增 retries");
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
        }

        @Test
        @DisplayName("retries 已为任意值 → 仍可重跑（无 maxRetries 上限）")
        void failedWithHighRetriesStillReruns() {
            String taskId = "task_failed_max_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("processRaw")
                    .status(TaskStatus.FAILED)
                    .retries(99)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            String result = testWorkflowService.processRaw("x");

            assertEquals("raw: x", result);
            TaskContext saved = requireTask(taskId);
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
            assertEquals(99, saved.getRetries(), "重跑不改写历史 retries 值");
        }

        @Test
        @DisplayName("业务执行仍失败 → 状态再次落库为 FAILED 并原样抛出")
        void failedRerunStillFailsPersistsFailed() {
            String taskId = "task_failed_twice_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("processAlwaysFail")
                    .status(TaskStatus.FAILED)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> testWorkflowService.processAlwaysFail("x"));
            assertEquals("Biz Error", ex.getMessage());

            TaskContext saved = requireTask(taskId);
            assertEquals(TaskStatus.FAILED, saved.getStatus());
            assertEquals(0, saved.getRetries());
        }
    }

    // ==================== 6. PAUSED 分支 ====================

    @Nested
    @DisplayName("分支6：PAUSED 状态（预留状态显式拒绝）")
    class PausedTask {

        @Test
        @DisplayName("PAUSED → IllegalStateException 拒绝进入执行，状态保持 PAUSED")
        void pausedRejectsWithException() {
            String taskId = "task_paused_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("named-workflow")
                    .status(TaskStatus.PAUSED)
                    .retries(0)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> testWorkflowService.processWithResult("x"));

            assertTrue(ex.getMessage().contains(taskId), "异常信息应包含 taskId");
            assertTrue(ex.getMessage().contains("PAUSED"), "异常信息应说明 PAUSED 原因");
            assertEquals(TaskStatus.PAUSED, requireTask(taskId).getStatus(),
                    "拒绝路径不应改写状态");
        }
    }

    // ==================== 7. 全新任务初始化 ====================

    @Nested
    @DisplayName("分支7：仓储中不存在该任务（else 全新初始化）")
    class NewTask {

        @Test
        @DisplayName("新任务 → retries=0、状态经 RUNNING 落盘，成功后 SUCCESS")
        void newTaskInitializesAndSucceeds() {
            useTaskId("task_new_001");

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("payload");

            assertEquals("SUCCESS", result.getTaskStatus());
            TaskContext saved = requireTask("task_new_001");
            assertEquals("task_new_001", saved.getTaskId());
            assertEquals(0, saved.getRetries());
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
            assertNotNull(saved.getInputPayload());
            assertTrue(saved.getInputPayload().contains("payload"),
                    "inputPayload 应序列化入参: " + saved.getInputPayload());
            assertNotNull(saved.getOutputPayload());
        }

        @Test
        @DisplayName("业务返回 null → outputPayload 为 null，buildResult 兜底空容器")
        void nullResultBuildsEmptyContainer() {
            useTaskId("task_new_null_001");

            AgentWorkflowResult<String> result = testWorkflowService.processNullResult();

            assertNotNull(result, "返回类型为 AgentWorkflowResult 时即使业务返回 null 也应有容器");
            assertEquals("task_new_null_001", result.getTaskId());
            assertEquals("SUCCESS", result.getTaskStatus());
            assertNull(result.getData());

            TaskContext saved = requireTask("task_new_null_001");
            assertEquals(TaskStatus.SUCCESS, saved.getStatus());
            assertNull(saved.getOutputPayload());
        }

        @Test
        @DisplayName("业务抛异常 → 置 FAILED 落库并原样重抛，finally 清理上下文")
        void exceptionMarksFailedAndClearsContext() {
            useTaskId("task_new_fail_001");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> testWorkflowService.processAlwaysFail("boom"));

            assertEquals("Biz Error", ex.getMessage());
            TaskContext saved = requireTask("task_new_fail_001");
            assertEquals(TaskStatus.FAILED, saved.getStatus());
            assertEquals(0, saved.getRetries(), "首次执行失败 retries 保持 0");
            assertNull(TaskContextHolder.getContext(), "finally 分支必须清理 ThreadLocal");
        }
    }

    // ==================== 8. buildResult / 返回值包装 ====================

    @Nested
    @DisplayName("分支8：buildResult 返回值包装")
    class BuildResult {

        @Test
        @DisplayName("返回类型是 AgentWorkflowResult 且结果非 null → 回填 taskId/taskStatus")
        void wrapsAgentWorkflowResult() {
            useTaskId("task_build_awr_001");

            AgentWorkflowResult<String> result = testWorkflowService.processWithResult("wrap");

            assertEquals("SUCCESS", result.getTaskStatus());
            assertEquals("task_build_awr_001", result.getTaskId());
            assertEquals("processed: wrap", result.getData());
        }

        @Test
        @DisplayName("返回类型非 AgentWorkflowResult（原始 String）→ 原样返回不包装")
        void rawTypeReturnedAsIs() {
            useTaskId("task_build_raw_001");

            String result = testWorkflowService.processRaw("plain");

            assertEquals("raw: plain", result);
        }
    }

    // ==================== 9. finally 上下文清理 ====================

    @Nested
    @DisplayName("分支9：ThreadLocal 清理")
    class ContextCleanup {

        @Test
        @DisplayName("成功路径执行后上下文被清理")
        void clearedAfterSuccess() {
            useTaskId("task_cleanup_ok_001");

            testWorkflowService.processWithResult("x");

            assertNull(TaskContextHolder.getContext());
        }

        @Test
        @DisplayName("拦截异常路径（PAUSED 拒绝）后上下文仍被清理")
        void clearedAfterRejectedException() {
            String taskId = "task_cleanup_reject_001";
            useTaskId(taskId);
            seed(TaskContext.builder()
                    .taskId(taskId)
                    .name("named-workflow")
                    .status(TaskStatus.PAUSED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .retries(0)
                    .build());

            assertThrows(IllegalStateException.class,
                    () -> testWorkflowService.processWithResult("x"));

            assertNull(TaskContextHolder.getContext());
        }
    }
}
