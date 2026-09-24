package ascion.agent.aegis.spring.boot.starter.resume;

public interface TaskResumeDispatcher {

    /**
     * 提交一条恢复命令
     * 单挑执行失败又框架在runner 内隔离，实现方不应吞掉 ticket 而不执行
     */
    void dispatch(ResumeTicket ticket);
}
