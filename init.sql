CREATE TABLE IF NOT EXISTS scheduled_task (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'internal only, never exposed',
    task_id      VARCHAR(64) NOT NULL COMMENT 'client-supplied id used by the API',
    execute_at   DATETIME(3) NOT NULL COMMENT 'UTC',
    payload      JSON        NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / TRIGGERED / CANCELLED / FAILED',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    triggered_at DATETIME(3) NULL,
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_status_execute_at (status, execute_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
