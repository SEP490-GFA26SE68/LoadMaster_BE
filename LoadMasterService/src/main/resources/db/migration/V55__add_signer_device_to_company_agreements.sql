-- V55: Add signer_device column to company_agreements table
ALTER TABLE company_agreements ADD COLUMN IF NOT EXISTS signer_device VARCHAR(255);
