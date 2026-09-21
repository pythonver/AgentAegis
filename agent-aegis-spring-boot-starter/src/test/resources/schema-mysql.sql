-- 1. 任务上下文表
CREATE TABLE IF NOT EXISTS agent_guardian_task (
                                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                   task_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    metadata_json LONGTEXT,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_task_id UNIQUE (task_id)
    );

-- 2. 步骤打卡快照表
CREATE TABLE IF NOT EXISTS agent_guardian_checkpoint (
                                                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                         task_id VARCHAR(64) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_payload LONGTEXT,
    output_payload LONGTEXT,
    exception_stack TEXT,
    execution_time_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_task_step UNIQUE (task_id, step_name)
    );