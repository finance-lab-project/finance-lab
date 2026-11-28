package org.financelab.budget;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces one owner's committed monthly figures. */
@Repository
class MonthStore {
    record HistoricalCategory(String code, String name, long plan, long actual, boolean recorded, boolean actualRecorded) {}
    record HistoricalEdit(String code, long plan, long actual, boolean actualProvided) {}
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

    @Transactional
    public MonthBudget read(String owner, YearMonth month) {
        YearMonth current = period.current();
        plans.refreshForYear(owner, current);
        return read(owner, month, current, true);
    }

    private MonthBudget read(String owner, YearMonth month, YearMonth current, boolean withBuffer) {
        var terms = plans.terms(owner, month);
        var catalogue = month.isBefore(current) ? plans.terms(owner, current) : terms;
        var categories = jdbc.sql(readMonthSql)
                .param("owner", owner).param("period", month.toString())
                .param("since", terms == null ? "" : terms.since())
                .param("catalogueSince", catalogue == null ? "" : catalogue.since())
                .param("withBuffer", withBuffer)
                .param("cutoff", (month.isBefore(current) ? month : current).toString())
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getString("kind"), rs.getLong("plan"), rs.getLong("actual"),
                        rs.getBoolean("actual_recorded"), rs.getLong("opening_buffer")))
                .list();
        return new MonthBudget(terms, figures(owner, month), categories);
    }

    @Transactional
    public YearReport readYear(String owner, int year, YearMonth current) {
        plans.refreshForYear(owner, current);
        YearMonth january = YearMonth.of(year, 1);
        YearMonth nextJanuary = january.plusYears(1);
        YearMonth cutoff = current.isBefore(nextJanuary) ? current : nextJanuary;
        YearMonth averageStart = cutoff.minusMonths(12);

        var months = new ArrayList<YearReport.Month>(12);
        var categories = new LinkedHashMap<String, YearReport.Category>();
        var totals = YearReport.Actuals.ZERO;
        BigInteger spendingPlan = BigInteger.ZERO;
        BigInteger investmentPlan = BigInteger.ZERO;
        for (int index = 0; index < 12; index++) {
            YearMonth month = january.plusMonths(index);
            MonthBudget budget = read(owner, month, current, false);
            YearReport.Actuals actuals = YearReport.Actuals.from(budget);
            months.add(new YearReport.Month(month, actuals));
            totals = totals.plus(actuals);
            spendingPlan = spendingPlan.add(budget.totalPlan());
            if (budget.terms() != null) {
                investmentPlan = investmentPlan.add(BigInteger.valueOf(budget.allocation().investments()));
            }
            for (var row : budget.categories()) {
                var category = categories.computeIfAbsent(row.code(), code -> YearReport.Category.empty(code, row.name()));
                categories.put(row.code(), category.withMonth(row));
            }
        }

        var spendingByMonth = new HashMap<YearMonth, BigInteger>();
        // Carry recorded plans forward, including records from earlier years.
        jdbc.sql("""
                SELECT m.period, m.category, c.name, m.plan, m.actual, m.revision
                  FROM category_month m JOIN category c ON c.code = m.category
                 WHERE m.owner = ? AND m.period <= ?
                """).params(owner, cutoff.minusMonths(1).toString()).query(rs -> {
            String code = rs.getString("category");
            String name = rs.getString("name");
            var category = categories.computeIfAbsent(code, key -> YearReport.Category.empty(key, name));
            var actual = BigInteger.valueOf(rs.getLong("actual"));
            var balance = BigInteger.valueOf(rs.getLong("plan")).subtract(actual);
            category = category.withBalance(balance);
            YearMonth month = YearMonth.parse(rs.getString("period"));
            // Legacy plan rows have zero actuals and no actual revision.
            if (!month.isBefore(averageStart) && (actual.signum() != 0 || rs.getLong("revision") > 0)) {
                category = category.withRecordedActual(actual);
                spendingByMonth.merge(month, actual, BigInteger::add);
            }
            categories.put(code, category);
        });
        var incomeAverage = YearReport.Average.EMPTY;
        var contributionsAverage = YearReport.Average.EMPTY;
        var spendingAverage = YearReport.Average.EMPTY;
        for (YearMonth month = averageStart; month.isBefore(cutoff); month = month.plusMonths(1)) {
            var figures = figures(owner, month);
            if (figures.incomeRecorded()) {
                incomeAverage = incomeAverage.withMonth(BigInteger.valueOf(figures.income()));
            }
            if (figures.contributionsRecorded()) {
                contributionsAverage = contributionsAverage.withMonth(BigInteger.valueOf(figures.contributions()));
            }
            var spending = spendingByMonth.get(month);
            if (spending != null) spendingAverage = spendingAverage.withMonth(spending);
        }

        return new YearReport(year, cutoff, averageStart, List.copyOf(months), totals, incomeAverage,
                spendingAverage, contributionsAverage, spendingPlan, investmentPlan, List.copyOf(categories.values()));
    }

    @Transactional
    public History readHistory(String owner, YearMonth month) {
        YearMonth current = period.current();
        plans.refreshForYear(owner, current);
        var terms = plans.terms(owner, current);
        var categories = jdbc.sql(readHistorySql)
                .param("owner", owner).param("period", month.toString()).param("since", terms == null ? "" : terms.since())
                .query((rs, index) -> new HistoricalCategory(rs.getString("category"), rs.getString("name"),
                        rs.getLong("plan"), rs.getLong("actual"), rs.getBoolean("recorded"),
                        rs.getBoolean("actual_recorded"))).list();
        return new History(figures(owner, month), terms == null ? 0 : terms.revision(), categories);
    }

    private MonthBudget.Figures figures(String owner, YearMonth month) {
        return savedFigures(owner, month).orElse(new MonthBudget.Figures(0, 0, 0, false, false));
    }

    Optional<MonthBudget.Figures> savedFigures(String owner, YearMonth month) {
        // A legacy nonzero value proves entry. The monthly revision only proves a save occurred.
        return jdbc.sql("""
                SELECT m.income, m.contributions, m.revision,
                       (m.income <> 0 OR coalesce(r.income_recorded, 0)) AS income_recorded,
                       (m.contributions <> 0 OR coalesce(r.contributions_recorded, 0)) AS contributions_recorded
                  FROM month_actuals m LEFT JOIN month_figure_recording r USING (owner, period)
                 WHERE m.owner = ? AND m.period = ?
                """).params(owner, month.toString())
                .query((rs, i) -> new MonthBudget.Figures(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getBoolean(4), rs.getBoolean(5)))
                .optional();
    }

    @Transactional
    public boolean replace(String owner, YearMonth month, MonthBudget saved, long revision, MonthActuals actuals) {
        var figures = submittedFigures(actuals.income(), actuals.contributions(), revision);
        boolean changed = !figures.equals(saved.figures())
                || saved.categories().stream().anyMatch(row -> {
                    Long actual = actuals.spending().get(row.code());
                    return actual != null && (!row.actualRecorded() || row.actual() != actual);
                });
        if (!claim(owner, month, month, revision, saved.planRevision(), figures, changed ? revision + 1 : revision)) {
            return false;
        }

        for (var row : saved.categories()) {
            Long actual = actuals.spending().get(row.code());
            saveCategory(owner, month, row.code(), row.plan(), actual == null ? 0 : actual, false,
                    actual == null ? 0 : 1);
        }
        if (changed) refreshPlan(owner, month, period.current());
        return true;
    }

    @Transactional
    public boolean replaceHistory(String owner, YearMonth month, History saved, Long income, Long contributions,
                                  List<HistoricalEdit> categories) {
        YearMonth current = period.current();
        long revision = saved.figures().revision();
        var figures = submittedFigures(income, contributions, revision);
        if (!month.isBefore(current) || !claim(owner, month, current, revision, saved.planRevision(), figures, revision + 1)) {
            return false;
        }
        for (var row : categories) {
            int initialRevision = row.actualProvided() ? 1 : 0;
            saveCategory(owner, month, row.code(), row.plan(), row.actual(), true, initialRevision);
        }
        refreshPlan(owner, month, current);
        return true;
    }

    private static MonthBudget.Figures submittedFigures(Long income, Long contributions, long revision) {
        return new MonthBudget.Figures(income == null ? 0 : income, contributions == null ? 0 : contributions,
                revision, income != null, contributions != null);
    }

    private boolean claim(String owner, YearMonth month, YearMonth settingsMonth, long revision, long planRevision,
                          MonthBudget.Figures figures, long nextRevision) {
        int claimed = jdbc.sql(replaceMonthSql).param("owner", owner).param("period", month.toString())
                .param("income", figures.income()).param("contributions", figures.contributions())
                .param("revision", revision).param("nextRevision", nextRevision)
                .param("settingsPeriod", settingsMonth.toString()).param("planRevision", planRevision).update();
        if (claimed != 1) return false;
        jdbc.sql("""
                INSERT INTO month_figure_recording (owner, period, income_recorded, contributions_recorded)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (owner, period) DO UPDATE SET income_recorded = excluded.income_recorded,
                    contributions_recorded = excluded.contributions_recorded
                """).params(owner, month.toString(), figures.incomeRecorded(), figures.contributionsRecorded()).update();
        return true;
    }

    private void saveCategory(String owner, YearMonth month, String category, long plan, long actual,
                              boolean history, int initialRevision) {
        jdbc.sql("""
                INSERT INTO category_month (owner, period, category, plan, actual, revision) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner, period, category) DO UPDATE
                   SET plan = CASE WHEN ? THEN excluded.plan ELSE category_month.plan END,
                       revision = CASE WHEN category_month.actual <> excluded.actual THEN category_month.revision + 1
                                       WHEN excluded.actual = 0 THEN max(category_month.revision, excluded.revision)
                                       ELSE category_month.revision END,
                       actual = excluded.actual
                """).params(owner, month.toString(), category, plan, actual, initialRevision, history).update();
    }

    private void refreshPlan(String owner, YearMonth savedMonth, YearMonth current) {
        if (savedMonth.getYear() == current.getYear() - 1) {
            plans.recalculateFromHistory(owner, current);
        }
    }
}
