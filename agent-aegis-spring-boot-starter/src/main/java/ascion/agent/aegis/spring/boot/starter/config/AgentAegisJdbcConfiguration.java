package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.repository.JdbcCheckpointRepositoryImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JDBC 存储配置（仅当 classpath 存在 spring-jdbc 且 repository-type=JDBC 时生效）
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
@ConditionalOnProperty(prefix = "agent-aegis", name = "repository-type", havingValue = "JDBC")
@EnableConfigurationProperties(AgentAegisJdbcProperties.class)
public class AgentAegisJdbcConfiguration {

    /**
     * 分支 B-1：开箱即用（默认）—— mode 为 INHERIT 时，直接沿用宿主工程默认的 JdbcTemplate
     */
    @Bean(name = "agentAegisJdbcTemplate")
    @ConditionalOnProperty(
            prefix = "agent-aegis.datasource",
            name = "mode",
            havingValue = "INHERIT",
            matchIfMissing = true
    )
    public JdbcTemplate inheritJdbcTemplate(ObjectProvider<DataSource> dataSourceProvider,
                                            ApplicationContext applicationContext) {
        // 1. 获取容器中除了 agentAegisJdbcTemplate 以外的所有 JdbcTemplate 类型 Bean
        String[] beanNames = applicationContext.getBeanNamesForType(JdbcTemplate.class);
        for (String beanName : beanNames) {
            if (!"agentAegisJdbcTemplate".equals(beanName)) {
                return applicationContext.getBean(beanName, JdbcTemplate.class);
            }
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
    public JdbcTemplate isolatedJdbcTemplate(AgentAegisJdbcProperties properties) {
        DataSource standaloneDataSource = properties.getCustom()
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
