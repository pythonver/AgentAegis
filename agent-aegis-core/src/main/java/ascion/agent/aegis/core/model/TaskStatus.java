package ascion.agent.aegis.core.model;

public enum TaskStatus {
    // 运行实例执行中；超时未更新可由后续后台清扫判定僵尸（入口切面不接管）
    RUNNING,
    // 已成功执行
    SUCCESS,
    // 运行失败，有 AgentStep 抛出异常
    FAILED,

    // 暂停中，后续支持，HITL场景
    PAUSED
}
