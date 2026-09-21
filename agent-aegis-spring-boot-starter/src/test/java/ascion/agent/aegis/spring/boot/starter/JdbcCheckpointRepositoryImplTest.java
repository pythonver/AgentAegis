package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.spring.boot.starter.repository.JdbcCheckpointRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@JdbcTest
//@Rollback(false)
//@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
//@TestPropertySource(properties = {
//        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
//        "spring.datasource.url=jdbc:mysql://localhost:3306/agent_aegis_test",
//        "spring.datasource.username=ascion",
//        "spring.datasource.password=ko50314Vc++nt",
//        "spring.sql.init.mode=always",
//        "spring.sql.init.schema-locations=classpath:schema-mysql.sql"
//})
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.schema-locations=classpath:schema-mysql.sql"
})
class JdbcCheckpointRepositoryImplTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcCheckpointRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcCheckpointRepositoryImpl(jdbcTemplate);
    }

    @Test
    @DisplayName("测试 TaskContext 的保存与查询 (Upsert)")
    void testSaveAndFindTask() {
        // 1. 新增 Task
        TaskContext context = TaskContext.builder()
                .taskId("task-1001")
                .status(TaskStatus.RUNNING)
                .build();
        context.setVariable("environment", "test");

        repository.saveTask(context);

        Optional<TaskContext> found = repository.findTaskById("task-1001");
        assertThat(found).isPresent();
        assertThat(found.get().getTaskId()).isEqualTo("task-1001");
        assertThat(found.get().getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(found.get().<String>getVariable("environment")).isEqualTo("test");

        // 2. 更新 Task (验证 ON DUPLICATE KEY UPDATE)
        context.setStatus(TaskStatus.SUCCESS);
        context.setVariable("retryCount", 2);
        repository.saveTask(context);

        Optional<TaskContext> updated = repository.findTaskById("task-1001");
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(updated.get().<Integer>getVariable("retryCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("验证 Checkpoint 联合唯一约束 (uk_task_step) 与 Upsert 更新")
    void testSaveAndListCheckpoints() {
        String taskId = "task-2002";

        // 1. 插入 Step 1
        Checkpoint step1 = Checkpoint.builder()
                .taskId(taskId)
                .stepName("step1_init")
                .status(StepStatus.RUNNING)
                .inputPayload("{\"param\":\"value\"}")
                .build();
        repository.saveCheckpoint(step1);

        // 2. 插入 Step 2
        Checkpoint step2 = Checkpoint.builder()
                .taskId(taskId)
                .stepName("step2_process")
                .status(StepStatus.NOT_STARTED)
                .build();
        repository.saveCheckpoint(step2);

        // 验证当前有两个 Step
        List<Checkpoint> listBefore = repository.listCheckpoints(taskId);
        assertThat(listBefore.size()).isEqualTo(2);

        // 3. 对相同的 (task_id, step_name) 再次保存（更新 Step 1 的状态和出参）
        step1.setStatus(StepStatus.SUCCESS);
        step1.setOutputPayload("{\"result\":\"ok\"}");
        step1.setExecutionTime(150L);
        repository.saveCheckpoint(step1);

        // 4. 验证数量依然是 2，且 Step 1 的内容被成功覆盖更新
        List<Checkpoint> listAfter = repository.listCheckpoints(taskId);
        assertThat(listAfter.size()).isEqualTo(2);

        Optional<Checkpoint> foundStep1 = repository.findCheckpoint(taskId, "step1_init");
        assertThat(foundStep1).isPresent();
        assertThat(foundStep1.get().getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(foundStep1.get().getOutputPayload()).isEqualTo("{\"result\":\"ok\"}");
        assertThat(foundStep1.get().getExecutionTime()).isEqualTo(150L);
    }

    @Test
    @DisplayName("验证查询不存在的数据时优雅返回 Optional.empty() 与空列表")
    void testFindNonExistentData() {
        Optional<TaskContext> task = repository.findTaskById("not-exist");
        assertThat(task).isEmpty();

        Optional<Checkpoint> checkpoint = repository.findCheckpoint("not-exist", "step1");
        assertThat(checkpoint).isEmpty();

        List<Checkpoint> checkpoints = repository.listCheckpoints("not-exist");
        assertThat(checkpoints.isEmpty()).isEqualTo(true);
    }
}