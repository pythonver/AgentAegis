package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.core.repository.impl.InMemoryCheckpointRepositoryImpl;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentRetryAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentStepAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
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
@EnableConfigurationProperties(AgentAegisProperties.class)
@EnableAspectJAutoProxy(exposeProxy = true)
public class AgentAegisAutoConfiguration {

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
