-- liquibase formatted sql
-- changeset codetest:3-add-embeddings.sql

-- Add embedding column to policy_terms_chunk table
ALTER TABLE policy_terms_chunk ADD COLUMN embedding BLOB;

-- Add index to help with querying chunks
CREATE INDEX idx_policy_terms_document_name ON policy_terms_chunk(document_name);
