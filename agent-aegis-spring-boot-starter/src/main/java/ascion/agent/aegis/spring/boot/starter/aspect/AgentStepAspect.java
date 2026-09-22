package ascion.agent.aegis.spring.boot.starter.aspect;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentStep;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil.safeSerialize;
import static ascion.agent.aegis.spring.boot.starter.utils.StackTraceUtil.getStackTraceAsString;

/**
 * agent步骤切面
 */
@Aspect
@Component
@Order(100)
public class AgentStepAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentStepAspect.class);

    private static final ExecutorService TIMEOUT_EXECUTOR = TtlExecutors.getTtlExecutorService(
            Executors.newCachedThreadPool()
    );
    /**
     * 由 Spring 决定注入
     */
    @Autowired
    private CheckpointRepository checkpointRepository;


    @Around("@annotation(agentStep)")
    public Object agentStepAspect(ProceedingJoinPoint pjp, AgentStep agentStep) throws Throwable {

        checkSelfInvocationDiagnosis(pjp);

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String stepName = StringUtils.hasText(agentStep.name()) ? agentStep.name() : signature.getMethod().getName();

        // 拦截当前task 和 step，去数据库查询当前步骤的执行状态
        String taskId = TaskContextHolder.getTaskId();

        Optional<Checkpoint> checkpoint = checkpointRepository.findCheckpoint(taskId, stepName);

        // 如果SUCCESS，根据是否重放选择重新执行或者直接反序列会结果返回
        if (checkpoint.isPresent() && checkpoint.get().getStatus().equals(StepStatus.SUCCESS) && agentStep.replay()) {
            if (void.class.equals(signature.getReturnType()) || Void.class.equals(signature.getReturnType())) {
                return null;
            }

            String outputPayload = checkpoint.get().getOutputPayload();
            //
            if (!StringUtils.hasText(outputPayload)) {
                return null;
            }

            // 反序列化结果返回（完美支持泛型）
            Type returnType = signature.getMethod().getGenericReturnType();
            return SerializeUtil.getMapper().readValue(
                    outputPayload,
                    SerializeUtil.getMapper().getTypeFactory().constructType(returnType)
            );
        }

        // 重跑
        Checkpoint build ;
        if (checkpoint.isPresent()) {
            build = checkpoint.get();

        }else {

            Object[] args = pjp.getArgs();
            String argsJson = safeSerialize(args);

            build = Checkpoint.builder()
                    .taskId(taskId)
                    .stepName(stepName)
                    .inputPayload(argsJson)
                    .build();
        }
        build.setStatus(StepStatus.RUNNING);
        // 先落库一次
        checkpointRepository.saveCheckpoint(build);

        // 超时控制
        long timeout = agentStep.timeout();
        if (timeout <= 0) {
            return executeAndSave(pjp, agentStep, build);
        }

        return executeSync(pjp, agentStep, build, stepName);
    }

    /**
     * 无超时控制时的普通执行流程
     */
    private Object executeAndSave(ProceedingJoinPoint pjp, AgentStep agentStep, Checkpoint build) throws Throwable {
        Instant start = Instant.now();
        try {
            Object result = pjp.proceed();
            Instant end = Instant.now();

            build.setStatus(StepStatus.SUCCESS);
            if (agentStep.ignoreOutput()) {
                build.setOutputPayload(null);
            } else {
                build.setOutputPayload(result != null ? SerializeUtil.getMapper().writeValueAsString(result) : null);
            }
            build.setExecutionTime(Duration.between(start, end).toMillis());
            checkpointRepository.saveCheckpoint(build);
            return result;
        } catch (Throwable e) {
            build.setStatus(StepStatus.FAILURE);
            build.setExceptionStack(getStackTraceAsString(e));
            checkpointRepository.saveCheckpoint(build);
            throw e;
        }
    }

    private Object executeSync(ProceedingJoinPoint pjp, AgentStep agentStep, Checkpoint build, String stepName) throws Throwable {

        long timeout = agentStep.timeout();
        // 标记任务是否中断
        AtomicBoolean isTimedOut = new AtomicBoolean(false);


        FutureTask<Object> task = new FutureTask<>(()-> {
            try {
                Instant start = Instant.now();
                Object result = pjp.proceed();

                // 响应点, 线程是否属于中断
                if (Thread.currentThread().isInterrupted() || isTimedOut.get()) {
                    log.warn("⚠️ [AgentAegis] Step [{}] 执行完成，但任务已被标记超时/中断，放弃结果落库。", stepName);
                    throw new InterruptedException("AgentStep [" + stepName + "] 已超时并终止");
                }

                Instant end = Instant.now();
                build.setStatus(StepStatus.SUCCESS);
                if (agentStep.ignoreOutput()) {
                    build.setOutputPayload(null);
                } else {
                    build.setOutputPayload(result != null ? SerializeUtil.getMapper().writeValueAsString(result) : null);
                }
                build.setExecutionTime(Duration.between(start, end).toMillis());

                // 只有在未超时的情况下才允许落库 SUCCESS 状态
                checkpointRepository.saveCheckpoint(build);
                return result;
            }catch (Throwable e){
                // 如果是因为中断退出或者已被标记超时，不再向数据库写 FAILURE（因为主线程超时捕获处已经处理了）
                if (!isTimedOut.get() && !(e instanceof InterruptedException)) {
                    build.setStatus(StepStatus.FAILURE);
                    build.setExceptionStack(getStackTraceAsString(e));
                    checkpointRepository.saveCheckpoint(build);
                }

                // 消除 Throwable 受检异常报错
                if (e instanceof Exception) {
                    throw (Exception) e;
                } else {
                    throw new RuntimeException(e);
                }
            }
        });

        // 提交给线程池异步执行
        TIMEOUT_EXECUTOR.execute(task);

        try {
            // 主线程带超时等待结果
            return task.get(timeout, agentStep.timeoutUnit());
        } catch (TimeoutException e) {
            // 1. 标记当前任务已经超时（原子标记）
            isTimedOut.set(true);

            // 2. 强行给异步子线程发送中断信号 Thread.interrupt()
            task.cancel(true);

            // 3. 主线程将 DB 记录标记为 FAILURE
            build.setStatus(StepStatus.FAILURE);
            build.setExceptionStack("AgentStep [" + stepName + "] 执行超时，限定时间: "
                    + timeout + " " + agentStep.timeoutUnit().name().toLowerCase());
            checkpointRepository.saveCheckpoint(build);

            throw new TimeoutException("AgentStep [" + stepName + "] 执行超时，限定时间: "
                    + timeout + " " + agentStep.timeoutUnit().name().toLowerCase());

        } catch (ExecutionException e) {
            // 解包真实业务异常
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
    /**
     * 诊断当前方法调用是否可能存在 Spring AOP 自调用失效风险
     */
    private void checkSelfInvocationDiagnosis(ProceedingJoinPoint pjp) {
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
