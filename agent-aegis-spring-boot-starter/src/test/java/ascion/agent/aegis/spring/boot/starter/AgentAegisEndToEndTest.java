package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.exception.AgentRetryExhaustedException;
import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentRetryAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentStepAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
import ascion.agent.aegis.spring.boot.starter.config.AgentAegisAutoConfiguration;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import ascion.agent.aegis.spring.boot.starter.service.E2eLinearWorkflowService;
import ascion.agent.aegis.spring.boot.starter.service.E2eStepService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三切面端到端：同一调用链验证 @AgentWorkflow + @AgentStep + @AgentRetry。
 *
 * <p>用例：
 * <ol>
 *   <li>Happy path：线性 Step1 → flaky(先失败再成功 Retry) → Step3，任务与打卡全 SUCCESS</li>
 *   <li>Retry 耗尽 → Workflow FAILED，后续 Step 不执行</li>
 *   <li>同 taskId 再入：Workflow SUCCESS + REPLAY，body 不重跑</li>
 * </ol>
 *
 * <p>SQL：仅使用 test 资源 schema-mysql.sql，不修改任何 .sql 文件。
 */
@SpringBootTest(classes = AgentAegisEndToEndTest.TestApplication.class)
@ActiveProfiles("test")
@Rollback(false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "agent-aegis.enabled=true",
        "agent-aegis.repository-type=JDBC",
        "agent-aegis.datasource.mode=INHERIT",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:aegis_e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-mysql.sql"
})
class AgentAegisEndToEndTest {

    @SpringBootApplication
    @Import(AgentAegisAutoConfiguration.class)
    static class TestApplication {
    }

    @Autowired
    private E2eLinearWorkflowService workflowService;

    @Autowired
    private E2eStepService stepService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM agent_guardian_checkpoint");
        jdbcTemplate.update("DELETE FROM agent_guardian_task");
        stepService.reset();
        TaskContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TaskContextHolder.clear();
    }

    private TaskContext requireTask(String taskId) {
        Optional<TaskContext> opt = checkpointRepository.findTaskById(taskId);
        assertTrue(opt.isPresent(), "任务应存在: " + taskId);
        return opt.get();
    }

    private Checkpoint requireCheckpoint(String taskId, String stepName) {
        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, stepName);
        assertTrue(cp.isPresent(), "打卡应存在: " + taskId + "/" + stepName);
        return cp.get();
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    @DisplayName("E2E-1：装配冒烟 — 三切面 + JDBC 仓库 + Step 代理均在")
    void aspectsAndJdbcRepositoryRegistered() {
        assertNotNull(applicationContext.getBean(AgentWorkflowAspect.class), "Workflow 切面应已注册");
        assertNotNull(applicationContext.getBean(AgentStepAspect.class), "Step 切面应已注册");
        assertNotNull(applicationContext.getBean(AgentRetryAspect.class), "Retry 切面应已注册");
        assertInstanceOf(ascion.agent.aegis.spring.boot.starter.repository.JdbcCheckpointRepositoryImpl.class,
                checkpointRepository, "E2E 应使用 JDBC 仓库");
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(stepService),
                "Step 业务 Bean 应为 AOP 代理，否则切面不生效");
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(workflowService),
                "Workflow 业务 Bean 应为 AOP 代理");
    }

    @Test
    @DisplayName("E2E-2：Happy path — Workflow 串 Step + Retry 先败后成，任务与三打卡全 SUCCESS")
    void linearWorkflow_withRetry_success() {
        AgentWorkflowResult<String> result = workflowService.runLinear("A");

        assertNotNull(result);
        assertNotNull(result.getTaskId());
        assertEquals(TaskStatus.SUCCESS.name(), result.getTaskStatus());
        assertEquals("one:A|flaky:one:A|three:flaky:one:A", result.getData());

        // Retry 真实重试：前 2 次失败 + 第 3 次成功
        assertEquals(3, stepService.getFlakyCount(), "flakyStep 应被调用 3 次");
        assertEquals(1, stepService.getStepOneCount());
        assertEquals(1, stepService.getStepThreeCount());

        String taskId = result.getTaskId();
        TaskContext task = requireTask(taskId);
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals("e2e-linear", task.getName());
        assertFalse(task.getOutputPayload() == null || task.getOutputPayload().isBlank(),
                "成功任务应有 outputPayload");

        List<Checkpoint> all = checkpointRepository.listCheckpoints(taskId);
        assertEquals(3, all.size(), "应存在 3 条打卡: " + all);

        for (String stepName : List.of("step_one", "flaky", "step_three")) {
            Checkpoint cp = requireCheckpoint(taskId, stepName);
            assertEquals(taskId, cp.getTaskId(), "打卡必须挂在同一 taskId 下");
            assertEquals(StepStatus.SUCCESS, cp.getStatus(), stepName + " 应为 SUCCESS");
            assertFalse(cp.getOutputPayload() == null || cp.getOutputPayload().isBlank(),
                    stepName + " 应有 outputPayload");
        }

        // finally 已清理 ThreadLocal
        assertNull(TaskContextHolder.getTaskId(), "Workflow 结束后上下文必须清空");
    }

    @Test
    @DisplayName("E2E-3：Retry 耗尽 → Workflow FAILED，后续 Step 不执行")
    void linearWorkflow_retryExhausted_taskFailed() {
        AgentRetryExhaustedException ex = assertThrows(
                AgentRetryExhaustedException.class,
                () -> workflowService.runFailing("B")
        );
        assertTrue(ex.getMessage().contains("maxRetries=2"), ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("always-fail:one:B", ex.getCause().getMessage());

        // step_one 成功 1 次；step_three 不应执行
        assertEquals(1, stepService.getStepOneCount());
        assertEquals(0, stepService.getStepThreeCount(), "失败后不应执行 step_three");

        // 通过任务状态反查：应有且仅有一条 FAILED 任务
        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_guardian_task WHERE status = 'FAILED'",
                Integer.class);
        assertEquals(1, failedCount, "应有 1 条 FAILED 任务");

        String taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM agent_guardian_task WHERE status = 'FAILED'",
                String.class);
        assertNotNull(taskId);

        TaskContext task = requireTask(taskId);
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("e2e-fail", task.getName());

        Checkpoint one = requireCheckpoint(taskId, "step_one");
        assertEquals(StepStatus.SUCCESS, one.getStatus());

        Checkpoint fail = requireCheckpoint(taskId, "always_fail");
        assertEquals(StepStatus.FAILURE, fail.getStatus());
        assertFalse(fail.getExceptionStack() == null || fail.getExceptionStack().isBlank(),
                "失败打卡应记录异常堆栈");

        Optional<Checkpoint> three = checkpointRepository.findCheckpoint(taskId, "step_three");
        assertTrue(three.isEmpty(), "耗尽失败后不应存在 step_three 打卡");

        assertNull(TaskContextHolder.getTaskId());
    }

    @Test
    @DisplayName("E2E-4：同 taskId 再入 — Workflow SUCCESS + REPLAY，body 与 Retry 不重跑")
    void linearWorkflow_sameTaskId_replaySkipsBody() {
        AgentWorkflowResult<String> first = workflowService.runLinear("C");
        assertEquals(TaskStatus.SUCCESS.name(), first.getTaskStatus());
        assertEquals(3, stepService.getFlakyCount());

        // 注入同一 taskId 再入（默认 conflictStrategy=REPLAY）
        TaskContext seed = requireTask(first.getTaskId());
        TaskContextHolder.setContext(seed);

        AgentWorkflowResult<String> second = workflowService.runLinear("C-should-not-run");

        assertNotNull(second);
        assertEquals(first.getTaskId(), second.getTaskId(), "REPLAY 应回填同一 taskId");
        assertEquals(TaskStatus.SUCCESS.name(), second.getTaskStatus());
        assertEquals(first.getData(), second.getData(), "REPLAY 应回放上次业务结果");

        // body 未重跑
        assertEquals(3, stepService.getFlakyCount(), "REPLAY 后 flaky 不应再执行");
        assertEquals(1, stepService.getStepOneCount());
        assertEquals(1, stepService.getStepThreeCount());

        // 打卡数量仍为 3
        assertEquals(3, checkpointRepository.listCheckpoints(first.getTaskId()).size());
    }
}
