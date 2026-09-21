import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class TaskContextTest {
    @Test
    @DisplayName("验证 TaskContext 默认初始化与变量存取")
    void testVariableSetAndGet() {
        TaskContext context = TaskContext.builder()
                .taskId("task-1001")
                .status(TaskStatus.RUNNING)
                .build();

        context.setVariable("userId", "user-888");
        context.setVariable("retryCount", 3);

        assertThat(context.getTaskId()).isEqualTo("task-1001");
        assertThat(context.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(context.<String>getVariable("userId")).isEqualTo("user-888");
        assertThat(context.<Integer>getVariable("retryCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("验证 JSON 序列化与反序列化无损恢复")
    void testJsonSerialization() throws Exception {
        TaskContext original = TaskContext.builder()
                .taskId("task-2002")
                .status(TaskStatus.SUCCESS)
                .build();
        original.setVariable("query", "Agent Architecture");

        // 1. 序列化为 JSON 字符串
        String json = original.toJson();
        assertThat(json).contains("task-2002").contains("Agent Architecture");

        // 2. 反序列化还原对象
        TaskContext restored = TaskContext.fromJson(json);

        assertThat(restored.getTaskId()).isEqualTo(original.getTaskId());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.<String>getVariable("query")).isEqualTo("Agent Architecture");
    }
}
