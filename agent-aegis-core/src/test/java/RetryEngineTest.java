import ascion.agent.aegis.core.exception.AgentRetryExhaustedException;
import ascion.agent.aegis.core.retry.RetryConfig;
import ascion.agent.aegis.core.retry.RetryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class RetryEngineTest {

    private final Duration baseDelay = Duration.ofMillis(100);
    private final Duration maxDelay = Duration.ofSeconds(5);
    private final double backoffFactor = 2.0;

    @Test
    @DisplayName("边界测试：首次重试(attempt=1)时，延迟上限为 baseDelay，返回值在 [0, baseDelay] 范围内")
    void shouldReturnDelayWithinBaseDelayRange_whenFirstAttempt() {
        // attempt = 1 -> retryIndex = 0 -> exp = 2^0 = 1 -> cap = 100ms
        Duration delay = RetryEngine.calculateDelay(1, baseDelay, maxDelay, backoffFactor);

        assertThat(delay)
                .isNotNull()
                .isGreaterThanOrEqualTo(Duration.ZERO)
                .isLessThanOrEqualTo(baseDelay);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("容错测试：当 attempt <= 1 时，一律按照第 1 次重试(attempt=1)处理")
    void shouldHandleNonPositiveAttemptsAsFirstAttempt(int invalidAttempt) {
        // 当 attempt <= 0 时，retryIndex 应该被 Math.max(0, ...) 修正为 0，cap 同样为 100ms
        Duration delay = RetryEngine.calculateDelay(invalidAttempt, baseDelay, maxDelay, backoffFactor);

        assertThat(delay)
                .isGreaterThanOrEqualTo(Duration.ZERO)
                .isLessThanOrEqualTo(baseDelay);
    }

    @Test
    @DisplayName("指数退避测试：多次重试(attempt=3)时，cap 按指数递增，返回值在 [0, baseDelay * factor^2] 范围内")
    void shouldIncreaseCapExponentially_whenAttemptIncreases() {
        // attempt = 3 -> retryIndex = 2 -> exp = 2^2 = 4 -> cap = 400ms
        Duration expectedCap = Duration.ofMillis(400);

        Duration delay = RetryEngine.calculateDelay(3, baseDelay, maxDelay, backoffFactor);

        assertThat(delay)
                .isGreaterThanOrEqualTo(Duration.ZERO)
                .isLessThanOrEqualTo(expectedCap);
    }

    @Test
    @DisplayName("上限封顶测试：重试次数过大(attempt=10)时，计算出的延迟超过 maxDelay，cap 应截断为 maxDelay")
    void shouldCapDelayAtMaxDelay_whenExponentialDelayExceedsMax() {
        // attempt = 10 -> retryIndex = 9 -> 100ms * 2^9 = 51,200ms (> 5,000ms maxDelay) -> cap = 5,000ms
        Duration delay = RetryEngine.calculateDelay(10, baseDelay, maxDelay, backoffFactor);

        assertThat(delay)
                .isGreaterThanOrEqualTo(Duration.ZERO)
                .isLessThanOrEqualTo(maxDelay);
    }

    @Test
    @DisplayName("防溢出测试：极高的 attempt (例如 100) 不会导致指数计算溢出或崩溃")
    void shouldNotOverflow_whenAttemptIsVeryLarge() {
        // Math.min(retryIndex, 30) 会将指数限制在 30，确保计算安全
        Duration delay = RetryEngine.calculateDelay(100, baseDelay, maxDelay, backoffFactor);

        assertThat(delay)
                .isGreaterThanOrEqualTo(Duration.ZERO)
                .isLessThanOrEqualTo(maxDelay);
    }

    @Test
    @DisplayName("零延迟测试：当 baseDelay 为 ZERO 时，返回 Duration.ZERO")
    void shouldReturnZero_whenBaseDelayIsZero() {
        Duration delay = RetryEngine.calculateDelay(1, Duration.ZERO, maxDelay, backoffFactor);

        assertThat(delay).isEqualTo(Duration.ZERO);
    }

    // ==================== execute：耗尽与异常分类 ====================

    private RetryConfig zeroDelayConfig(int maxRetries) {
        return RetryConfig.builder()
                .maxRetries(maxRetries)
                .baseDelay(Duration.ZERO)
                .maxDelay(Duration.ZERO)
                .backoffFactor(backoffFactor)
                .retryFor(Set.of(RuntimeException.class))
                .noRetryFor(Set.of())
                .build();
    }

    @Test
    @DisplayName("execute 耗尽：可重试异常打满 maxRetries 后抛 AgentRetryExhaustedException，cause 为原异常")
    void execute_shouldThrowExhausted_whenRetriesDepleted() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = zeroDelayConfig(2);

        AgentRetryExhaustedException ex = assertThrows(
                AgentRetryExhaustedException.class,
                () -> RetryEngine.execute(() -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }, config)
        );

        assertTrue(ex.getMessage().contains("maxRetries=2"), ex.getMessage());
        assertNotNull(ex.getCause());
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(ex.getCause().getMessage()).isEqualTo("boom");
        // 1 次初试 + maxRetries 次重试
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute 不可重试：命中 noRetryFor 时直接抛原异常，不包装为 Exhausted")
    void execute_shouldThrowOriginal_whenNotRetryable() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = RetryConfig.builder()
                .maxRetries(5)
                .baseDelay(Duration.ZERO)
                .maxDelay(Duration.ZERO)
                .retryFor(Set.of(RuntimeException.class))
                .noRetryFor(Set.of(IllegalArgumentException.class))
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> RetryEngine.execute(() -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("非法入参");
                }, config)
        );

        assertThat(ex.getMessage()).isEqualTo("非法入参");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("execute 成功路径：首次成功不抛异常、不触发重试")
    void execute_shouldReturn_whenFirstAttemptSucceeds() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = zeroDelayConfig(3);

        String result = RetryEngine.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        }, config);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }
}
