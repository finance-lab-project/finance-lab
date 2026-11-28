, effective_rates AS (
    SELECT category, plan FROM periods WHERE period <= :period AND (until IS NULL OR until > :period)
), settings_categories AS (
    SELECT p.category, p.name, p.kind, p.addition_order, coalesce(r.plan, p.plan) AS plan
      FROM planning_category p LEFT JOIN effective_rates r ON r.category = p.category
     WHERE p.owner = :owner AND p.since = :since
), rows AS (
    SELECT p.category, p.name, p.kind, p.addition_order, p.plan,
           coalesce(m.actual, 0) AS actual, (m.actual <> 0 OR m.revision > 0) AS actual_recorded
      FROM settings_categories p
 LEFT JOIN category_month m ON m.owner = :owner AND m.period = :period AND m.category = p.category
    UNION ALL
    SELECT m.category, coalesce(p.name, c.name), coalesce(p.kind, 'essential'),
           coalesce(p.addition_order, c.display_order),
           CASE WHEN i.category IS NULL THEN m.plan ELSE coalesce(r.plan, 0) END, m.actual, (m.actual <> 0 OR m.revision > 0)
      FROM category_month m JOIN category c ON c.code = m.category
 LEFT JOIN category_month_inherited_plan i ON i.owner = m.owner AND i.period = m.period AND i.category = m.category
 LEFT JOIN effective_rates r ON r.category = m.category
 LEFT JOIN planning_category p ON p.owner = m.owner AND p.since = :catalogueSince AND p.category = m.category
     WHERE m.owner = :owner AND m.period = :period
       AND NOT EXISTS (SELECT 1 FROM settings_categories s WHERE s.category = m.category)
    UNION ALL
    SELECT r.category, coalesce(p.name, c.name), coalesce(p.kind, 'essential'),
           coalesce(p.addition_order, c.display_order), r.plan, 0, 0
      FROM effective_rates r JOIN category c ON c.code = r.category
 LEFT JOIN planning_category p ON p.owner = :owner AND p.since = :catalogueSince AND p.category = r.category
     WHERE NOT EXISTS (SELECT 1 FROM settings_categories s WHERE s.category = r.category)
       AND NOT EXISTS (SELECT 1 FROM category_month m WHERE m.owner = :owner
                        AND m.period = :period AND m.category = r.category)
)
SELECT r.*, CASE WHEN :withBuffer THEN
                 (SELECT coalesce(sum(h.plan - h.actual), 0) FROM category_month h
                   WHERE h.owner = :owner AND h.category = r.category AND h.period < :cutoff)
               ELSE 0 END AS opening_buffer
  FROM rows r ORDER BY r.addition_order;
