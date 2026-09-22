package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.spring.boot.starter.config.enums.DatasourceMode;
import ascion.agent.aegis.spring.boot.starter.config.enums.RepositoryType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "agent-aegis")
public class AgentAegisProperties {

    /**
     * 是否开启 Agent Aegis 自动装配
     */
    private boolean enabled = true;

    /**
     * 数据源配置
     */
    private DatasourceConfig datasource = new DatasourceConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private RepositoryType repositoryType = RepositoryType.MEMORY;

    public RepositoryType getRepositoryType() {
        return repositoryType;
    }

    public void setRepositoryType(RepositoryType repositoryType) {
        this.repositoryType = repositoryType;
    }


    public DatasourceConfig getDatasource() {
        return datasource;
    }

    public void setDatasource(DatasourceConfig datasource) {
        this.datasource = datasource;
    }

    public static class DatasourceConfig {
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

        public DatasourceMode getMode() {
            return mode;
        }

        public void setMode(DatasourceMode mode) {
            this.mode = mode;
        }

        public DataSourceProperties getCustom() {
            return custom;
        }

        public void setCustom(DataSourceProperties custom) {
            this.custom = custom;
        }
    }


}
