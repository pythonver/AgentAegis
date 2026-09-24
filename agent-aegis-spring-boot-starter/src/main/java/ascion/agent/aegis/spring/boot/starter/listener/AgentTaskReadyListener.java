package ascion.agent.aegis.spring.boot.starter.listener;


import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.resume.ResumeTicket;
import ascion.agent.aegis.spring.boot.starter.resume.TaskResumeDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.List;


/**
 * 实例侧恢复：应用就绪后仅扫描遗留的 RUNNING 运行实例（Task），
 * 构造 {@link ResumeTicket} 交 {@link TaskResumeDispatcher} 异步拉起（不阻塞启动）。
 * 实际执行由 {@code TaskResumeRunner} 统一完成；后台僵尸清扫（B）后置。
 */
public class AgentTaskReadyListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTaskReadyListener.class);

    private final CheckpointRepository checkpointRepository;

    private final TaskResumeDispatcher taskResumeDispatcher;

    public AgentTaskReadyListener(CheckpointRepository checkpointRepository, TaskResumeDispatcher taskResumeDispatcher) {
        this.checkpointRepository = checkpointRepository;
        this.taskResumeDispatcher = taskResumeDispatcher;
    }

    // 扫描所有 RUNNING 的任务，投递任务
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<TaskContext> tasks = checkpointRepository.getTasksByStatus(TaskStatus.RUNNING);
        tasks.forEach(task ->
                taskResumeDispatcher.dispatch(new ResumeTicket(task.getTaskId(), task.getName()))
        );
    }
}
