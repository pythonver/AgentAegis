package ascion.agent.aegis.spring.boot.starter.service;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import org.springframework.stereotype.Service;

/**
 * 覆盖 AgentWorkflowAspect 全部分支所需的注解组合。
 */
@Service
public class TestWorkflowService {

    @AgentWorkflow(name = "named-workflow")
    public AgentWorkflowResult<String> processWithResult(String input) {
        return AgentWorkflowResult.of("processed: " + input);
    }

    @AgentWorkflow
    public String processRaw(String input) {
        return "raw: " + input;
    }

    @AgentWorkflow(conflictStrategy = AgentWorkflow.ConflictStrategy.REPLAY)
    public AgentWorkflowResult<String> processReplay(String input) {
        return AgentWorkflowResult.of("replay: " + input);
    }

    @AgentWorkflow(conflictStrategy = AgentWorkflow.ConflictStrategy.THROW_EXCEPTION)
    public AgentWorkflowResult<String> processThrowOnDuplicate(String input) {
        return AgentWorkflowResult.of("throw: " + input);
    }

    @AgentWorkflow(conflictStrategy = AgentWorkflow.ConflictStrategy.THROW_EXCEPTION, ignoreOutput = true)
    public String processIgnoreOutput(String input) {
        return "sensitive: " + input;
    }

    @AgentWorkflow(conflictStrategy = AgentWorkflow.ConflictStrategy.REPLAY, ignoreOutput = true)
    public String processInvalidReplayIgnoreOutput(String input) {
        return input;
    }

    @AgentWorkflow(maxRetries = 2)
    public String processAlwaysFail(String input) {
        throw new IllegalStateException("Biz Error");
    }

    @AgentWorkflow(zombieTimeoutSeconds = 1)
    public String processZombieTakeover(String input) {
        return "took-over: " + input;
    }

    @AgentWorkflow(name = "void-flow")
    public void processVoid() {
    }

    @AgentWorkflow
    public AgentWorkflowResult<String> processNullResult() {
        return null;
    }
}
