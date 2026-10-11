-- M4.1: Owner-scoped, explicitly approved sequential execution plans.
-- Plans only reference *existing* immutable AI move proposals. No model output is
-- executable and no file data is duplicated in the workflow audit.
CREATE TABLE ai_agent_workflow (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_agent_workflow_owner (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_agent_workflow_step (
    workflow_id CHAR(36) NOT NULL,
    step_no INT NOT NULL,
    move_proposal_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (workflow_id,step_no),
    UNIQUE KEY uk_agent_workflow_move (move_proposal_id),
    CONSTRAINT fk_agent_workflow_steps FOREIGN KEY (workflow_id)
        REFERENCES ai_agent_workflow(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_agent_workflow_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    step_no INT NULL,
    action VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_agent_workflow_events (workflow_id,id),
    CONSTRAINT fk_agent_workflow_events FOREIGN KEY (workflow_id)
        REFERENCES ai_agent_workflow(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
