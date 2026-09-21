package ascion.agent.aegis.spring.boot.starter.config.enums;

public enum DatasourceMode {
    /**
     * 继承/沿用宿主工程的主数据源 (默认值)
     */
    INHERIT,

    /**
     * 使用 Agent Aegis 独立的自定义数据源
     */
    ISOLATED
}
