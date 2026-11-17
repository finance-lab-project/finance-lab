-- Give existing issue #9 databases their original planning settings without rewriting monthly records.
INSERT INTO planning_settings (owner, since, income, target, revision)
SELECT owner, '2027-01', 100000, '2/5', 1 FROM category_month
 WHERE period = '2027-01' AND category = 'food'
ON CONFLICT DO NOTHING;

INSERT INTO planning_category (owner, since, category, name, kind, addition_order, unit, value, plan)
SELECT m.owner, m.period, c.code, c.name,
       CASE WHEN c.code IN ('leisure', 'travel') THEN 'discretionary' ELSE 'essential' END,
       c.display_order,
       CASE WHEN c.code IN ('housing', 'food') THEN 'annual'
            WHEN c.code = 'health' THEN 'amount' ELSE 'fraction' END,
       CASE WHEN c.code IN ('housing', 'food') THEN
                CAST((SELECT sum(h.actual) FROM category_month h WHERE h.owner = m.owner
                      AND h.category = c.code AND h.period BETWEEN '2026-01' AND '2026-12') AS TEXT)
            WHEN c.code = 'leisure' THEN '3/5' WHEN c.code = 'travel' THEN '2/5'
            ELSE CAST(m.plan AS TEXT) END,
       m.plan
  FROM category_month m JOIN category c ON c.code = m.category
 WHERE m.period = '2027-01' AND c.code IN ('housing', 'food', 'health', 'leisure', 'travel')
ON CONFLICT DO NOTHING;
