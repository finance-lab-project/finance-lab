-- Claim the owner/month revision before writing any category. Settings also version the
-- category list and the planning income used to convert a percentage into rubles.
INSERT INTO month_actuals (owner, period, income, contributions, revision)
SELECT :owner, :period, :income, :contributions, :nextRevision
 WHERE coalesce((SELECT revision FROM month_actuals WHERE owner = :owner AND period = :period), 0) = :revision
   AND coalesce((SELECT revision FROM planning_settings WHERE owner = :owner AND since <= :period
                  ORDER BY since DESC LIMIT 1), 0) = :planRevision
ON CONFLICT (owner, period) DO UPDATE SET income = excluded.income,
    contributions = excluded.contributions, revision = excluded.revision;
