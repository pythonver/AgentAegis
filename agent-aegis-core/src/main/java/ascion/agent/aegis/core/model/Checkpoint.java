package ascion.agent.aegis.core.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class Checkpoint {
    // 所属运行实例 ID（TaskContext.taskId）
    private String taskId;

    // 步骤名称
    private String stepName;

    // 步骤状态
    @Builder.Default
    private StepStatus status = StepStatus.NOT_STARTED;

    // 步骤入参的JSON 序列化字符串
    private String inputPayload;

    // 步骤出参的 JSON 序列化字符串
    private String outputPayload;

    // 若失败，异常堆栈信息
    private String exceptionStack;

    // 步骤执行时长
    private long executionTimeMs;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
