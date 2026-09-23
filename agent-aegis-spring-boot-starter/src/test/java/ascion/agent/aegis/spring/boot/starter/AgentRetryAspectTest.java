package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.exception.AgentRetryExhaustedException;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentRetryAspect;
import ascion.agent.aegis.spring.boot.starter.service.TestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {
        AgentRetryAspect.class,
        TestService.class
})
@EnableAspectJAutoProxy // 开启 AOP 代理
class AgentRetryAspectTest {

    @Autowired
    private TestService testService;

    @BeforeEach
    void setUp() {
        testService.resetCount();
    }

    @Test
    @DisplayName("场景 1：重试耗尽且未配置 fallback，应抛出 AgentRetryExhaustedException 并保留 cause")
    void testNoFallback_ShouldThrowExhaustedException() {
        AgentRetryExhaustedException exception = assertThrows(
                AgentRetryExhaustedException.class,
                () -> testService.doSomethingNoFallback("test")
        );

        assertTrue(exception.getMessage().contains("maxRetries=2"),
                "耗尽信息应包含 maxRetries: " + exception.getMessage());
        assertNotNull(exception.getCause(), "包装异常必须保留业务原异常作为 cause");
        assertEquals("业务执行失败", exception.getCause().getMessage());
        // maxRetries = 2，代表【1次初试 + 2次重试】= 3次调用
        assertEquals(3, testService.getCallCount());
    }

    @Test
    @DisplayName("场景 2：重试中途成功，应正常返回结果且不再继续重试")
    void testRetrySuccess_ShouldReturnResult() {
        String result = testService.doSomethingRetrySuccess("hello");

        assertEquals("Success: hello", result);
        assertEquals(3, testService.getCallCount()); // 前 2 次失败，第 3 次成功
    }

    @Test
    @DisplayName("场景 3：触发不带 Throwable 参数的降级方法")
    void testFallbackWithoutEx_ShouldInvokeFallback() {
        String result = testService.doSomethingWithFallback("world");

        assertEquals("Fallback: world", result);
        // maxRetries = 1，调用 2 次后触发降级
        assertEquals(2, testService.getCallCount());
    }

    @Test
    @DisplayName("场景 4：触发带 Throwable 参数的降级方法，且能正确传入异常对象")
    void testFallbackWithEx_ShouldPassThrowableToFallback() {
        String result = testService.doSomethingWithExFallback("hello");

        // 验证降级方法收到了业务抛出的 IllegalArgumentException
        assertEquals("FallbackWithEx: hello, Error: 参数不合法", result);
        assertEquals(1, testService.getCallCount());
    }

    @Test
    @DisplayName("场景 5：降级方法本身抛出异常，切面应拆包并抛出降级方法的真实异常")
    void testBadFallback_ShouldThrowCauseException() {
        // 验证捕捉到的不是 InvocationTargetException，而是被拆包后的 IllegalStateException
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> testService.doSomethingWithBadFallback("test")
        );

        assertEquals("降级逻辑内部崩溃", exception.getMessage());
    }

    @Test
    @DisplayName("场景 6：耗尽后触发带 Throwable 的降级，应收到业务原异常（cause）而非包装类")
    void testExhaustedFallback_ShouldReceiveOriginalCause() {
        String result = testService.doSomethingExhaustedWithExFallback("abc");

        // 降级拿到的是 cause（耗尽业务异常），不是 AgentRetryExhaustedException
        assertEquals("FallbackWithEx: abc, Error: 耗尽业务异常", result);
        // maxRetries = 1 → 初试 + 1 次重试 = 2 次调用后进入降级
        assertEquals(2, testService.getCallCount());
    }
}