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

CREATE TABLE IF NOT EXISTS month_figure_recording (
    owner TEXT NOT NULL,
    period TEXT NOT NULL,
    income_recorded INTEGER NOT NULL,
    contributions_recorded INTEGER NOT NULL,
    PRIMARY KEY (owner, period),
    FOREIGN KEY (owner, period) REFERENCES month_actuals(owner, period)
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

CREATE TABLE IF NOT EXISTS planning_category_rollover_copy (
    owner TEXT NOT NULL,
    since TEXT NOT NULL,
    category TEXT NOT NULL,
    PRIMARY KEY (owner, since, category),
    FOREIGN KEY (owner, since, category) REFERENCES planning_category(owner, since, category) ON DELETE CASCADE
) STRICT;

-- In legacy versions a normal January settings save advanced the rollover revision
-- and could create a plan-only category_month snapshot. Preserve unmarked zero rates.
INSERT INTO planning_category_rollover_copy (owner, since, category)
SELECT p.owner, p.since, p.category FROM planning_category p
  JOIN planning_settings jan ON jan.owner = p.owner AND jan.since = p.since
  JOIN planning_settings prev ON prev.owner = p.owner AND prev.since = (
      SELECT max(since) FROM planning_settings WHERE owner = p.owner AND since < p.since
  )
  JOIN planning_category old ON old.owner = prev.owner AND old.since = prev.since AND old.category = p.category
 WHERE substr(p.since, 6, 2) = '01' AND jan.revision = prev.revision + 1
   AND jan.income = prev.income AND jan.target = prev.target AND old.plan = p.plan
   AND NOT EXISTS (SELECT 1 FROM category_month m WHERE m.owner = p.owner
                    AND m.period = p.since AND m.category = p.category
                    AND (m.plan = 0 OR m.plan <> p.plan OR m.actual <> 0 OR m.revision <> 0
                         OR EXISTS (SELECT 1 FROM month_actuals a WHERE a.owner = m.owner AND a.period = m.period)))
ON CONFLICT DO NOTHING;

-- Remove settings-only snapshots so projected rates contribute no opening buffer.
DELETE FROM category_month
 WHERE plan <> 0 AND actual = 0 AND revision = 0
   AND NOT EXISTS (SELECT 1 FROM month_actuals a
                    WHERE a.owner = category_month.owner AND a.period = category_month.period)
   AND EXISTS (
       SELECT 1 FROM planning_category_rollover_copy c
         JOIN planning_category p USING (owner, since, category)
        WHERE c.owner = category_month.owner AND c.since = category_month.period
          AND c.category = category_month.category AND p.plan = category_month.plan
   );

-- A backdated history edit must preserve a category's already established start.
CREATE TABLE IF NOT EXISTS category_plan_inception (
    owner TEXT NOT NULL,
    category TEXT NOT NULL REFERENCES category(code),
    since TEXT NOT NULL,
    PRIMARY KEY (owner, category)
) STRICT;

-- For upgraded owners, a pre-settings rate is evidence that a reserved category
-- was already used. Inherited plan snapshots supply no such evidence.
CREATE VIEW IF NOT EXISTS inferred_category_plan_inception AS
SELECT f.owner, f.category, f.since FROM (
    SELECT owner, category, min(since) AS since FROM planning_category GROUP BY owner, category
) f
WHERE f.category NOT IN ('housing', 'food', 'health', 'leisure', 'travel')
   OR (f.since > (SELECT min(since) FROM planning_settings WHERE owner = f.owner)
       AND NOT EXISTS (
           SELECT 1 FROM category_month m
            WHERE m.owner = f.owner AND m.category = f.category AND m.period < f.since
              AND NOT EXISTS (SELECT 1 FROM category_month_inherited_plan i
                               WHERE i.owner = m.owner AND i.period = m.period AND i.category = m.category)
       ));

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
