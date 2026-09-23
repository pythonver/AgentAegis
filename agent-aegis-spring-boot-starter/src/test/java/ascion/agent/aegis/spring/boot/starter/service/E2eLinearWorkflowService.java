package ascion.agent.aegis.spring.boot.starter.service;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * E2E 线性工作流入口：串联多个 @AgentStep（其一叠加 @AgentRetry）。
 */
@Service
public class E2eLinearWorkflowService {

    @Autowired
    private E2eStepService steps;

    @AgentWorkflow(name = "e2e-linear")
    public AgentWorkflowResult<String> runLinear(String input) {
        String one = steps.stepOne(input);
        String flaky = steps.flakyStep(one);
        String three = steps.stepThree(flaky);
        return AgentWorkflowResult.of(one + "|" + flaky + "|" + three);
    }

    /**
     * step_one 成功后 always_fail 耗尽失败 → 后续 step 不应执行，任务 FAILED。
     */
    @AgentWorkflow(name = "e2e-fail", maxRetries = 1, conflictStrategy = AgentWorkflow.ConflictStrategy.THROW_EXCEPTION)
    public AgentWorkflowResult<String> runFailing(String input) {
        String one = steps.stepOne(input);
        steps.alwaysFailStep(one);
        steps.stepThree(one);
        return AgentWorkflowResult.of(one);
    }
}
