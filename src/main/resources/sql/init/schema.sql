CREATE TABLE IF NOT EXISTS category (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    display_order INTEGER NOT NULL UNIQUE
) STRICT;

CREATE TABLE IF NOT EXISTS category_month (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    category TEXT NOT NULL REFERENCES category(code),
    plan INTEGER NOT NULL,
    actual INTEGER NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner, period, category)
) STRICT;
