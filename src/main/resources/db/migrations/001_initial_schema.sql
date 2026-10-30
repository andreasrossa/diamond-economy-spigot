-- Initial schema. Must match the Exposed tables in db/Tables.kt (names included), see Migrations.kt.

CREATE TABLE accounts (
    uuid    uuid PRIMARY KEY,
    "name"  TEXT NOT NULL,
    balance BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT check_accounts_0 CHECK (balance >= 0)
);
CREATE INDEX accounts_name_lower_idx ON accounts (LOWER(accounts."name"));

CREATE TABLE transactions (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "type"      VARCHAR(16) NOT NULL,
    from_uuid   uuid NULL,
    to_uuid     uuid NULL,
    amount      BIGINT NOT NULL,
    item        TEXT NULL,
    item_name   TEXT NULL,
    item_amount INT NULL,
    shop_id     BIGINT NULL,
    CONSTRAINT fk_transactions_from_uuid__uuid FOREIGN KEY (from_uuid) REFERENCES accounts(uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transactions_to_uuid__uuid FOREIGN KEY (to_uuid) REFERENCES accounts(uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT check_transactions_0 CHECK (amount > 0)
);
CREATE INDEX transactions_from_uuid_created_at ON transactions (from_uuid, created_at);
CREATE INDEX transactions_to_uuid_created_at ON transactions (to_uuid, created_at);

CREATE TABLE shops (
    id           BIGSERIAL PRIMARY KEY,
    owner_uuid   uuid NOT NULL,
    owner_name   TEXT NOT NULL,
    world        TEXT NOT NULL,
    x            INT NOT NULL,
    y            INT NOT NULL,
    z            INT NOT NULL,
    item         TEXT NOT NULL,
    item_type    TEXT NOT NULL,
    display_name TEXT NULL,
    icon         TEXT NULL,
    quantity     INT NOT NULL,
    price        BIGINT NOT NULL,
    stock        INT DEFAULT 0 NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_shops_owner_uuid__uuid FOREIGN KEY (owner_uuid) REFERENCES accounts(uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT check_shops_0 CHECK (quantity > 0),
    CONSTRAINT check_shops_1 CHECK (price > 0)
);
CREATE INDEX shops_item_type ON shops (item_type);
ALTER TABLE shops ADD CONSTRAINT shops_world_x_y_z_unique UNIQUE (world, x, y, z);
