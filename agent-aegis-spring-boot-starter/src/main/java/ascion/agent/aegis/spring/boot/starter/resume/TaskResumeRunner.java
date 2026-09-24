package ascion.agent.aegis.spring.boot.starter.resume;

import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.exception.TaskResumeFailedException;
import ascion.agent.aegis.spring.boot.starter.registry.AgentWorkflowRegistry;
import ascion.agent.aegis.spring.boot.starter.registry.WorkflowDescription;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Type;
import java.util.Optional;

@RequiredArgsConstructor
public class TaskResumeRunner {

    private static final Logger logger = LoggerFactory.getLogger(TaskResumeRunner.class);

    private final CheckpointRepository checkpointRepository;
    private final AgentWorkflowRegistry agentWorkflowRegistry;

    public void run(ResumeTicket ticket) {
        if (ticket == null || !StringUtils.hasText(ticket.getTaskId())) {
            logger.warn("Resume ticket is empty");
            return;
        }

        Optional<TaskContext> taskById = checkpointRepository.findTaskById(ticket.getTaskId());
        if (taskById.isEmpty() || !TaskStatus.RUNNING.equals(taskById.get().getStatus())) {
            logger.warn("Task {} is not RUNNING, skip resume", ticket.getTaskId());
            return;
        }

        TaskContext task = taskById.get();
        String workflowName = StringUtils.hasText(ticket.getWorkflowName())
                ? ticket.getWorkflowName()
                : task.getName();

        WorkflowDescription workflowDescription = agentWorkflowRegistry.find(workflowName);
        if (workflowDescription == null) {
            logger.warn("Workflow {} not found in registry, mark task {} FAILED",
                    workflowName, task.getTaskId());
            task.setStatus(TaskStatus.FAILED);
            task.setUpdatedAt(java.time.Instant.now());
            checkpointRepository.saveTask(task);
            return;
        }

        Object[] args;
        String inputPayload = task.getInputPayload();
        if (!StringUtils.hasText(inputPayload) || inputPayload.equals("[Unserializable Input]")) {
            logger.warn("Task {} input not restorable, mark FAILED", task.getTaskId());
            task.setStatus(TaskStatus.FAILED);
            task.setUpdatedAt(java.time.Instant.now());
            checkpointRepository.saveTask(task);
            return;
        }

        try {
            JsonNode arr = SerializeUtil.getMapper().readTree(inputPayload);
            Type[] types = workflowDescription.getMethod().getGenericParameterTypes();
            if (!arr.isArray() || arr.size() != types.length) {
                throw new TaskResumeFailedException(
                        String.format("Task (task id : {%s}) restart failed, method signature does not match.",
                                task.getTaskId()));
            }
            args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                args[i] = SerializeUtil.getMapper()
                        .convertValue(arr.get(i), SerializeUtil.getMapper().getTypeFactory().constructType(types[i]));
            }
        } catch (TaskResumeFailedException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Task {} argument restore failed, mark FAILED", task.getTaskId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setUpdatedAt(java.time.Instant.now());
            checkpointRepository.saveTask(task);
            return;
        }

        try {
            TaskContextHolder.setContext(task);
            workflowDescription.invoke(args);
        } catch (Exception e) {
            logger.warn("Task {} resume failed", task.getTaskId(), e);
        } finally {
            TaskContextHolder.clear();
        }
    }
}
