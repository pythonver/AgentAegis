package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.repository.JdbcCheckpointRepositoryImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/**
 * 自动配置 Agent-Aegis 使用的数据源
 */
@AutoConfiguration
@AutoConfigureAfter({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnProperty(prefix = "agent-aegis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentAegisProperties.class)
public class AgentAegisAutoConfiguration {

    /**
     * 分支 1：开箱即用（默认）—— mode 为 INHERIT 时，直接沿用宿主工程默认的 JdbcTemplate
     */
    @Bean(name = "agentAegisJdbcTemplate")
    @ConditionalOnProperty(
            prefix = "agent-aegis.datasource",
            name = "mode",
            havingValue = "INHERIT",
            matchIfMissing = true // 未明确指定模式时，默认生效该分支
    )
    public JdbcTemplate inheritJdbcTemplate(ObjectProvider<DataSource> dataSourceProvider,
                                            ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        // 1. 优先复用宿主默认 JdbcTemplate
        JdbcTemplate jdbcTemplate =
                jdbcTemplateProvider.getIfAvailable();

        if (jdbcTemplate != null) {
            return jdbcTemplate;
        }

        // 2. 没有 JdbcTemplate，则使用宿主默认 DataSource
        DataSource dataSource =
                dataSourceProvider.getIfAvailable();

        if (dataSource != null) {
            return new JdbcTemplate(dataSource);
        }

        // 3. INHERIT 模式却没有任何数据库基础设施
        throw new IllegalStateException(
                "Agent Aegis [INHERIT] mode requires a JdbcTemplate or DataSource, " +
                        "but neither was found. " +
                        "Please configure spring-jdbc/DataSource or switch " +
                        "agent-aegis.datasource.mode to ISOLATED."
        );
    }

    /**
     * 分支 2：独立解绑—— mode 为 ISOLATED 时，使用自定义数据源属性初始化专属的 JdbcTemplate
     */
    @Bean(name = "agentAegisJdbcTemplate")
    @ConditionalOnProperty(
            prefix = "agent-aegis.datasource",
            name = "mode",
            havingValue = "ISOLATED"
    )
    public JdbcTemplate isolatedJdbcTemplate(AgentAegisProperties properties) {
        DataSource standaloneDataSource = properties.getDatasource()
                .getCustom()
                .initializeDataSourceBuilder()
                .build();
        return new JdbcTemplate(standaloneDataSource);
    }

    /**
     * 优先注入名称为 agentAegisJdbcTemplate 的 Bean
     */
    @Bean
    @ConditionalOnMissingBean(CheckpointRepository.class)
    public CheckpointRepository checkpointRepository(@Qualifier("agentAegisJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new JdbcCheckpointRepositoryImpl(jdbcTemplate);
    }

}
