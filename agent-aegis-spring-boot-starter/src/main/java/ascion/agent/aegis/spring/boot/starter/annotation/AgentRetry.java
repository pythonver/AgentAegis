package ascion.agent.aegis.spring.boot.starter.annotation;

import java.io.IOException;
import java.lang.annotation.*;
import java.util.concurrent.TimeoutException;

/**
 * Agent 重试注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentRetry {
    /**
     * 最大重试次数
     */
    int maxRetries() default 3;

    /**
     * 基础等待时间
     */
    long baseDelayMs() default 2000;

    /**
     * 最大等待时间
     */
    long maxDelayMs() default 60_000;

    /**
     * 退避因子
     */
    double backoffFactor() default 2.0d;

    /**
     * 允许重试的异常
     */
    Class<? extends Throwable>[] retryFor() default {
            IOException.class,
            TimeoutException.class,
            RuntimeException.class
    };

    /**
     * 不允许重试的异常
     */
    Class<? extends Throwable>[] noRetryFor() default {
            IllegalArgumentException.class,
            NullPointerException.class,
            IllegalStateException.class,
            SecurityException.class
    };

    /**
     * 降级/兜底处理方法名 (Fallback Method)
     * 重试次数耗尽后调用的兜底方法（方法签名需与原方法一致，或多一个 Throwable 入参）
     */
    String fallbackMethod() default "";


}
