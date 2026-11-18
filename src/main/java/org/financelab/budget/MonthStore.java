package org.financelab.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces one owner's committed monthly figures. */
@Repository
class MonthStore {
    record HistoricalCategory(String code, String name, long plan, long actual, boolean recorded) {}
    record History(MonthBudget.Figures figures, long planRevision, List<HistoricalCategory> categories) {
        public String percent(long amount) {
            return figures.income() == 0 ? "—" : PlanCalculator.displayedPercent(amount, figures.income()) + " %";
        }
    }

    private final PlanStore plans;
    private final BudgetPeriod period;
    private final JdbcClient jdbc;
    private final String readMonthSql;
    private final String readHistorySql;
    private final String replaceMonthSql;

    MonthStore(JdbcClient jdbc, PlanStore plans, BudgetPeriod period) throws IOException {
        this.plans = plans;
        this.period = period;
        this.jdbc = jdbc;
        this.readMonthSql = new ClassPathResource("sql/queries/read-month.sql").getContentAsString(StandardCharsets.UTF_8);
        this.readHistorySql = new ClassPathResource("sql/queries/read-history.sql").getContentAsString(StandardCharsets.UTF_8);
        this.replaceMonthSql = new ClassPathResource("sql/queries/replace-month.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public MonthBudget read(String owner, YearMonth month) {
        var terms = plans.terms(owner, month);
        YearMonth current = period.current();
        var catalogue = month.isBefore(current) ? plans.terms(owner, current) : terms;
        var categories = jdbc.sql(readMonthSql)
                .param("owner", owner).param("period", month.toString())
                .param("since", terms == null ? "" : terms.since())
                .param("catalogueSince", catalogue == null ? "" : catalogue.since())
                .param("cutoff", (month.isBefore(current) ? month : current).toString())
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getString("kind"), rs.getLong("plan"), rs.getLong("actual"),
                        rs.getLong("opening_buffer")))
                .list();
        return new MonthBudget(terms, figures(owner, month), categories);
    }

    @Transactional(readOnly = true)
    public History readHistory(String owner, YearMonth month) {
        var terms = plans.terms(owner, period.current());
        var categories = jdbc.sql(readHistorySql)
                .param("owner", owner).param("period", month.toString()).param("since", terms == null ? "" : terms.since())
                .query((rs, index) -> new HistoricalCategory(rs.getString("category"), rs.getString("name"),
                        rs.getLong("plan"), rs.getLong("actual"), rs.getBoolean("recorded"))).list();
        return new History(figures(owner, month), terms == null ? 0 : terms.revision(), categories);
    }

    private MonthBudget.Figures figures(String owner, YearMonth month) {
        return jdbc.sql("SELECT income, contributions, revision FROM month_actuals WHERE owner = ? AND period = ?")
                .params(owner, month.toString())
                .query((rs, i) -> new MonthBudget.Figures(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .optional().orElse(new MonthBudget.Figures(0, 0, 0));
    }

    @Transactional
    public boolean replace(String owner, YearMonth month, MonthBudget saved, long revision, MonthActuals actuals) {
        boolean changed = actuals.income() != saved.figures().income()
                || actuals.contributions() != saved.figures().contributions()
                || saved.categories().stream().anyMatch(row -> row.actual() != actuals.spending().get(row.code()));
        var figures = new MonthBudget.Figures(actuals.income(), actuals.contributions(), changed ? revision + 1 : revision);
        if (!claim(owner, month, month, revision, saved.planRevision(), figures)) return false;

        for (var row : saved.categories()) {
            saveCategory(owner, month, row.code(), row.plan(), actuals.spending().get(row.code()), false);
        }
        if (changed) refreshPlan(owner, month, period.current());
        return true;
    }

    @Transactional
    public boolean replaceHistory(String owner, YearMonth month, History saved, long income, long contributions,
                                  List<HistoricalCategory> categories) {
        YearMonth current = period.current();
        long revision = saved.figures().revision();
        var figures = new MonthBudget.Figures(income, contributions, revision + 1);
        if (!month.isBefore(current) || !claim(owner, month, current, revision, saved.planRevision(), figures)) return false;
        for (var row : categories) {
            saveCategory(owner, month, row.code(), row.plan(), row.actual(), true);
        }
        refreshPlan(owner, month, current);
        return true;
    }

    private boolean claim(String owner, YearMonth month, YearMonth settingsMonth, long revision, long planRevision,
                          MonthBudget.Figures figures) {
        return jdbc.sql(replaceMonthSql).param("owner", owner).param("period", month.toString())
                .param("income", figures.income()).param("contributions", figures.contributions())
                .param("revision", revision).param("nextRevision", figures.revision())
                .param("settingsPeriod", settingsMonth.toString()).param("planRevision", planRevision).update() == 1;
    }

    private void saveCategory(String owner, YearMonth month, String category, long plan, long actual, boolean history) {
        jdbc.sql("""
                INSERT INTO category_month (owner, period, category, plan, actual) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (owner, period, category) DO UPDATE
                   SET plan = CASE WHEN ? THEN excluded.plan ELSE category_month.plan END,
                       actual = excluded.actual
                """).params(owner, month.toString(), category, plan, actual, history).update();
    }

    private void refreshPlan(String owner, YearMonth savedMonth, YearMonth current) {
        if (savedMonth.getYear() == current.getYear() - 1) {
            plans.recalculateFromHistory(owner, current);
        }
    }
}
