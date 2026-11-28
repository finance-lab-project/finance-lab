, recorded AS (
    SELECT category, plan, actual FROM category_month WHERE owner = :owner AND period = :period
), configured AS (
    SELECT category, name, addition_order FROM planning_category WHERE owner = :owner AND since = :since
), codes AS (
    SELECT category FROM configured UNION SELECT category FROM recorded
)
SELECT codes.category, coalesce(p.name, c.name) AS name, m.plan, m.actual, m.category IS NOT NULL AS recorded,
       coalesce(m.plan, (SELECT r.plan FROM periods r WHERE r.category = codes.category
                           AND (r.period <= :period OR (r.ordinal = 1 AND r.start IS NULL))
                           AND (r.until IS NULL OR r.until > :period)), 0) AS effective_plan
  FROM codes JOIN category c ON c.code = codes.category
  LEFT JOIN configured p ON p.category = codes.category
  LEFT JOIN recorded m ON m.category = codes.category
 ORDER BY coalesce(p.addition_order, c.display_order), codes.category;
