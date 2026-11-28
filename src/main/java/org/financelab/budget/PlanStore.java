package org.financelab.budget;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PlanStore {
    record Terms(String since, long income, Fraction target, long revision) {}
    record Settings(long revision, PlanDraft draft) {}

    private final JdbcClient jdbc;
    private final String saveSettingsSql;

    PlanStore(JdbcClient jdbc) throws IOException {
        this.jdbc = jdbc;
        saveSettingsSql = new ClassPathResource("sql/queries/save-settings.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    Terms terms(String owner, YearMonth month) {
        return jdbc.sql("""
                SELECT since, income, target, revision FROM planning_settings
                 WHERE owner = ? AND since <= ? ORDER BY since DESC LIMIT 1
                """).params(owner, month.toString())
                .query((r, i) -> new Terms(r.getString(1), r.getLong(2), Fraction.read(r.getString(3)), r.getLong(4)))
                .optional().orElse(null);
    }

    @Transactional
    public Settings read(String owner, YearMonth current) {
        refreshForYear(owner, current);
        return savedSettings(owner, current);
    }

    private Settings savedSettings(String owner, YearMonth month) {
        Terms terms = terms(owner, month);
        if (terms == null) return new Settings(0, PlanDraft.empty());
        List<PlanDraft.Row> rows = jdbc.sql("""
                SELECT category, name, kind, unit, value FROM planning_category
                 WHERE owner = ? AND since = ? ORDER BY addition_order
                """).params(owner, terms.since())
                .query((r, i) -> new PlanDraft.Row(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getString(5))).list();
        return new Settings(terms.revision(), new PlanDraft(Long.toString(terms.income()), "fraction", terms.target().stored(), rows));
    }

    // Claim the new snapshot before reading history so simultaneous requests share one refresh.
    void refreshForYear(String owner, YearMonth current) {
        YearMonth january = current.withMonth(1);
        int added = jdbc.sql("""
                INSERT INTO planning_settings (owner, since, income, target, revision)
                SELECT owner, :january, income, target, revision + 1
                  FROM (
                    SELECT owner, since, income, target, revision FROM planning_settings
                     WHERE owner = :owner AND since <= :current ORDER BY since DESC LIMIT 1
                  )
                 WHERE since < :january
                """).param("owner", owner).param("current", current.toString())
                .param("january", january.toString()).update();
        if (added == 0) return;

        PlanDraft saved = savedSettings(owner, january.minusMonths(1)).draft();
        var plan = PlanCalculator.evaluate(withHistoricalAverages(owner, january, saved));
        // Rollover records settings only. Existing month rows retain their recorded plans.
        saveCategories(owner, january.toString(), plan, false, false);
    }

    // Called within the month write transaction so the new plans and historical figures commit together.
    void recalculateFromHistory(String owner, YearMonth current) {
        Settings settings = savedSettings(owner, current);
        PlanDraft saved = settings.draft();
        PlanDraft updated = withHistoricalAverages(owner, current.withMonth(1), saved);
        if (updated.rows().equals(saved.rows())) return;

        if (!save(owner, current, settings.revision(), PlanCalculator.evaluate(updated), false)) {
            throw new IllegalStateException("Planning settings changed during the historical save.");
        }
    }

    private PlanDraft withHistoricalAverages(String owner, YearMonth january, PlanDraft saved) {
        if (saved.rows().isEmpty()) return saved;

        Map<String, BigInteger> totals = new HashMap<>();
        // Explicit zero expenses count as history and have a positive revision.
        jdbc.sql("""
                SELECT category, actual FROM category_month
                 WHERE owner = ? AND period >= ? AND period < ? AND (actual <> 0 OR revision > 0)
                """).params(owner, january.minusYears(1).toString(), january.toString())
                .query(rs -> {
                    totals.merge(rs.getString("category"), BigInteger.valueOf(rs.getLong("actual")), BigInteger::add);
                });
        List<PlanDraft.Row> rows = saved.rows().stream().map(row -> {
            BigInteger total = totals.get(row.code());
            if (!"essential".equals(row.group()) || total == null) return row;
            BigInteger monthly = new Fraction(total, BigInteger.valueOf(12)).rounded().max(BigInteger.ZERO);
            return row.withValue("amount", monthly.toString());
        }).toList();
        return new PlanDraft(saved.income(), saved.targetUnit(), saved.targetValue(), rows);
    }

    @Transactional
    public boolean save(String owner, YearMonth month, long revision, PlanCalculator.Preview plan) {
        return save(owner, month, revision, plan, true);
    }

    private boolean save(String owner, YearMonth month, long revision, PlanCalculator.Preview plan,
                         boolean explicitSave) {
        PlanDraft draft = plan.draft();
        String since = month.toString();
        // Acquire the write transaction and compare the version before changing any category or month.
        int changed = jdbc.sql(saveSettingsSql).param("owner", owner).param("period", since)
                .param("income", Rubles.parse(draft.income())).param("target", draft.targetValue())
                .param("revision", revision).update();
        if (changed == 0) return false;
        saveCategories(owner, since, plan, explicitSave, true);
        return true;
    }

    private void saveCategories(String owner, String since, PlanCalculator.Preview plan,
                                boolean recordMonth, boolean refreshInherited) {
        PlanDraft draft = plan.draft();
        Map<String, Long> previousRates = new HashMap<>();
        jdbc.sql("""
                SELECT p.category, p.plan FROM planning_category p
                 WHERE p.owner = :owner AND p.since = (
                     SELECT max(since) FROM planning_category WHERE owner = :owner AND since <= :since
                 ) AND ((p.since < :since AND NOT :recordMonth) OR EXISTS (
                     SELECT 1 FROM planning_category_rollover_copy c WHERE c.owner = p.owner
                       AND c.since = p.since AND c.category = p.category
                 ) AND p.since = :since)
                """).param("owner", owner).param("since", since).param("recordMonth", recordMonth)
                .query(rs -> { previousRates.put(rs.getString("category"), rs.getLong("plan")); });
        jdbc.sql("DELETE FROM planning_category WHERE owner = ? AND since = ?").params(owner, since).update();
        for (int i = 0; i < draft.rows().size(); i++) {
            PlanDraft.Row row = draft.rows().get(i);
            String code = row.code();
            if (code.isEmpty()) {
                code = UUID.randomUUID().toString();
                // The original month table references this catalogue. Owner-specific labels live in planning_category.
                jdbc.sql("""
                        INSERT INTO category (code, name, display_order)
                        SELECT ?, ?, coalesce(max(display_order), 0) + 1 FROM category
                        """).params(code, row.name().strip()).update();
            }
            long rubles = Long.parseLong(plan.fields().get(i).amount());
            jdbc.sql("""
                    INSERT INTO planning_category (owner, since, category, name, kind, addition_order, unit, value, plan)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(owner, since, code, row.name().strip(), row.group(), i + 1, row.unit(), row.value(), rubles).update();
            if (Long.valueOf(rubles).equals(previousRates.get(code))) {
                jdbc.sql("INSERT INTO planning_category_rollover_copy (owner, since, category) VALUES (?, ?, ?)")
                        .params(owner, since, code).update();
            }
            if (recordMonth) {
                int added = jdbc.sql("""
                        INSERT INTO category_month (owner, period, category, plan, actual) VALUES (?, ?, ?, ?, 0)
                        ON CONFLICT(owner, period, category) DO NOTHING
                        """).params(owner, since, code, rubles).update();
                if (added == 1) {
                    jdbc.sql("""
                            INSERT INTO category_month_inherited_plan (owner, period, category) VALUES (?, ?, ?)
                            """).params(owner, since, code).update();
                }
            }
            if (refreshInherited) {
                // An explicit rate supersedes the calculated setting for inherited rows that follow it.
                jdbc.sql("""
                        UPDATE category_month SET plan = ?
                         WHERE owner = ? AND period >= ? AND category = ? AND plan <> ?
                           AND EXISTS (SELECT 1 FROM category_month_inherited_plan i
                                        WHERE i.owner = category_month.owner AND i.period = category_month.period
                                          AND i.category = category_month.category)
                           AND NOT EXISTS (
                               SELECT 1 FROM category_month m
                                WHERE m.owner = category_month.owner AND m.category = category_month.category
                                  AND m.period BETWEEN ? AND category_month.period
                                  AND NOT EXISTS (SELECT 1 FROM category_month_inherited_plan i
                                                   WHERE i.owner = m.owner AND i.period = m.period
                                                     AND i.category = m.category)
                           )
                        """).params(rubles, owner, since, code, rubles, since).update();
            }
        }
    }
}
