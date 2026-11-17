package org.financelab.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces one owner's committed monthly figures. */
@Repository
class MonthStore {
    private final PlanStore plans;
    private final BudgetPeriod period;
    private final JdbcClient jdbc;
    private final String readMonthSql;
    private final String replaceMonthSql;

    MonthStore(JdbcClient jdbc, PlanStore plans, BudgetPeriod period) throws IOException {
        this.plans = plans;
        this.period = period;
        this.jdbc = jdbc;
        this.readMonthSql = new ClassPathResource("sql/queries/read-month.sql").getContentAsString(StandardCharsets.UTF_8);
        this.replaceMonthSql = new ClassPathResource("sql/queries/replace-month.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public MonthBudget read(String owner, YearMonth month) {
        var terms = plans.terms(owner, month);
        YearMonth current = period.current();
        var figures = jdbc.sql("SELECT income, contributions, revision FROM month_actuals WHERE owner = ? AND period = ?")
                .params(owner, month.toString())
                .query((rs, i) -> new MonthBudget.Figures(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .optional().orElse(new MonthBudget.Figures(0, 0, 0));
        var categories = jdbc.sql(readMonthSql)
                .param("owner", owner).param("period", month.toString())
                .param("since", terms == null ? "" : terms.since())
                .param("cutoff", (month.isBefore(current) ? month : current).toString())
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getString("kind"), rs.getLong("plan"), rs.getLong("actual"),
                        rs.getLong("opening_buffer")))
                .list();
        return new MonthBudget(terms, figures, categories);
    }

    @Transactional
    public boolean replace(String owner, YearMonth month, MonthBudget saved, long revision, MonthActuals actuals) {
        String key = month.toString();
        boolean changed = actuals.income() != saved.figures().income()
                || actuals.contributions() != saved.figures().contributions()
                || saved.categories().stream().anyMatch(row -> row.actual() != actuals.spending().get(row.code()));
        int claimed = jdbc.sql(replaceMonthSql).param("owner", owner).param("period", key)
                .param("income", actuals.income()).param("contributions", actuals.contributions())
                .param("revision", revision).param("nextRevision", changed ? revision + 1 : revision)
                .param("planRevision", saved.planRevision()).update();
        if (claimed == 0) return false;

        for (var row : saved.categories()) {
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (owner, period, category) DO UPDATE SET actual = excluded.actual
                    """).params(owner, key, row.code(), row.plan(), actuals.spending().get(row.code())).update();
        }
        return true;
    }
}
