package ascion.agent.aegis.spring.boot.starter.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentWorkflow {

    /**
     * workflow 定义键（Registry 键；写入 TaskContext.name）。空白则扫描/切面回落为方法名。
     */
    String name() default "";


    /**
     * 同一运行实例（taskId）已 SUCCESS 时的行为（切面仅此一条再入策略）：
     * - REPLAY：使用上一次的结果反序列化返回，不落库
     * - THROW_EXCEPTION：抛出 TaskAlreadyExistsException 阻止重复提交
     * <p>
     * RUNNING/FAILED 再进入一律按重跑，不受本策略影响；整单重跑次数上限不做限制
     * （执行内重试见 {@code @AgentRetry}）。
     */
    ConflictStrategy conflictStrategy() default ConflictStrategy.REPLAY;


    /**
     * 运行实例僵尸判定阈值（单位：秒，默认 300 秒）。
     * 仅供后续后台清扫任务使用；入口切面不读取、不做僵尸接管。
     */
    long zombieTimeoutSeconds() default 300;


    // 成功时不写出参（实例 outputPayload）
    boolean ignoreOutput() default false;

    /**
     * 是否开启快速失败
     * 当内部存在一个 AgentStep 失败时，将本实例 TaskStatus 置为 FAILED
     */
    boolean failFast() default true;


    enum ConflictStrategy {
        // 使用结果
        REPLAY,

        // 抛异常
        THROW_EXCEPTION
    }
}
