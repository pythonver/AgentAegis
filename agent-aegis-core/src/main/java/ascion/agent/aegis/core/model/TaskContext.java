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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // 任务状态
    private TaskStatus status;

    // 任务元数据
    @Builder.Default
    private Map<String, Object> metadata = new ConcurrentHashMap<>();

    // 任务创建时间
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 任务最后更新时间
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public void setVariable(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new ConcurrentHashMap<>();
        }
        if (value != null) {
            this.metadata.put(key, value);
        } else {
            this.metadata.remove(key);
        }
        this.updatedAt = Instant.now();
    }

    /**
     * 类型安全的变量获取方法 (新增便利方法)
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key) {
        if (this.metadata == null) {
            return null;
        }
        return (T) this.metadata.get(key);
    }


    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(this);
    }


    public static TaskContext fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, TaskContext.class);
    }
}
