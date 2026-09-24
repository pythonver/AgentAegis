package ascion.agent.aegis.spring.boot.starter.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调用 workflow 入口（定义侧）得到的统一响应包装：
 * 类名用 Workflow 表示「调用了哪个入口」；taskId/taskStatus 为本次运行实例（Task）信息。
 *
 * @param <T> 业务真实返回的数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentWorkflowResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本次运行实例的唯一 ID（Task 实例侧）
     */
    private String taskId;

    /**
     * 本次运行实例状态 (如 SUCCESS, FAILED, RUNNING)
     */
    private String taskStatus;

    /**
     * 真实的业务返回结果
     */
    private T data;


    public static <T> AgentWorkflowResult<T> of(T data) {
        AgentWorkflowResult<T> result = new AgentWorkflowResult<>();
        result.setData(data);
        return result;
    }

}
