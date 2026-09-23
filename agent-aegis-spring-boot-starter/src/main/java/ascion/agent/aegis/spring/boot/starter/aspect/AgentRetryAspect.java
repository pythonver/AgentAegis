package ascion.agent.aegis.spring.boot.starter.aspect;

import ascion.agent.aegis.core.exception.AgentRetryExhaustedException;
import ascion.agent.aegis.core.retry.RetryConfig;
import ascion.agent.aegis.core.retry.RetryEngine;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentRetry;
import ascion.agent.aegis.spring.boot.starter.resolve.FallbackResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

@Aspect
@Order(200)
public class AgentRetryAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentRetryAspect.class);


    @Pointcut("@annotation(agentRetry)")
    public void agentRetryPointCut(AgentRetry agentRetry) {}

    @Around("@annotation(agentRetry)")
    public Object aspect(ProceedingJoinPoint pjp, AgentRetry agentRetry) throws Throwable {
        RetryConfig retryConfig = buildRetryConfig(agentRetry);

        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        try {
            return RetryEngine.execute(pjp::proceed, retryConfig);
        }catch (Throwable e){
            // 如果没有降级策略
            if (agentRetry.fallbackMethod().isBlank()){
                throw e;
            }

            // 查找降级方法并返回
            Method fallbackMethod = FallbackResolver.resolve(AopUtils.getTargetClass(pjp.getTarget()), method, agentRetry.fallbackMethod());
            Object[] args = pjp.getArgs();
            // fallback 多一个 Throwable 参数
            if (fallbackMethod.getParameterCount() == args.length + 1) {
                args = Arrays.copyOf(args, args.length + 1);

                if (e instanceof AgentRetryExhaustedException){
                    args[args.length - 1] = e.getCause();
                }else {
                    args[args.length - 1] = e;
                }


            }

            try {
                return fallbackMethod.invoke(pjp.getTarget(), args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }

    private RetryConfig buildRetryConfig(AgentRetry agentRetry) {
        return RetryConfig.builder()
                .maxRetries(agentRetry.maxRetries())
                .baseDelay(Duration.ofMillis(agentRetry.baseDelayMs()))
                .maxDelay(Duration.ofMillis(agentRetry.maxDelayMs()))
                .backoffFactor(agentRetry.backoffFactor())
                .retryFor(Set.of(agentRetry.retryFor()))
                .noRetryFor(Set.of(agentRetry.noRetryFor()))
                .build();
    }


}
