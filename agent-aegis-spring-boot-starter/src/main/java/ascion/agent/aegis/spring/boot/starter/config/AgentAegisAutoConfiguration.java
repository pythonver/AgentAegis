package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.core.repository.impl.InMemoryCheckpointRepositoryImpl;
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
import org.springframework.context.annotation.Configuration;
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

    // =========================================================================
    // 分支 A：内存存储实现（repository-type = MEMORY 或未显式配置时生效）
    // =========================================================================
    @Bean
    @ConditionalOnProperty(
            prefix = "agent-aegis",
            name = "repository-type",
            havingValue = "MEMORY",
            matchIfMissing = true // 默认使用内存模式，无需配置数据源
    )
    @ConditionalOnMissingBean(CheckpointRepository.class)
    public CheckpointRepository inMemoryCheckpointRepository() {
        return new InMemoryCheckpointRepositoryImpl();
    }

    // =========================================================================
    // 分支 B：JDBC 存储实现（仅当 repository-type = JDBC 时生效）
    // =========================================================================
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "agent-aegis",
            name = "repository-type",
            havingValue = "JDBC"
    )
    public static class JdbcRepositoryConfiguration {

        /**
         * 分支 B-1：开箱即用（默认）—— mode 为 INHERIT 时，直接沿用宿主工程默认的 JdbcTemplate
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
            JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();

            if (jdbcTemplate != null) {
                return jdbcTemplate;
            }

            // 2. 没有 JdbcTemplate，则使用宿主默认 DataSource
            DataSource dataSource = dataSourceProvider.getIfAvailable();

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
         * 分支 B-2：独立解绑—— mode 为 ISOLATED 时，使用自定义数据源属性初始化专属的 JdbcTemplate
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
         * 强绑定 JDBC 版本的 CheckpointRepository
         */
        @Bean
        @ConditionalOnMissingBean(CheckpointRepository.class)
        public CheckpointRepository jdbcCheckpointRepository(
                @Qualifier("agentAegisJdbcTemplate") JdbcTemplate jdbcTemplate) {
            return new JdbcCheckpointRepositoryImpl(jdbcTemplate);
        }
    }

}
