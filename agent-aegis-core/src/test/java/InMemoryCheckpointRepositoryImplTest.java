import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.impl.InMemoryCheckpointRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

public class InMemoryCheckpointRepositoryImplTest {

    private InMemoryCheckpointRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCheckpointRepositoryImpl();
    }

    @Test
    @DisplayName("测试 TaskContext 的保存与查询")
    void testSaveAndFindTask() {
        TaskContext context = TaskContext.builder()
                .taskId("task-abc")
                .status(TaskStatus.RUNNING)
                .build();

        repository.saveTask(context);

        Optional<TaskContext> found = repository.findTaskById("task-abc");
        assertThat(found).isPresent();
        assertThat(found.get().getTaskId()).isEqualTo("task-abc");
    }

    @Test
    @DisplayName("核心验证：连续打卡多个 Step，防覆盖与状态隔离")
    void testSaveMultipleCheckpointsWithoutOverwriting() {
        String taskId = "task-flow-1";

        Checkpoint step1 = Checkpoint.builder()
                .taskId(taskId)
                .stepName("step1_analyze")
                .status(StepStatus.SUCCESS)
                .outputPayload("{\"result\":\"ok\"}")
                .build();

        Checkpoint step2 = Checkpoint.builder()
                .taskId(taskId)
                .stepName("step2_llm_call")
                .status(StepStatus.RUNNING)
                .build();

        // 写入 Step 1
        repository.saveCheckpoint(step1);
        // 写入 Step 2 (关键点：验证 Step 2 不会把 Step 1 冲掉)
        repository.saveCheckpoint(step2);

        // 1. 单独查询 Step 1
        Optional<Checkpoint> foundStep1 = repository.findCheckpoint(taskId, "step1_analyze");
        assertThat(foundStep1).isPresent();
        assertThat(foundStep1.get().getStatus()).isEqualTo(StepStatus.SUCCESS);

        // 2. 单独查询 Step 2
        Optional<Checkpoint> foundStep2 = repository.findCheckpoint(taskId, "step2_llm_call");
        assertThat(foundStep2).isPresent();
        assertThat(foundStep2.get().getStatus()).isEqualTo(StepStatus.RUNNING);

        // 3. 列表查询，应包含 2 个 Checkpoint
        List<Checkpoint> checkpoints = repository.listCheckpoints(taskId);
        assertThat(checkpoints.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试空参数防御性拦截")
    void testNullCheckValidations() {
        assertThatThrownBy(() -> repository.saveTask(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> repository.saveCheckpoint(null))
                .isInstanceOf(NullPointerException.class);

        // 查询不存在的 Task，应优雅返回 Optional.empty() / EmptyList
        assertThat(repository.findTaskById("not-exist")).isEmpty();
        assertThat(repository.listCheckpoints("not-exist").isEmpty()).isEqualTo(true);
    }

}
