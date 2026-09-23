package ascion.agent.aegis.spring.boot.starter.service;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentRetry;
import org.springframework.stereotype.Service;

@Service
public class TestService {

    private int callCount = 0;

    public void resetCount() {
        this.callCount = 0;
    }

    public int getCallCount() {
        return callCount;
    }

    // 场景 1：无降级，重试耗尽抛异常
    @AgentRetry(maxRetries = 2, baseDelayMs = 10, fallbackMethod = "")
    public String doSomethingNoFallback(String param) {
        callCount++;
        throw new RuntimeException("业务执行失败");
    }

    // 场景 2：重试 2 次后成功
    @AgentRetry(maxRetries = 3, baseDelayMs = 10)
    public String doSomethingRetrySuccess(String param) {
        callCount++;
        if (callCount < 3) {
            throw new RuntimeException("偶发异常");
        }
        return "Success: " + param;
    }

    // 场景 3：带有不含 Throwable 的降级方法
    @AgentRetry(maxRetries = 1, baseDelayMs = 10, fallbackMethod = "fallbackWithoutEx")
    public String doSomethingWithFallback(String param) {
        callCount++;
        throw new RuntimeException("触发降级");
    }

    public String fallbackWithoutEx(String param) {
        return "Fallback: " + param;
    }

    // 场景 4：带有含 Throwable 的降级方法
    @AgentRetry(maxRetries = 1, baseDelayMs = 10, fallbackMethod = "fallbackWithEx")
    public String doSomethingWithExFallback(String param) {
        callCount++;
        throw new IllegalArgumentException("参数不合法");
    }

    public String fallbackWithEx(String param, Throwable e) {
        return "FallbackWithEx: " + param + ", Error: " + e.getMessage();
    }

    // 场景 5：降级方法本身抛出异常
    @AgentRetry(maxRetries = 1, baseDelayMs = 10, fallbackMethod = "badFallback")
    public String doSomethingWithBadFallback(String param) {
        throw new RuntimeException("原始异常");
    }

    public String badFallback(String param, Throwable e) {
        throw new IllegalStateException("降级逻辑内部崩溃");
    }

    // 场景 6：可重试异常耗尽后触发带 Throwable 的降级，降级应收到业务原异常而非包装类
    @AgentRetry(maxRetries = 1, baseDelayMs = 10, fallbackMethod = "fallbackWithEx")
    public String doSomethingExhaustedWithExFallback(String param) {
        callCount++;
        throw new RuntimeException("耗尽业务异常");
    }
}