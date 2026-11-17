INSERT INTO planning_settings (owner, since, income, target, revision)
SELECT :owner, :period, :income, :target, :revision + 1
 WHERE coalesce((SELECT revision FROM planning_settings
                  WHERE owner = :owner AND since <= :period ORDER BY since DESC LIMIT 1), 0) = :revision
ON CONFLICT (owner, since) DO UPDATE
   SET income = excluded.income, target = excluded.target, revision = excluded.revision;
