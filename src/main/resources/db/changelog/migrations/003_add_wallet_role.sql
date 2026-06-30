--liquibase formatted sql

--changeset tokeniza:003-add-wallet-role
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
CREATE INDEX IF NOT EXISTS idx_wallet_role ON wallet(role);
