CREATE TABLE IF NOT EXISTS category (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    display_order INTEGER NOT NULL UNIQUE
) STRICT;

-- Reserve the original catalogue keys for recorded March fixture databases.
INSERT INTO category (code, name, display_order) VALUES
    ('housing', 'Жильё', 1),
    ('food', 'Продукты', 2),
    ('health', 'Здоровье', 3),
    ('leisure', 'Развлечения', 4),
    ('travel', 'Путешествия', 5)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS category_month (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    category TEXT NOT NULL REFERENCES category(code),
    plan INTEGER NOT NULL,
    actual INTEGER NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner, period, category)
) STRICT;

-- Actual-only and settings-derived rows retain their plan snapshots for balances.
-- The settings or a historical rate determines the annual projection.
-- Older databases stored a blank historical rate and an explicit zero identically.
-- Keep each unmarked legacy rate, including zero, until the owner corrects it.
CREATE TABLE IF NOT EXISTS category_month_inherited_plan (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    category TEXT NOT NULL,
    PRIMARY KEY (owner, period, category),
    FOREIGN KEY (owner, period, category) REFERENCES category_month(owner, period, category)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
) STRICT;

CREATE TABLE IF NOT EXISTS month_actuals (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    income INTEGER NOT NULL,
    contributions INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    PRIMARY KEY (owner, period)
) STRICT;

CREATE TABLE IF NOT EXISTS planning_settings (
    owner TEXT NOT NULL,
    since TEXT NOT NULL,
    income INTEGER NOT NULL,
    target TEXT NOT NULL,
    revision INTEGER NOT NULL,
    PRIMARY KEY (owner, since)
) STRICT;

CREATE TABLE IF NOT EXISTS planning_category (
    owner TEXT NOT NULL,
    since TEXT NOT NULL,
    category TEXT NOT NULL REFERENCES category(code),
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('essential', 'discretionary')),
    addition_order INTEGER NOT NULL,
    unit TEXT NOT NULL,
    value TEXT NOT NULL,
    plan INTEGER NOT NULL,
    PRIMARY KEY (owner, since, category),
    UNIQUE (owner, since, addition_order),
    FOREIGN KEY (owner, since) REFERENCES planning_settings(owner, since)
) STRICT;

CREATE TABLE IF NOT EXISTS investment_account (
    id INTEGER PRIMARY KEY,
    owner TEXT NOT NULL,
    name TEXT NOT NULL,
    UNIQUE (owner, name)
) STRICT;

CREATE TABLE IF NOT EXISTS savings_product (
    account_id INTEGER PRIMARY KEY REFERENCES investment_account(id),
    kind TEXT NOT NULL CHECK (kind IN ('DEPOSIT', 'SAVINGS'))
) STRICT;

CREATE TABLE IF NOT EXISTS investment_month (
    account_id INTEGER NOT NULL REFERENCES investment_account(id),
    period TEXT NOT NULL,
    closing_value INTEGER NOT NULL,
    credited_income INTEGER NOT NULL,
    PRIMARY KEY (account_id, period)
) STRICT;

CREATE TABLE IF NOT EXISTS fire_goal (
    owner TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('capital', 'income')),
    amount INTEGER NOT NULL CHECK (amount > 0),
    PRIMARY KEY (owner, kind)
) STRICT;

CREATE TABLE IF NOT EXISTS fire_account (
    account_id INTEGER PRIMARY KEY REFERENCES investment_account(id)
) STRICT;

CREATE TABLE IF NOT EXISTS cash_reconciliation (
    owner TEXT PRIMARY KEY,
    reference_date TEXT NOT NULL,
    card_balance INTEGER NOT NULL,
    later_movement TEXT NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS cash_movement (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    effective_date TEXT NOT NULL,
    amount TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS cash_movement_lookup ON cash_movement (owner, period, effective_date);
