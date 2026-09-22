package ascion.agent.aegis.spring.boot.starter.service;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import org.springframework.stereotype.Service;

@Service
public class TestWorkflowService {

    @AgentWorkflow
    public AgentWorkflowResult<String> processWithResult(String input) {
        // 业务只管成功返回数据，不需要传 taskId
        return AgentWorkflowResult.of("processed: " + input);
    }

    @AgentWorkflow
    public String processRaw(String input) {
        // 返回纯业务对象
        return "raw: " + input;
    }

    @AgentWorkflow(conflictStrategy = AgentWorkflow.ConflictStrategy.REPLAY)
    public AgentWorkflowResult<String> processReplay(String input) {
        return AgentWorkflowResult.of("replay: " + input);
    }

    @AgentWorkflow(maxRetries = 2)
    public String processWithException(String input) {
        throw new RuntimeException("Biz Error");
    }
}