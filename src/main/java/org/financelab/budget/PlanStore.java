package org.financelab.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
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

    @Transactional(readOnly = true)
    public Settings read(String owner, YearMonth month) {
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

    @Transactional
    public boolean save(String owner, YearMonth month, long revision, PlanCalculator.Preview plan) {
        PlanDraft draft = plan.draft();
        String since = month.toString();
        // Acquire the write transaction and compare the version before changing any category or month.
        int changed = jdbc.sql(saveSettingsSql).param("owner", owner).param("period", since)
                .param("income", Rubles.parse(draft.income())).param("target", draft.targetValue())
                .param("revision", revision).update();
        if (changed == 0) return false;

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
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES (?, ?, ?, ?, 0)
                    ON CONFLICT(owner, period, category) DO UPDATE SET plan = excluded.plan
                    """).params(owner, since, code, rubles).update();
            jdbc.sql("UPDATE category_month SET plan = ? WHERE owner = ? AND period > ? AND category = ?")
                    .params(rubles, owner, since, code).update();
        }
        return true;
    }
}
