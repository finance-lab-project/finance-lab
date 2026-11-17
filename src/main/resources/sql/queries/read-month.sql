WITH rows AS (
    SELECT p.category, p.name, p.kind, p.addition_order, coalesce(m.plan, p.plan) AS plan,
           coalesce(m.actual, 0) AS actual
      FROM planning_category p
 LEFT JOIN category_month m ON m.owner = p.owner AND m.period = :period AND m.category = p.category
     WHERE p.owner = :owner AND p.since = :since
    UNION ALL
    SELECT m.category, c.name, 'essential', c.display_order, m.plan, m.actual
      FROM category_month m JOIN category c ON c.code = m.category
     WHERE m.owner = :owner AND m.period = :period AND :since = ''
)
SELECT r.*, (SELECT coalesce(sum(h.plan - h.actual), 0) FROM category_month h
              WHERE h.owner = :owner AND h.category = r.category AND h.period < :cutoff) AS opening_buffer
  FROM rows r ORDER BY r.addition_order;
