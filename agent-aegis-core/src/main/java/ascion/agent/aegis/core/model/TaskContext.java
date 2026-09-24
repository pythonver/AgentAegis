package ascion.agent.aegis.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// workflow 一次运行的实例上下文（Task = 实例侧词汇；Workflow 定义见 starter 注解/Registry）
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskContext {

    // 共享的单例 ObjectMapper，配置好时间模块
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    // 实例唯一 ID（taskId）
    private String taskId;

    /**
     * 本实例对应的 workflow 定义键（@AgentWorkflow.name()，空白时为方法名），
     * 非实例自定义名；与 AgentWorkflowRegistry 键对齐，供恢复反查定义。
     */
    private String name;

    // 实例状态
    private TaskStatus status;

    @Builder.Default
    private Integer retries = 0 ;

    // 实例入参（进入 workflow 入口时的参数快照）
    private String inputPayload;

    // 实例出参
    private String outputPayload;

    // 实例元数据
    private String metadata;

    // 实例创建时间
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 实例最后更新时间
    @Builder.Default
    private Instant updatedAt = Instant.now();


    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(this);
    }


    public static TaskContext fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, TaskContext.class);
    }
}
