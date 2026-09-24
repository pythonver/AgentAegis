package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.spring.boot.starter.config.enums.RepositoryType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "agent-aegis")
public class AgentAegisProperties {

    /**
     * 是否开启 Agent Aegis 自动装配
     */
    private boolean enabled = true;

    /**
     * Checkpoint 持久化类型：MEMORY（默认）或 JDBC
     */
    private RepositoryType repositoryType = RepositoryType.MEMORY;

}
