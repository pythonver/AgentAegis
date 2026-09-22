package ascion.agent.aegis.core.model;

public enum TaskStatus {
    // 运行中（可能正在运行，也可能处理异常运行状态，可配置化的工作流异常时长）
    RUNNING,
    // 已成功执行
    SUCCESS,
    // 运行失败，有 AgentStep 抛出异常
    FAILED,

    // 暂停中，后续支持，HITL场景
    PAUSED
}
