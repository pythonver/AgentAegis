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

// 任务上下文对象
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

    // 任务id
    private String taskId;

    // 任务名称
    private String name;

    // 任务状态
    private TaskStatus status;

    @Builder.Default
    private Integer retries = 0 ;

    // 任务输入
    private String inputPayload;

    // 任务输出
    private String outputPayload;

    // 任务元数据
    private String metadata;

    // 任务创建时间
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 任务最后更新时间
    @Builder.Default
    private Instant updatedAt = Instant.now();


    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(this);
    }


    public static TaskContext fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, TaskContext.class);
    }
}
