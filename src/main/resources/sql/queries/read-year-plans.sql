SELECT p.category, c.name, p.plan,
       CASE WHEN p.ordinal = 1 AND p.start IS NULL THEN :january
            ELSE max(p.period, :january) END AS first_month,
       p.until AS next_month
  FROM periods p JOIN category c ON c.code = p.category
 WHERE p.until IS NULL OR p.until > :january
 ORDER BY c.display_order, p.period;
