package ascion.agent.aegis.core.repository;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.TaskContext;

import java.util.List;
import java.util.Optional;

// 检查点 SPI 接口
public interface CheckpointRepository {

    /**
     * 保存或更新任务上下文
     */
    void saveTask(TaskContext context);

    /**
     * 根据 Task ID 获取任务上下文
     */
    Optional<TaskContext> findTaskById(String taskId);

    /**
     * 记录或更新某个 Step 的打卡状态
     */
    void saveCheckpoint(Checkpoint checkpoint);

    /**
     * 查询某个 Task 下指定 Step 的历史打卡记录
     * 用于切面判断：如果存在且为 SUCCESS，直接 Replay 返回 outputPayload
     */
    Optional<Checkpoint> findCheckpoint(String taskId, String stepName);

    /**
     * 获取某个 Task 下所有的 Checkpoint 列表（按时间排序，供 Dashboard/Timeline 展示）
     */
    List<Checkpoint> listCheckpoints(String taskId);

}
