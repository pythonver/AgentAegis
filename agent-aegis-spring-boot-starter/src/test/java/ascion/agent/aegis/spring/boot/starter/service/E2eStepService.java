package ascion.agent.aegis.spring.boot.starter.service;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentRetry;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentStep;
import org.springframework.stereotype.Component;

/**
 * E2E 线性工作流步骤夹具：独立 Bean，避免 Workflow 内 this 调用导致切面失效。
 */
@Component
public class E2eStepService {

    private int flakyCount = 0;
    private int stepOneCount = 0;
    private int stepThreeCount = 0;

    public void reset() {
        flakyCount = 0;
        stepOneCount = 0;
        stepThreeCount = 0;
    }

    public int getFlakyCount() {
        return flakyCount;
    }

    public int getStepOneCount() {
        return stepOneCount;
    }

    public int getStepThreeCount() {
        return stepThreeCount;
    }

    @AgentStep(name = "step_one")
    public String stepOne(String input) {
        stepOneCount++;
        return "one:" + input;
    }

    /**
     * 前 2 次失败，第 3 次成功 → 验证 @AgentRetry 在 @AgentStep 包裹内真正重试。
     */
    @AgentStep(name = "flaky")
    @AgentRetry(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 2, fallbackMethod = "")
    public String flakyStep(String input) {
        flakyCount++;
        if (flakyCount < 3) {
            throw new RuntimeException("flaky-第" + flakyCount + "次偶发失败");
        }
        return "flaky:" + input;
    }

    /**
     * 恒失败 → 重试耗尽抛 AgentRetryExhaustedException，Workflow 应标 FAILED。
     */
    @AgentStep(name = "always_fail")
    @AgentRetry(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 2, fallbackMethod = "")
    public String alwaysFailStep(String input) {
        throw new RuntimeException("always-fail:" + input);
    }

    @AgentStep(name = "step_three")
    public String stepThree(String input) {
        stepThreeCount++;
        return "three:" + input;
    }
}
