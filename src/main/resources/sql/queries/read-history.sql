WITH recorded AS (
    SELECT category, plan, actual FROM category_month WHERE owner = :owner AND period = :period
), configured AS (
    SELECT category, name, addition_order FROM planning_category WHERE owner = :owner AND since = :since
), codes AS (
    SELECT category FROM configured UNION SELECT category FROM recorded
), rates AS (
    SELECT category, period, plan, 1 AS recorded FROM category_month
     WHERE owner = :owner AND period <= :period
    UNION ALL
    SELECT category, since, plan, 0 FROM planning_category
     WHERE owner = :owner AND since <= :period
)
SELECT codes.category, coalesce(p.name, c.name) AS name, m.plan, m.actual, m.category IS NOT NULL AS recorded,
       coalesce((SELECT r.plan FROM rates r WHERE r.category = codes.category
                  ORDER BY r.period DESC, r.recorded DESC LIMIT 1), 0) AS effective_plan
  FROM codes JOIN category c ON c.code = codes.category
  LEFT JOIN configured p ON p.category = codes.category
  LEFT JOIN recorded m ON m.category = codes.category
 ORDER BY coalesce(p.addition_order, c.display_order), codes.category;
