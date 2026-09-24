package ascion.agent.aegis.spring.boot.starter.registry;

import ascion.agent.aegis.spring.boot.starter.exception.DuplicateWorkflowException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workflow 定义侧注册表：workflowName（定义键）→ 入口方法描述。
 * 定义键与实例侧 TaskContext.name 对齐，供恢复链路反查。
 */
@AllArgsConstructor
@Getter
public class AgentWorkflowRegistry {

    // workflowName（定义键） -> WorkflowDescription
    private final Map<String, WorkflowDescription> workflows = new ConcurrentHashMap<>();

    /**
     * 按 workflow 定义键查询。
     *
     * @param workflowName 定义键：@AgentWorkflow.name()，空白时回落为方法名（与 TaskContext.name 一致）
     */
    public WorkflowDescription find(String workflowName) {
        return workflows.get(workflowName);
    }

    /**
     * 注册 workflow 定义；同名冲突为启动期定义冲突（非实例冲突，见 DuplicateWorkflowException）。
     */
    public void register(String workflowName, WorkflowDescription workflowDescription) {
        if (workflows.containsKey(workflowName)) {
            throw new DuplicateWorkflowException(
                String.format(">>> %s <<< already exists, please check if a workflow with the same name exists", workflowName)
            );
        }

        workflows.put(workflowName, workflowDescription);
    }

}
