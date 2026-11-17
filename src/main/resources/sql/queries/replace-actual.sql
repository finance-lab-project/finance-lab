INSERT INTO category_month (owner, period, category, plan, actual, revision)
SELECT :owner, :period, :category, :plan, :amount, CASE WHEN :amount = 0 THEN 0 ELSE 1 END
 WHERE :revision = 0 OR EXISTS (
       SELECT 1 FROM category_month WHERE owner = :owner AND period = :period
         AND category = :category AND revision = :revision)
ON CONFLICT (owner, period, category) DO UPDATE
   SET actual = excluded.actual,
       revision = CASE WHEN category_month.actual = excluded.actual THEN category_month.revision
                       ELSE category_month.revision + 1 END
 WHERE category_month.revision = :revision;
