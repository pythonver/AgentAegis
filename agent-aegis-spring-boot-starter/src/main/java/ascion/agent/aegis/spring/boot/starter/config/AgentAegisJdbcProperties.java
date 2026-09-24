package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.spring.boot.starter.config.enums.DatasourceMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * JDBC 数据源配置（仅在 spring-jdbc 存在且 repository-type=JDBC 时加载）
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent-aegis.datasource")
public class AgentAegisJdbcProperties {

    /**
     * 数据源模式：
     * INHERIT: 沿用宿主主业务库（默认）
     * ISOLATED: 使用独立自定义数据库
     */
    private DatasourceMode mode = DatasourceMode.INHERIT;

    /**
     * 当 mode = ISOLATED 时使用的独立数据源属性
     */
    @NestedConfigurationProperty
    private DataSourceProperties custom = new DataSourceProperties();

}
