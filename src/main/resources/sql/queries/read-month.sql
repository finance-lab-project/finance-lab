SELECT m.category, c.name, m.plan, m.actual, m.revision,
       (SELECT coalesce(sum(h.plan - h.actual), 0)
          FROM category_month h
         WHERE h.owner = m.owner AND h.category = m.category
           AND h.period < m.period) AS opening_buffer
  FROM category_month m JOIN category c ON c.code = m.category
 WHERE m.owner = :owner AND m.period = :period
 ORDER BY c.display_order;
