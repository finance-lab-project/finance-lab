-- Give existing issue #9 databases their original planning settings without rewriting monthly records.
INSERT INTO planning_settings (owner, since, income, target, revision)
SELECT owner, '2027-01', 100000, '2/5', 1 FROM category_month
 WHERE period = '2027-01' AND category = 'food'
ON CONFLICT DO NOTHING;

-- Sum whole billions and residual rubles separately so twelve signed integers fit.
-- Carry whole billions out of the residual, then borrow when the two parts have opposite signs.
WITH historical_parts AS (
    SELECT owner, category, sum(actual / 1000000000) AS billions, sum(actual % 1000000000) AS rubles
      FROM category_month
     WHERE period BETWEEN '2026-01' AND '2026-12' AND category IN ('housing', 'food')
     GROUP BY owner, category
), carried AS (
    SELECT owner, category, billions + rubles / 1000000000 AS billions, rubles % 1000000000 AS rubles
      FROM historical_parts
), normalized AS (
    SELECT owner, category,
           CASE WHEN billions > 0 AND rubles < 0 THEN billions - 1
                WHEN billions < 0 AND rubles > 0 THEN billions + 1 ELSE billions END AS billions,
           CASE WHEN billions > 0 AND rubles < 0 THEN rubles + 1000000000
                WHEN billions < 0 AND rubles > 0 THEN rubles - 1000000000 ELSE rubles END AS rubles
      FROM carried
)
INSERT INTO planning_category (owner, since, category, name, kind, addition_order, unit, value, plan)
SELECT m.owner, m.period, c.code, c.name,
       CASE WHEN c.code IN ('leisure', 'travel') THEN 'discretionary' ELSE 'essential' END,
       c.display_order,
       CASE WHEN c.code IN ('housing', 'food') THEN 'annual'
            WHEN c.code = 'health' THEN 'amount' ELSE 'fraction' END,
       CASE WHEN c.code IN ('housing', 'food') THEN
                CASE WHEN h.billions = 0 THEN CAST(h.rubles AS TEXT)
                     WHEN h.billions < 0 THEN printf('-%lld%09lld', -h.billions, abs(h.rubles))
                     WHEN h.billions > 0 THEN printf('%lld%09lld', h.billions, h.rubles) END
            WHEN c.code = 'leisure' THEN '3/5' WHEN c.code = 'travel' THEN '2/5'
            ELSE CAST(m.plan AS TEXT) END,
       m.plan
  FROM category_month m JOIN category c ON c.code = m.category
  LEFT JOIN normalized h ON h.owner = m.owner AND h.category = c.code
 WHERE m.period = '2027-01' AND c.code IN ('housing', 'food', 'health', 'leisure', 'travel')
   AND NOT EXISTS (SELECT 1 FROM planning_category p
                    WHERE p.owner = m.owner AND p.since = m.period AND p.category = c.code)
ON CONFLICT DO NOTHING;
