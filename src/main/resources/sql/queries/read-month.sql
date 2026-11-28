WITH settings_categories AS (
    SELECT p.category, p.name, p.kind, p.addition_order,
           coalesce((SELECT h.plan FROM category_month h
                      WHERE h.owner = p.owner AND h.category = p.category
                        AND h.period >= p.since AND h.period <= :period
                      ORDER BY h.period DESC LIMIT 1), p.plan) AS plan
      FROM planning_category p WHERE p.owner = :owner AND p.since = :since
), rows AS (
    SELECT p.category, p.name, p.kind, p.addition_order, coalesce(m.plan, p.plan) AS plan,
           coalesce(m.actual, 0) AS actual
      FROM settings_categories p
 LEFT JOIN category_month m ON m.owner = :owner AND m.period = :period AND m.category = p.category
    UNION ALL
    SELECT m.category, coalesce(p.name, c.name), coalesce(p.kind, 'essential'),
           coalesce(p.addition_order, c.display_order), m.plan, m.actual
      FROM category_month m JOIN category c ON c.code = m.category
 LEFT JOIN planning_category p ON p.owner = m.owner AND p.since = :catalogueSince AND p.category = m.category
     WHERE m.owner = :owner AND m.period = :period
       AND NOT EXISTS (SELECT 1 FROM settings_categories s WHERE s.category = m.category)
)
SELECT r.*, CASE WHEN :withBuffer THEN
                 (SELECT coalesce(sum(h.plan - h.actual), 0) FROM category_month h
                   WHERE h.owner = :owner AND h.category = r.category AND h.period < :cutoff)
               ELSE 0 END AS opening_buffer
  FROM rows r ORDER BY r.addition_order;
