package ascion.agent.aegis.core.repository;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;

import java.util.List;
import java.util.Optional;

// 检查点 SPI 接口
public interface CheckpointRepository {

    /**
     * 保存或更新运行实例上下文（Task = 实例侧）
     */
    void saveTask(TaskContext context);

    /**
     * 根据运行实例 ID 获取实例上下文
     */
    Optional<TaskContext> findTaskById(String taskId);

    /**
     * 记录或更新某个 Step 的打卡状态
     */
    void saveCheckpoint(Checkpoint checkpoint);

    /**
     * 查询某个运行实例下指定 Step 的历史打卡记录
     * 用于切面判断：如果存在且为 SUCCESS，直接 Replay 返回 outputPayload
     */
    Optional<Checkpoint> findCheckpoint(String taskId, String stepName);

    /**
     * 获取某个运行实例下所有的 Checkpoint 列表（按时间排序，供 Dashboard/Timeline 展示）
     */
    List<Checkpoint> listCheckpoints(String taskId);


    List<TaskContext> getTasksByStatus(TaskStatus status);
}
