WITH monthly_rates AS (
    SELECT m.category, m.period, m.plan FROM category_month m
     WHERE m.owner = :owner AND m.period <= :last
       AND NOT EXISTS (SELECT 1 FROM category_month_inherited_plan i WHERE i.owner = m.owner
                        AND i.category = m.category AND i.period = m.period)
), inception AS (
    SELECT category, since AS start FROM category_plan_inception WHERE owner = :owner
    UNION ALL
    SELECT f.category, f.since FROM inferred_category_plan_inception f
     WHERE f.owner = :owner AND NOT EXISTS (
         SELECT 1 FROM category_plan_inception s WHERE s.owner = f.owner AND s.category = f.category
     )
), rates AS (
    SELECT category, period, plan FROM monthly_rates
    UNION ALL
    SELECT p.category, p.since, p.plan FROM planning_category p
     WHERE p.owner = :owner AND p.since <= :last
       AND NOT EXISTS (SELECT 1 FROM monthly_rates m WHERE m.category = p.category AND m.period = p.since)
       AND NOT EXISTS (SELECT 1 FROM planning_category_rollover_copy c WHERE c.owner = p.owner
                        AND c.since = p.since AND c.category = p.category)
), periods AS (
    SELECT r.*, i.start,
           row_number() OVER (PARTITION BY r.category ORDER BY r.period) AS ordinal,
           lead(r.period) OVER (PARTITION BY r.category ORDER BY r.period) AS until
      FROM rates r LEFT JOIN inception i ON i.category = r.category
     WHERE i.start IS NULL OR r.period >= i.start
)
