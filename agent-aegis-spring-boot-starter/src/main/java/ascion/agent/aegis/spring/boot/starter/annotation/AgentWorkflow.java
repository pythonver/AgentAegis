package ascion.agent.aegis.spring.boot.starter.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentWorkflow {

    // 工作流名称
    String name() default "";


    /**
     * 幂等与重试策略：当检测到同名 taskId 已完成 (SUCCESS) 时的行为
     * - REPLAY：使用上一次的结果反序列化
     * - THROW_EXCEPTION: 抛出 TaskAlreadyExistsException 阻止重复提交
     */
    ConflictStrategy conflictStrategy() default ConflictStrategy.REPLAY;


    /**
     * 最大重试次数（默认为 3 次）
     */
    int maxRetries() default 3;

    /**
     * 僵尸任务超时判断阈值（单位：秒，默认 300 秒 = 5 分钟）
     * 超过此时间没有更新且处于 RUNNING 状态，判定为崩溃挂掉，可触发接管重试
     */
    long zombieTimeoutSeconds() default 300;


    // 是否忽略输出
    boolean ignoreOutput() default false;

    /**
     * 是否开启快速失败
     * 当内部存在一个 AgentStep 失败时，将工作流状态置为Fail
     */
    boolean failFast() default true;


    enum ConflictStrategy {
        // 使用结果
        REPLAY,

        // 抛异常
        THROW_EXCEPTION
    }
}
