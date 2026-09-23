package ascion.agent.aegis.spring.boot.starter.repository;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.model.TaskStatus;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public class JdbcCheckpointRepositoryImpl implements CheckpointRepository {


    private final JdbcTemplate jdbcTemplate;

    public JdbcCheckpointRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JDBC template can't be null!");
    }

    // TaskContext 映射器
    private final RowMapper<TaskContext> taskContextRowMapper = (rs, rowMapper) -> {
        String metadataJson = rs.getString("metadata_json");
        TaskContext taskContext;
        try {
            if (metadataJson != null && !metadataJson.isEmpty()) {
                taskContext = TaskContext.fromJson(metadataJson);
            }else {
                taskContext = new TaskContext();
            }
        }catch (Exception e) {
            taskContext = new TaskContext();
        }
                taskContext.setInputPayload(rs.getString("input_payload"));
                taskContext.setOutputPayload(rs.getString("output_payload"));
        taskContext.setTaskId(rs.getString("task_id"));
        taskContext.setName(rs.getString("name"));
        taskContext.setStatus(TaskStatus.valueOf(rs.getString("status")));
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (createdAt != null) taskContext.setCreatedAt(createdAt.toInstant());
        if (updatedAt != null) taskContext.setUpdatedAt(updatedAt.toInstant());

        return taskContext;
    };

    // Checkpoint 映射器
    private final RowMapper<Checkpoint> checkpointRowMapper = (rs, rowMapper) -> {
        Checkpoint checkpoint = Checkpoint.builder()
                .taskId(rs.getString("task_id"))
                .stepName(rs.getString("step_name"))
                .status(StepStatus.valueOf(rs.getString("status")))
                .inputPayload(rs.getString("input_payload"))
                .outputPayload(rs.getString("output_payload"))
                .exceptionStack(rs.getString("exception_stack"))
                .executionTime(rs.getLong("execution_time_ms"))
                .build();

        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (createdAt != null) checkpoint.setCreatedAt(createdAt.toInstant());
        if (updatedAt != null) checkpoint.setUpdatedAt(updatedAt.toInstant());
        return checkpoint;
    };


    @Override
    public void saveTask(TaskContext context) {
        Objects.requireNonNull(context, "TaskContext can't be null!");

        try {
            String metadataJson = context.toJson();
            Optional<TaskContext> existing = findTaskById(context.getTaskId());

            if (existing.isPresent()) {
                String updateSql = "UPDATE agent_guardian_task SET name = ?, status = ?, input_payload = ?, output_payload = ?, retries = ?, metadata_json = ?, updated_at = ? WHERE task_id = ?";
                jdbcTemplate.update(updateSql,
                        context.getName(),
                        context.getStatus().name(),
                        context.getInputPayload(),
                        context.getOutputPayload(),
                        context.getRetries(),
                        metadataJson,
                        Timestamp.from(context.getUpdatedAt()),
                        context.getTaskId());
            } else {
                String insertSql = "INSERT INTO agent_guardian_task (task_id, name, status, input_payload, output_payload, retries, metadata_json, created_at, updated_at) VALUES (?,?,?,? ,?, ?, ?, ?, ?)";
                jdbcTemplate.update(insertSql,
                        context.getTaskId(),
                        context.getName(),
                        context.getStatus().name(),
                        context.getInputPayload(),
                        context.getOutputPayload(),
                        context.getRetries(),
                        metadataJson,
                        Timestamp.from(context.getCreatedAt()),
                        Timestamp.from(context.getUpdatedAt()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("FAILED to save task json: " + context.getTaskId(), e);
        }
    }

    @Override
    public Optional<TaskContext> findTaskById(String taskId) {
        String sql = "SELECT task_id, name, status, input_payload, output_payload, retries, metadata_json, created_at, updated_at FROM agent_guardian_task WHERE task_id = ?";

        try {
            TaskContext taskContext = jdbcTemplate.queryForObject(sql, taskContextRowMapper, taskId);
            return Optional.ofNullable(taskContext);
        }catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveCheckpoint(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "Checkpoint can't be null!");

        Optional<Checkpoint> existing = findCheckpoint(checkpoint.getTaskId(), checkpoint.getStepName());

        if (existing.isPresent()) {
            String updateSql = "UPDATE agent_guardian_checkpoint SET status = ?, input_payload = ?, output_payload = ?, " +
                    "exception_stack = ?, execution_time_ms = ?, updated_at = ? WHERE task_id = ? AND step_name = ?";
            jdbcTemplate.update(updateSql,
                    checkpoint.getStatus().name(),
                    checkpoint.getInputPayload(),
                    checkpoint.getOutputPayload(),
                    checkpoint.getExceptionStack(),
                    checkpoint.getExecutionTime(),
                    Timestamp.from(checkpoint.getUpdatedAt()),
                    checkpoint.getTaskId(),
                    checkpoint.getStepName());
        } else {
            String insertSql = "INSERT INTO agent_guardian_checkpoint " +
                    "(task_id, step_name, status, input_payload, output_payload, exception_stack, execution_time_ms, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(insertSql,
                    checkpoint.getTaskId(),
                    checkpoint.getStepName(),
                    checkpoint.getStatus().name(),
                    checkpoint.getInputPayload(),
                    checkpoint.getOutputPayload(),
                    checkpoint.getExceptionStack(),
                    checkpoint.getExecutionTime(),
                    Timestamp.from(checkpoint.getCreatedAt()),
                    Timestamp.from(checkpoint.getUpdatedAt()));
        }
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(String taskId, String stepName) {

        String sql = "SELECT task_id, step_name, status, input_payload, output_payload, exception_stack, execution_time_ms, created_at, updated_at" +
                " FROM agent_guardian_checkpoint WHERE task_id = ? AND step_name = ?";

        try {
            Checkpoint checkpoint = jdbcTemplate.queryForObject(sql, checkpointRowMapper, taskId, stepName);
            return Optional.ofNullable(checkpoint);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Checkpoint> listCheckpoints(String taskId) {
        String sql = "SELECT task_id, step_name, status, input_payload, output_payload, exception_stack, execution_time_ms, created_at, updated_at " +
                "FROM agent_guardian_checkpoint WHERE task_id = ? ORDER BY created_at";
        return jdbcTemplate.query(sql, checkpointRowMapper, taskId);
    }
}
