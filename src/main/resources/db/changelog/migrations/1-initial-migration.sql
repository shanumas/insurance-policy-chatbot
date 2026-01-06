-- liquibase formatted sql
-- changeset codetest:1-initial-migration.sql

-- Insurance table: represents one insurance contract per personnummer
CREATE TABLE insurance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    personal_number VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Policy table: represents versions of a policy over time (timeline)
CREATE TABLE policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    insurance_id BIGINT NOT NULL,
    address VARCHAR(500) NOT NULL,
    postal_code VARCHAR(10) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (insurance_id) REFERENCES insurance(id) ON DELETE CASCADE
);

-- Index for common queries
CREATE INDEX idx_policy_insurance_id ON policy(insurance_id);
CREATE INDEX idx_policy_start_date ON policy(start_date);
CREATE INDEX idx_policy_insurance_date ON policy(insurance_id, start_date);
