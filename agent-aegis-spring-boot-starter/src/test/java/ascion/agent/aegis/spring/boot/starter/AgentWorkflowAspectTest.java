package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.exception.MaxRetriesExceededException;
import ascion.agent.aegis.spring.boot.starter.exception.TaskAlreadyExistsException;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import ascion.agent.aegis.spring.boot.starter.service.TestWorkflowService;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Rollback(false) // 允许数据真正 COMMIT 到 MySQL
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 强制使用真实配置的数据库
class AgentWorkflowAspectTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private TestWorkflowService testWorkflowService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @AfterEach
    void tearDown() {
        TaskContextHolder.clear();
    }

    @Test
    @DisplayName("[DB真实测试] 首次执行：持久化记录应正确落盘为 SUCCESS 且包含 OutputPayload")
    void testExecute_DatabasePersistenceSuccess() throws Exception {
        // 1. 调用 AOP 代理的 Service 方法
        AgentWorkflowResult<String> result = testWorkflowService.processWithResult("DB_Test_Input");

        // 2. 校验返回值中的 taskId 和状态
        assertNotNull(result);
        assertNotNull(result.getTaskId(), "切面生成的 taskId 不应为空");
        assertEquals("SUCCESS", result.getTaskStatus());
        assertEquals("processed: DB_Test_Input", result.getData());

        // 3. 从数据库直接查询记录，验证落盘数据真实性
        Optional<TaskContext> taskInDbOpt = checkpointRepository.findTaskById(result.getTaskId());
        assertTrue(taskInDbOpt.isPresent(), "数据库中应该能够查询到该任务");

        TaskContext taskInDb = taskInDbOpt.get();
        assertEquals(TaskStatus.SUCCESS, taskInDb.getStatus(), "DB 中状态应为 SUCCESS");
        assertNotNull(taskInDb.getOutputPayload(), "DB 中应该持久化了输出 Payload");

        // 校验反序列化出来的 Payload
        AgentWorkflowResult<?> payloadResult = SerializeUtil.getMapper()
                .readValue(taskInDb.getOutputPayload(), AgentWorkflowResult.class);
        assertEquals("processed: DB_Test_Input", payloadResult.getData());
    }

    @Test
    @DisplayName("[DB真实测试] REPLAY 重放模式：读取 DB 记录，不再重新执行业务代码")
    void testReplay_FromDatabase() throws Exception {
        // 1. 模拟在数据库中先插入一条已经成功的 Task 记录
        String mockTaskId = "task_replay_db_001";
        AgentWorkflowResult<String> originalResult = AgentWorkflowResult.of("replay: DB_Test_Input");
        String payloadJson = SerializeUtil.getMapper().writeValueAsString(originalResult);

        TaskContext existingTask = TaskContext.builder()
                .taskId(mockTaskId)
                .name("processReplay")
                .status(TaskStatus.SUCCESS)
                .outputPayload(payloadJson)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .retries(0)
                .build();

        // 写入真实数据库
        checkpointRepository.saveTask(existingTask);

        // 2. 将此 taskId 设置到 ThreadLocal 中，模拟传入此任务 ID
        TaskContextHolder.setContext(TaskContext.builder().taskId(mockTaskId).build());

        // 3. 执行支持 REPLAY 的工作流方法
        AgentWorkflowResult<String> replayedResult = testWorkflowService.processReplay("DB_Test_Input");

        // 4. 断言直接从 DB 反序列化并补全了最新的元数据
        assertNotNull(replayedResult);
        assertEquals(mockTaskId, replayedResult.getTaskId());
        assertEquals("SUCCESS", replayedResult.getTaskStatus());
        assertEquals("replay: DB_Test_Input", replayedResult.getData());
    }

    @Test
    @DisplayName("[DB真实测试] 并发防重：处于 RUNNING 状态的真实任务阻止重复提交")
    void testRunningTask_DatabaseLock() {
        String mockTaskId = "task_running_db_002";

        // 在 DB 中插入一条正在运行的任务
        TaskContext runningTask = TaskContext.builder()
                .taskId(mockTaskId)
                .name("processReplay")
                .status(TaskStatus.RUNNING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now()) // 当前时刻，未超时
                .build();
        checkpointRepository.saveTask(runningTask);

        // 设置当前请求的 taskId 相同
        TaskContextHolder.setContext(TaskContext.builder().taskId(mockTaskId).build());

        // 再次触发，应该触发 DB 状态阻断并抛出异常
        assertThrows(TaskAlreadyExistsException.class, () -> {
            testWorkflowService.processWithResult("DB_Test_Input");
        });
    }
}