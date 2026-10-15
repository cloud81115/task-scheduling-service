CREATE TABLE IF NOT EXISTS scheduled_task (
    task_id      VARCHAR(64) NOT NULL PRIMARY KEY,
    execute_at   DATETIME(3) NOT NULL COMMENT 'UTC',
    payload      JSON        NOT NULL,
    status       VARCHAR(16) NOT NULL COMMENT 'PENDING / TRIGGERED / CANCELLED',
    created_at   DATETIME(3) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    triggered_at DATETIME(3) NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    INDEX idx_status_execute_at (status, execute_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
