package ascion.agent.aegis.core.repository.impl;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.repository.CheckpointRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCheckpointRepositoryImpl implements CheckpointRepository {

    private final Map<String, TaskContext> taskContexts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Checkpoint>> checkpoints = new ConcurrentHashMap<>();


    @Override
    public void saveTask(TaskContext context) {
        if (context == null || context.getTaskId() == null) {
            throw new NullPointerException("Context or TaskContext is null");
        }
        taskContexts.put(context.getTaskId(), context);
    }

    @Override
    public Optional<TaskContext> findTaskById(String taskId) {
        if (taskId == null) {
            throw new NullPointerException("TaskContext is null");
        }
        return Optional.ofNullable(taskContexts.get(taskId));
    }

    @Override
    public void saveCheckpoint(Checkpoint checkpoint) {
        if (checkpoint == null || checkpoint.getTaskId() == null ||  checkpoint.getStepName() == null) {
            throw new NullPointerException("Checkpoint is null");
        }
        Map<String, Checkpoint> stepMap = new ConcurrentHashMap<>();
        stepMap.put(checkpoint.getStepName(), checkpoint);

        checkpoints.computeIfAbsent(checkpoint.getTaskId(), k -> new ConcurrentHashMap<>())
                .put(checkpoint.getStepName(), checkpoint);
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(String taskId, String stepName) {

        Map<String, Checkpoint> stepMap = checkpoints.get(taskId);
        if (stepMap == null || stepMap.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(stepMap.get(stepName));
    }

    @Override
    public List<Checkpoint> listCheckpoints(String taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        Map<String, Checkpoint> stepMap = checkpoints.get(taskId);

        if (stepMap == null || stepMap.isEmpty()) {
            return Collections.emptyList();
        }
        // 创建时间升序
        return stepMap.values().stream()
                .sorted(Comparator.comparing(Checkpoint::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
