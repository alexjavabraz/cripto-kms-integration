--liquibase formatted sql

--changeset dpl_devops_liquibase:002_create_wallet
CREATE TABLE IF NOT EXISTS wallet (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    VARCHAR(255) NOT NULL,
    key_id     VARCHAR(512) NOT NULL UNIQUE,
    address    VARCHAR(42)  NOT NULL UNIQUE,
    network    VARCHAR(100) NOT NULL,
    alias      VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wallet_user_id ON wallet(user_id);
CREATE INDEX IF NOT EXISTS idx_wallet_address  ON wallet(address);

--rollback DROP TABLE IF EXISTS wallet;
