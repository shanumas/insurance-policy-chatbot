--liquibase formatted sql

--changeset hedvig:4-add-customer-name-policy-type
ALTER TABLE insurance ADD COLUMN customer_name VARCHAR(255);
ALTER TABLE insurance ADD COLUMN policy_type VARCHAR(20);

-- Set default values for existing records
UPDATE insurance SET customer_name = 'Unknown Customer' WHERE customer_name IS NULL;
UPDATE insurance SET policy_type = 'STANDARD' WHERE policy_type IS NULL;

-- Make columns NOT NULL after setting defaults
ALTER TABLE insurance ALTER COLUMN customer_name SET NOT NULL;
ALTER TABLE insurance ALTER COLUMN policy_type SET NOT NULL;
