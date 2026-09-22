package ascion.agent.aegis.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 重试引擎
 */
public class RetryEngine  {

    /**
     * Full Jitter 全抖动指数退避算法
     * @param attempt 当前重试次数
     * @param baseDelay 基础等待时间
     * @param maxDelay 最大等待时间
     * @param backoffFactor 指数增长因子
     * @return 下一次延迟时间
     */
    public static Duration calculateDelay(int attempt, Duration baseDelay, Duration maxDelay, double backoffFactor){
        // 假设 attempt 从 1 开始，做 0-based 偏移修正
        int retryIndex = Math.max(0, attempt - 1);
        double exp = Math.pow(backoffFactor, Math.min(retryIndex, 30));

        // 计算当前重试轮次的延迟上限 cap
        double rawDelay = baseDelay.toMillis() * exp;
        long cap = (long) Math.min(maxDelay.toMillis(), rawDelay);

        if (cap <= 0) {
            return Duration.ZERO;
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, cap + 1);

        return Duration.ofMillis(jitter);
    };

    public static Duration calculateDelay(int attempt, Duration baseDelay, Duration maxDelay){
        return calculateDelay(attempt, baseDelay, maxDelay, 2.0);
    }

    public static Duration calculateDelay(int attempt, RetryConfig config){
        Objects.requireNonNull(config, "retryConfig 不能为 null");
        return calculateDelay(attempt, config.getBaseDelay(), config.getMaxDelay(), config.getBackoffFactor());
    }
    public static Duration calculateDelay(int attempt){
        return calculateDelay(attempt, RetryConfig.defaultConfig());
    }

    /**
     * 是否允许重试
     * <ul>
     *     <li>若 noRetryFor 中匹配到该异常或其父类，优先返回 false；</li>
     *     <li>若 retryFor 非空，仅当匹配到其中的类型或其子类时返回 true，否则返回 false；</li>
     *     <li>若 retryFor 为空，默认对除 noRetryFor 外的所有 Exception 返回 true。</li>
     * </ul>
     * @param throwable 目标异常
     * @param retryFor 允许重试的异常
     * @param noRetryFor 不允许重试的异常
     * @return 是否允许重试
     */
    public static boolean isRetryable(Throwable throwable, Set<Class<? extends Throwable>> retryFor, Set<Class<? extends Throwable>> noRetryFor){
        if (throwable == null) return false;

        // 不重试
        if (noRetryFor != null && !noRetryFor.isEmpty()){
            for (Class<? extends Throwable> noRetryForCls : noRetryFor) {
                if (noRetryForCls.isInstance(throwable)) return false;
            }
        }


        // reTryFor 非空：只匹配retryFor里的子类/子类才允许 重试
        if (retryFor != null && !retryFor.isEmpty()) {
            for (Class<? extends Throwable> cls : retryFor) {
                if (cls.isInstance(throwable)) return true;
            }
            return false;
        }
        return throwable instanceof Exception;
    }

    /**
     * 重试引擎执行器，含退避算法执行
     * @param task 目标任务
     * @param retryConfig 重试配置
     * @return 任务执行结果
     * @param <T> 任务执行结果类型
     * @throws Throwable 异常
     */
    public static <T> T execute(RetryTask<T> task, RetryConfig retryConfig) throws Throwable {
        int attempt = 0;

        while (true) {
            try {
                attempt++;
                return task.run();
            }catch (Throwable t) {
                if (attempt > retryConfig.getMaxRetries() || !isRetryable(t,  retryConfig.getRetryFor(), retryConfig.getNoRetryFor())) {
                    throw t;
                }

                long delay = calculateDelay(attempt, retryConfig).toMillis();
                if (delay > 0 ) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay);
                    }catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }

}
