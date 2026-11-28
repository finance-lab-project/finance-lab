, effective_rates AS (
    SELECT category, plan FROM periods WHERE period <= :period AND (until IS NULL OR until > :period)
)
