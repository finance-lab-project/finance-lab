-- The reserved catalogue may predate an owner's initial settings. Other keys are
-- created by saving a category, and a later addition has a known start in either case.
WITH first_settings AS (
    SELECT category, min(since) AS start FROM planning_category
     WHERE owner = :owner GROUP BY category
), monthly_rates AS (
    SELECT m.category, m.period, m.plan FROM category_month m
     WHERE m.owner = :owner AND m.period <= :last
       AND NOT EXISTS (SELECT 1 FROM category_month_inherited_plan i WHERE i.owner = m.owner
                        AND i.category = m.category AND i.period = m.period)
), inception AS (
    SELECT f.category, f.start FROM first_settings f
     WHERE f.category NOT IN ('housing', 'food', 'health', 'leisure', 'travel')
        OR (f.start > (SELECT min(since) FROM planning_settings WHERE owner = :owner)
            AND NOT EXISTS (SELECT 1 FROM monthly_rates h WHERE h.category = f.category AND h.period < f.start))
), rates AS (
    SELECT category, period, plan FROM monthly_rates
    UNION ALL
    SELECT p.category, p.since, p.plan FROM planning_category p
     WHERE p.owner = :owner AND p.since <= :last
       AND NOT EXISTS (SELECT 1 FROM monthly_rates m WHERE m.category = p.category AND m.period = p.since)
       AND NOT EXISTS (SELECT 1 FROM planning_category_rollover_copy c WHERE c.owner = p.owner
                        AND c.since = p.since AND c.category = p.category)
       -- Older automatic January snapshots copied unchanged rates without recording a month row.
       -- Explicit settings saves record a month row, and unmarked monthly rates are handled above.
       AND NOT (
           substr(p.since, 6, 2) = '01'
           AND NOT EXISTS (SELECT 1 FROM category_month m WHERE m.owner = p.owner
                            AND m.period = p.since AND m.category = p.category)
           AND EXISTS (
               SELECT 1 FROM planning_category previous
                WHERE previous.owner = p.owner AND previous.category = p.category AND previous.plan = p.plan
                  AND previous.since = (
                      SELECT max(since) FROM planning_settings WHERE owner = p.owner AND since < p.since
                  )
           )
       )
), periods AS (
    SELECT r.*, i.start,
           row_number() OVER (PARTITION BY r.category ORDER BY r.period) AS ordinal,
           lead(r.period) OVER (PARTITION BY r.category ORDER BY r.period) AS until
      FROM rates r LEFT JOIN inception i ON i.category = r.category
     WHERE i.start IS NULL OR r.period >= i.start
)
