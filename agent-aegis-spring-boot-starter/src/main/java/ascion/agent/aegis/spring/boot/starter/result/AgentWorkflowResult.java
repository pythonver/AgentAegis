package ascion.agent.aegis.spring.boot.starter.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Aegis Agent 工作流方法的统一响应包装类
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
     * Aegis 框架自动注入的任务唯一 ID
     */
    private String taskId;

    /**
     * 任务执行状态 (如 SUCCESS, FAILED, RUNNING)
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
