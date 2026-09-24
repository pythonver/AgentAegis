package ascion.agent.aegis.spring.boot.starter.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentStep {

    /**
     * 步骤名
     */
    String name() default "";

    /**
     * 步骤详细描述
     */
    String description() default "";

    /**
     * 是否开启断点回放
     * - true（默认）：若数据库中已存在该 (taskId, stepName) 且状态为 SUCCESS，直接反序列化 DB 中的 output_json 返回，跳过方法真实执行。
     * - false：即使历史已成功，每次运行该 workflow 实例链时仍强行重新调用该步骤。
     */
    boolean replay() default true;


    /**
     * 步骤超时
     */
    long timeout() default 0L;

    /**
     * 超时时间单位，默认秒
     */
    TimeUnit timeoutUnit() default TimeUnit.SECONDS;

    /**
     * 忽略返回结果
     */
    boolean ignoreOutput() default false;


}
