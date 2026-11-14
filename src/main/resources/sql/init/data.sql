INSERT INTO category (code, name, display_order) VALUES
    ('housing', 'Жильё', 1),
    ('food', 'Продукты', 2),
    ('health', 'Здоровье', 3),
    ('leisure', 'Развлечения', 4),
    ('travel', 'Путешествия', 5)
ON CONFLICT (code) DO NOTHING;

-- Recorded plans and actuals from the worked example. Both owners start independently.
WITH RECURSIVE
    months_2026(n) AS (VALUES (1) UNION ALL SELECT n + 1 FROM months_2026 WHERE n < 12),
    owners(name) AS (VALUES ('owner-a'), ('owner-b')),
    fixture(period, category, plan, actual) AS (
        SELECT printf('2026-%02d', n), 'food',
               CASE WHEN n = 12 THEN 22000 ELSE 24000 END, 24000 FROM months_2026
        UNION ALL
        SELECT printf('2026-%02d', n), 'housing',
               12000, CASE WHEN n = 12 THEN 24000 ELSE 12000 END FROM months_2026
        UNION ALL VALUES
            ('2027-01', 'housing', 13000, 12000), ('2027-02', 'housing', 13000, 13000), ('2027-03', 'housing', 13000, 13000),
            ('2027-01', 'food',    24000, 25000), ('2027-02', 'food',    24000, 23000), ('2027-03', 'food',    24000,  6000),
            ('2027-01', 'health',   3000,  1000), ('2027-02', 'health',   3000,  2000), ('2027-03', 'health',   3000,     0),
            ('2027-01', 'leisure', 12000, 10000), ('2027-02', 'leisure', 12000, 14000), ('2027-03', 'leisure', 12000,  1000),
            ('2027-01', 'travel',   8000,  5000), ('2027-02', 'travel',   8000,  6000), ('2027-03', 'travel',   8000,     0)
    )
INSERT INTO category_month (owner, period, category, plan, actual)
SELECT owners.name, fixture.period, fixture.category, fixture.plan, fixture.actual
FROM fixture CROSS JOIN owners WHERE true
ON CONFLICT (owner, period, category) DO NOTHING;
