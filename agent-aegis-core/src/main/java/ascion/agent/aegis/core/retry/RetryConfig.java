package ascion.agent.aegis.core.retry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Duration;
import java.util.Set;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class RetryConfig {
    // 最大重试次数
    @Builder.Default
    private int maxRetries = 1;

    // 基础重试延迟时长
    @Builder.Default
    private Duration baseDelay = Duration.ofMillis(2000);

    // 最大重试延迟时长
    @Builder.Default
    private Duration maxDelay = Duration.ofMillis(30_000);

    // 指数因子
    @Builder.Default
    private double backoffFactor = 2.0;

    // 允许重试异常集合
    @Builder.Default
    private Set<Class<? extends Throwable>> retryFor = Set.of();

    // 不允许重试异常集合
    @Builder.Default
    private Set<Class<? extends Throwable>> noRetryFor = Set.of();

    public static RetryConfig defaultConfig() {
        return RetryConfig.builder().build();
    }
}
