, recorded AS (
    SELECT m.category, m.plan, m.actual, m.revision, i.category IS NULL AS explicit_plan
      FROM category_month m LEFT JOIN category_month_inherited_plan i USING (owner, period, category)
     WHERE m.owner = :owner AND m.period = :period
), configured AS (
    SELECT category, name, addition_order FROM planning_category WHERE owner = :owner AND since = :since
), codes AS (
    SELECT category FROM configured UNION SELECT category FROM recorded
)
SELECT codes.category, coalesce(p.name, c.name) AS name, m.plan, m.actual, m.category IS NOT NULL AS recorded, m.explicit_plan,
       (m.actual <> 0 OR m.revision > 0) AS actual_recorded,
       coalesce(m.plan, (SELECT r.plan FROM periods r WHERE r.category = codes.category
                           AND r.period <= :period
                           AND (r.until IS NULL OR r.until > :period)), 0) AS effective_plan
  FROM codes JOIN category c ON c.code = codes.category
  LEFT JOIN configured p ON p.category = codes.category
  LEFT JOIN recorded m ON m.category = codes.category
 ORDER BY coalesce(p.addition_order, c.display_order), codes.category;
