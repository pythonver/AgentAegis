package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.core.repository.impl.InMemoryCheckpointRepositoryImpl;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentRetryAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentStepAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
import ascion.agent.aegis.spring.boot.starter.listener.AgentTaskReadyListener;
import ascion.agent.aegis.spring.boot.starter.listener.AgentWorkflowBeanScanListener;
import ascion.agent.aegis.spring.boot.starter.registry.AgentWorkflowRegistry;
import ascion.agent.aegis.spring.boot.starter.resume.TaskResumeDispatcher;
import ascion.agent.aegis.spring.boot.starter.resume.TaskResumeRunner;
import ascion.agent.aegis.spring.boot.starter.resume.ThreadPoolTaskResumeDispatcher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 自动配置 Agent-Aegis 切面与内存仓储（不依赖 Spring JDBC）
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-aegis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({AgentAegisProperties.class, AgentAegisTaskResumeProperties.class})
@EnableAspectJAutoProxy(exposeProxy = true)
public class AgentAegisAutoConfiguration {

    // 注册切面
    @Bean
    public AgentRetryAspect agentRetryAspect() {
        return new AgentRetryAspect();
    }

    @Bean
    public AgentStepAspect agentStepAspect() {
        return new AgentStepAspect();
    }

    @Bean
    public AgentWorkflowAspect agentWorkflowAspect() {
        return new AgentWorkflowAspect();
    }

    // 注册重启Bean（Dispatcher 允许业务侧以同名 @Bean 覆盖）
    @Bean
    @ConditionalOnMissingBean
    public TaskResumeRunner taskResumeRunner(CheckpointRepository checkpointRepository, AgentWorkflowRegistry agentWorkflowRegistry) {
        return new TaskResumeRunner(checkpointRepository, agentWorkflowRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(TaskResumeDispatcher.class)
    public TaskResumeDispatcher taskResumeDispatcher(TaskResumeRunner taskResumeRunner) {
        return new ThreadPoolTaskResumeDispatcher(taskResumeRunner);
    }

    // 注册监听器（扫描=定义侧 Workflow；Ready=实例侧 Task 恢复）
    @Bean
    public AgentWorkflowRegistry agentWorkflowRegistry() {
        return new AgentWorkflowRegistry();
    }

    @Bean
    public AgentWorkflowBeanScanListener agentWorkflowBeanScanListener(AgentWorkflowRegistry agentWorkflowRegistry) {
        return new AgentWorkflowBeanScanListener(agentWorkflowRegistry);
    }

    @Bean
    public AgentTaskReadyListener agentTaskReadyListener(CheckpointRepository checkpointRepository, TaskResumeDispatcher  taskResumeDispatcher) {
        return new AgentTaskReadyListener(checkpointRepository, taskResumeDispatcher);
    }


    /**
     * 内存存储实现（默认，无需任何数据库依赖）
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "agent-aegis",
            name = "repository-type",
            havingValue = "MEMORY",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(CheckpointRepository.class)
    public CheckpointRepository inMemoryCheckpointRepository() {
        return new InMemoryCheckpointRepositoryImpl();
    }
}
