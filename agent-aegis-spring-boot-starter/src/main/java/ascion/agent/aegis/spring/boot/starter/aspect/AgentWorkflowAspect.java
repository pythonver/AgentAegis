package ascion.agent.aegis.spring.boot.starter.aspect;

import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
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
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;

import static ascion.agent.aegis.spring.boot.starter.utils.SelfInvocationDiagnosisUtil.checkSelfInvocationDiagnosis;
import static ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil.safeSerialize;

/**
 * 一次 workflow 调用的执行外壳：认领 taskId → 绑上下文 → 落 RUNNING → 执行 → 落终态 → 清上下文。
 * <p>
 * 再入策略仅处理 SUCCESS（REPLAY / THROW）；RUNNING/FAILED 一律按重跑（覆盖更新，无次数上限）；
 * PAUSED 拒绝进入。僵尸判定与后台清扫不在本切面职责内。
 */
@Aspect
@Order(10)
public class AgentWorkflowAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowAspect.class);

    @Autowired
    private CheckpointRepository  checkpointRepository;

    @Around("@annotation(agentWorkflow)")
    public Object agentWorkflow(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow) throws Throwable {

        checkSelfInvocationDiagnosis(pjp);

        if (agentWorkflow.conflictStrategy().equals(AgentWorkflow.ConflictStrategy.REPLAY)
                && agentWorkflow.ignoreOutput()) {

            throw new IllegalArgumentException(
                    "Invalid Workflow Configuration: Cannot set conflictStrategy = REPLAY when ignoreOutput = true, " +
                            "because the output payload has been purged from the database and cannot be replayed."
            );
        }


        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();

            String taskId = getTaskId(pjp, agentWorkflow);
            Instant now = Instant.now();


            Optional<TaskContext> existingTaskOpt = checkpointRepository.findTaskById(taskId);
            TaskContext taskContext;

            if (existingTaskOpt.isPresent()) {
                taskContext = existingTaskOpt.get();
                if (!StringUtils.hasText(taskContext.getName())) {
                    taskContext.setName(resolveWorkflowKey(pjp, signature, agentWorkflow));
                }
                TaskStatus status = taskContext.getStatus();

                // 仅 SUCCESS 再入：REPLAY 回放或拒绝；不落库
                if (status.equals(TaskStatus.SUCCESS)) {
                    return handleSuccessTask(signature, agentWorkflow, taskContext, taskId);
                }

                // PAUSED 为预留状态，拒绝进入（不重跑）
                if (status.equals(TaskStatus.PAUSED)) {
                    throw new IllegalStateException(
                            String.format("任务 [%s] 处于 PAUSED（预留状态），暂不支持进入执行", taskId));
                }

                // RUNNING / FAILED：一律按重跑，走下方生命周期（覆盖更新）

            } else {
                taskContext = TaskContext.builder()
                        .taskId(taskId)
                        .name(resolveWorkflowKey(pjp, signature, agentWorkflow))
                        .retries(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .status(TaskStatus.RUNNING)
                        .build();
            }


            TaskContextHolder.setContext(taskContext);

            Object[] args = pjp.getArgs();
            taskContext.setInputPayload(safeSerialize(args));
            taskContext.setUpdatedAt(now);
            taskContext.setStatus(TaskStatus.RUNNING);

            checkpointRepository.saveTask(taskContext);


            return proceedDirectly(pjp, agentWorkflow, taskContext);
        }finally {
            TaskContextHolder.clear();
        }


    }

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

    private void proceedWithTimeout(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow, TaskContext taskContext) throws Throwable {
        // TODO : 计划支持
    }

    /**
     * 优先复用 ThreadLocal 中已有 taskId，否则生成新 id。
     */
    private String getTaskId(ProceedingJoinPoint pjp, AgentWorkflow agentWorkflow) throws Throwable {

        String contextTaskId = TaskContextHolder.getTaskId();
        return StringUtils.hasText(contextTaskId) ? contextTaskId : TaskContextHolder.generateTaskId();

    }

    /**
     * 解析 workflow 定义键（非实例自定义名）。
     * <p>
     * 规则：{@code @AgentWorkflow.name()} 非空白则用之，否则回落方法名。
     * 返回值写入 {@link TaskContext#getName()}（实例侧 name 列），
     * 并与 {@code AgentWorkflowRegistry} 的键对齐，供启动恢复时按实例反查定义。
     */
    private String resolveWorkflowKey(ProceedingJoinPoint pjp, MethodSignature methodSignature, AgentWorkflow agentWorkflow) {
        if (agentWorkflow.name().isBlank()) {
            return methodSignature.getMethod().getName();
        }
        return agentWorkflow.name();
    }


    /**
     * SUCCESS 再入：REPLAY 回放出参（不落库）或 THROW 拒绝重跑。
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
            JavaType javaType = SerializeUtil.getMapper().getTypeFactory().constructType(signature.getMethod().getGenericReturnType());
            Object replayedResult = SerializeUtil.getMapper().readValue(outputPayload, javaType);

            return buildResult(replayedResult, signature.getReturnType(), taskContext);
        }

        throw new TaskAlreadyExistsException("任务 [" + taskId + "] 已成功完成，禁止重复发起");
    }

    /**
     * 包装返回值
     */
    private Object buildResult(Object result, Class<?> returnType, TaskContext taskContext) {
        if (AgentWorkflowResult.class.isAssignableFrom(returnType)) {
            if (result instanceof AgentWorkflowResult<?> workflowResult) {
                workflowResult.setTaskId(taskContext.getTaskId());
                workflowResult.setTaskStatus(taskContext.getStatus().name());
            } else if (result == null) {
                AgentWorkflowResult<Object> emptyResult = AgentWorkflowResult.of(null);
                emptyResult.setTaskId(taskContext.getTaskId());
                emptyResult.setTaskStatus(taskContext.getStatus().name());
                return emptyResult;
            }
        }

        return result;
    }
}
