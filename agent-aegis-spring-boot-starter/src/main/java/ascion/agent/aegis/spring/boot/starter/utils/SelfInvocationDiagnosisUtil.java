package ascion.agent.aegis.spring.boot.starter.utils;

import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;

public class SelfInvocationDiagnosisUtil {

    private static final Logger log = LoggerFactory.getLogger(SelfInvocationDiagnosisUtil.class);

    /**
     * 诊断当前方法调用是否可能存在 Spring AOP 自调用失效风险
     */
    public static void checkSelfInvocationDiagnosis(ProceedingJoinPoint pjp) {
        // 1. 只有在处于 @AgentWorkflow 的 Task 上下文中才需要诊断
        if (TaskContextHolder.getContext() == null) {
            return;
        }

        try {
            // 2. 尝试获取当前 Spring 容器中的代理对象
            Object currentProxy = AopContext.currentProxy();
            Object target = pjp.getTarget();

            // 3. 检查堆栈：判断当前方法调用的上层方法（Caller）是否来自于当前类本身
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            String currentClassName = target.getClass().getName();

            int selfCallCount = 0;
            for (StackTraceElement element : stackTrace) {
                // 如果堆栈中多次出现了当前类的方法，说明存在类内部的相互调用
                if (element.getClassName().equals(currentClassName)) {
                    selfCallCount++;
                }
            }

            // 如果在堆栈中发现当前类内部的方法调用了另一个内部方法
            if (selfCallCount > 1) {
                // 提示用户：确保使用的是 AopContext.currentProxy() 代理对象调用，而非 this.method()
                log.debug("ℹ️ [AgentAegis 诊断] 检测到类 [{}] 内部方法间调用 @AgentStep [{}]。" +
                                "请确保通过 ((Class) AopContext.currentProxy()).step() 进行代理调用，以保证切面生效。",
                        currentClassName, pjp.getSignature().getName());
            }

        } catch (IllegalStateException e) {
            // 防御性捕获：防止个别场景下未正确暴露代理对象
            log.warn("⚠️ [AgentAegis 警告] 无法获取当前 AOP 代理对象！" +
                            "请确认配置类上的 @EnableAspectJAutoProxy(exposeProxy = true) 是否生效，" +
                            "否则内部调用 @AgentStep 方法 [{}] 时切面将失效。",
                    pjp.getSignature().toShortString());
        }
    }
}
