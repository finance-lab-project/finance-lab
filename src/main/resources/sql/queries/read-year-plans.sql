-- The reserved catalogue may predate an owner's initial settings. Other keys are
-- created by saving a category, and a later addition has a known start in either case.
WITH inception AS (
    SELECT category, min(since) AS start FROM planning_category
     WHERE owner = :owner GROUP BY category
    HAVING category NOT IN ('housing', 'food', 'health', 'leisure', 'travel')
        OR min(since) > (SELECT min(since) FROM planning_settings WHERE owner = :owner)
), rates AS (
    SELECT category, period, plan FROM category_month WHERE owner = :owner AND period < :end
    UNION ALL
    SELECT p.category, p.since, p.plan FROM planning_category p
     WHERE p.owner = :owner AND p.since < :end
       AND NOT EXISTS (SELECT 1 FROM category_month m WHERE m.owner = p.owner
                        AND m.category = p.category AND m.period = p.since)
), periods AS (
    SELECT r.*, i.start,
           row_number() OVER (PARTITION BY r.category ORDER BY r.period) AS ordinal,
           lead(r.period, 1, :end) OVER (PARTITION BY r.category ORDER BY r.period) AS until
      FROM rates r LEFT JOIN inception i ON i.category = r.category
     WHERE i.start IS NULL OR r.period >= i.start
)
SELECT p.category, c.name, p.plan,
       CASE WHEN p.ordinal = 1 AND p.start IS NULL THEN :january
            ELSE max(p.period, :january) END AS first_month,
       p.until AS next_month
  FROM periods p JOIN category c ON c.code = p.category
 WHERE p.until > :january
 ORDER BY c.display_order, p.period;
