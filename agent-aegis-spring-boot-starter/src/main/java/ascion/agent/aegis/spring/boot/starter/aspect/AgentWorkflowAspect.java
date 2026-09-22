package ascion.agent.aegis.spring.boot.starter.aspect;

import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.exception.MaxRetriesExceededException;
import ascion.agent.aegis.spring.boot.starter.exception.TaskAlreadyExistsException;
import ascion.agent.aegis.spring.boot.starter.result.AgentWorkflowResult;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import com.fasterxml.jackson.databind.JavaType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static ascion.agent.aegis.spring.boot.starter.utils.SelfInvocationDiagnosisUtil.checkSelfInvocationDiagnosis;
import static ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil.safeSerialize;

@Aspect
@Component
public class AgentWorkflowAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowAspect.class);

    @Autowired
    private CheckpointRepository  checkpointRepository;

    @Around("@annotation(agentWorkflow)")
    public Object agentWorkflow(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow) throws Throwable {

        checkSelfInvocationDiagnosis(pjp);

        if (agentWorkflow.conflictStrategy().equals(AgentWorkflow.ConflictStrategy.REPLAY)
                && agentWorkflow.ignoreOutput()) {

            // 明确告之开发者：这两个配置不能同时使用！
            throw new IllegalArgumentException(
                    "Invalid Workflow Configuration: Cannot set conflictStrategy = REPLAY when ignoreOutput = true, " +
                            "because the output payload has been purged from the database and cannot be replayed."
            );
        }


        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();

            // 创建任务
            String taskId = getTaskId(pjp, agentWorkflow);
            Instant now = Instant.now();


            Optional<TaskContext> existingTaskOpt = checkpointRepository.findTaskById(taskId);
            TaskContext taskContext;

            if (existingTaskOpt.isPresent()) {
                taskContext = existingTaskOpt.get();
                if (!StringUtils.hasText(taskContext.getName())) {
                    taskContext.setName(getTaskName(pjp, signature, agentWorkflow));
                }
                TaskStatus status = taskContext.getStatus();

                // 1. SUCCESS
                if (status.equals(TaskStatus.SUCCESS)) {
                    return handleSuccessTask(signature, agentWorkflow, taskContext, taskId);
                }

                // 2. RUNNING
                if (status.equals(TaskStatus.RUNNING)) {
                    // 若是僵尸任务，函数内会将其 status 改为 FAILED
                    handleRunningTaskCheck(taskContext, taskId, agentWorkflow);
                    status = taskContext.getStatus();
                }
                // 3. FAILED
                if (status.equals(TaskStatus.FAILED)) {
                    int maxRetries = agentWorkflow.maxRetries();
                    int currentRetries = taskContext.getRetries();

                    if (currentRetries >= maxRetries) {
                        throw new MaxRetriesExceededException(
                                String.format("任务 [%s] 已达到最大重试次数上限 (%d/%d)，拒绝再次重试",
                                        taskId, currentRetries, maxRetries)
                        );
                    }

                    // 允许重试：自增重试次数
                    taskContext.setRetries(currentRetries + 1);
                    log.info("🔄 [AgentWorkflow] 任务 [{}] 正在发起第 {}/{} 次重试...",
                            taskId, taskContext.getRetries(), maxRetries);
                }
                if(status.equals(TaskStatus.PAUSED)) {

                }

            } else {
                // ================= 4. 全新任务初始化 =================
                taskContext = TaskContext.builder()
                        .taskId(taskId)
                        .name(getTaskName(pjp, signature, agentWorkflow))
                        .retries(0) // 初始重试次数为 0
                        .createdAt(now)
                        .updatedAt(now)
                        .status(TaskStatus.RUNNING)
                        .build();
            }


            // 绑定当前线程上下文
            TaskContextHolder.setContext(taskContext);

            // 重新进入 RUNNING 状态，更新 payload 与时间戳，同步落盘
            Object[] args = pjp.getArgs();
            taskContext.setInputPayload(safeSerialize(args));
            taskContext.setUpdatedAt(now);
            taskContext.setStatus(TaskStatus.RUNNING);

            // 确保 DB 里的状态、重试次数 (retries) 和输入参数实时同步
            checkpointRepository.saveTask(taskContext);


            return proceedDirectly(pjp, agentWorkflow, taskContext);
        }finally {
            TaskContextHolder.clear();
        }


    }

    // 同步调用
    private Object proceedDirectly(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow, TaskContext taskContext) throws Throwable {

        try {
            Object result = pjp.proceed();
            if (agentWorkflow.ignoreOutput()) {
                taskContext.setOutputPayload(null);
            }else {
                taskContext.setOutputPayload(result != null ? SerializeUtil.getMapper().writeValueAsString(result) : null);
            }
            taskContext.setUpdatedAt(Instant.now());
            taskContext.setStatus(TaskStatus.SUCCESS);
            checkpointRepository.saveTask(taskContext);
            return buildResult(result, ((MethodSignature) pjp.getSignature()).getReturnType(), taskContext);
        }catch (Throwable throwable){
            taskContext.setUpdatedAt(Instant.now());
            taskContext.setStatus(TaskStatus.FAILED);
            checkpointRepository.saveTask(taskContext);
            throw throwable;
        }
    }

    // 超时调用
    private void proceedWithTimeout(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow, TaskContext taskContext) throws Throwable {
        // TODO : 计划支持
    }

    /**
     * 使用 SqEL 表达式提取taskid
     * @param pjp
     * @param agentWorkflow
     * @return
     * @throws Throwable
     */
    private String getTaskId(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow) throws Throwable {

        String contextTaskId = TaskContextHolder.getTaskId();
        return StringUtils.hasText(contextTaskId) ? contextTaskId : TaskContextHolder.generateTaskId();

    }

    private String getTaskName(ProceedingJoinPoint pjp, MethodSignature methodSignature, AgentWorkflow agentWorkflow) throws Throwable {

        if (agentWorkflow.name().isBlank()) {
            return methodSignature.getMethod().getName();
        }
        return agentWorkflow.name();
    }


    /**
     * SUCCESS 状态的响应处理（只允许 REPLAY 结果或抛异常拒绝）
     */
    private Object handleSuccessTask(MethodSignature signature, AgentWorkflow agentWorkflow, TaskContext taskContext, String taskId) throws Exception {
        if (agentWorkflow.conflictStrategy().equals(AgentWorkflow.ConflictStrategy.REPLAY)) {
            if (agentWorkflow.ignoreOutput() || void.class.equals(signature.getReturnType()) || Void.class.equals(signature.getReturnType())) {
                return buildResult(null, signature.getReturnType(), taskContext);
            }
            String outputPayload = taskContext.getOutputPayload();
            if (!StringUtils.hasText(outputPayload)) {
                return buildResult(null, signature.getReturnType(), taskContext);
            }
            // 3. 从 DB 还原原始的 result 对象
            JavaType javaType = SerializeUtil.getMapper().getTypeFactory().constructType(signature.getMethod().getGenericReturnType());
            Object replayedResult = SerializeUtil.getMapper().readValue(outputPayload, javaType);

            // 4. 关键：将还原出来的 replayedResult 再次经过 buildResult 补充 taskId 与 taskStatus！
            return buildResult(replayedResult, signature.getReturnType(), taskContext);
        }

        throw new TaskAlreadyExistsException("任务 [" + taskId + "] 已成功完成，禁止重复发起");
    }

    /**
     * 校验 RUNNING 状态
     */
    private void handleRunningTaskCheck(TaskContext taskContext, String taskId, AgentWorkflow agentWorkflow) {
        long zombieTimeoutSeconds = agentWorkflow.zombieTimeoutSeconds();
        Instant lastUpdatedAt = taskContext.getUpdatedAt() != null ? taskContext.getUpdatedAt() : Instant.now();

        long elapsedSeconds = Duration.between(lastUpdatedAt, Instant.now()).getSeconds();

        // 没超时：说明是真的有其他节点/线程在并行跑该任务，抛异常拦截
        if (elapsedSeconds < zombieTimeoutSeconds) {
            throw new TaskAlreadyExistsException(
                    String.format("任务 [%s] 正在运行中（上次更新于 %d 秒前），请勿重复提交", taskId, elapsedSeconds)
            );
        }

        // 已超时：判定为上一次运行环境挂掉/僵尸任务，强制标记为 FAILED 准备接管
        log.warn("⚠️ 检测到僵尸任务 [{}]（RUNNING 状态且超过 {} 秒未更新），判定为节点宕机崩塌，将其转换为 FAILED 状态以备重试。",
                taskId, zombieTimeoutSeconds);

        taskContext.setStatus(TaskStatus.FAILED);
    }

    /**
     * 包装返回值
     */
    private Object buildResult(Object result, Class<?> returnType, TaskContext taskContext) {
        if (AgentWorkflowResult.class.isAssignableFrom(returnType)) {
            // 说明 result 绝对是 AgentWorkflowResult 类型（若非 null）
            if (result instanceof AgentWorkflowResult<?> workflowResult) {
                workflowResult.setTaskId(taskContext.getTaskId());
                workflowResult.setTaskStatus(taskContext.getStatus().name());
            } else if (result == null) {
                // 防御业务方法显式 return null 的情况：自动创建一个带 taskId 的空容器
                AgentWorkflowResult<Object> emptyResult = AgentWorkflowResult.of(null);
                emptyResult.setTaskId(taskContext.getTaskId());
                emptyResult.setTaskStatus(taskContext.getStatus().name());
                return emptyResult;
            }
        }

        return result;
    }
}
