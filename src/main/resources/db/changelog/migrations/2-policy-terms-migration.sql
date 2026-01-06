-- liquibase formatted sql
-- changeset codetest:2-policy-terms-migration.sql

-- Policy Terms Chunk table: stores chunked text from policy documents
CREATE TABLE policy_terms_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_name VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    page_number INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for searching chunks
CREATE INDEX idx_policy_terms_document ON policy_terms_chunk(document_name);
CREATE INDEX idx_policy_terms_chunk_index ON policy_terms_chunk(document_name, chunk_index);
