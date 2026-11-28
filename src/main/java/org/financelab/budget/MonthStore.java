package org.financelab.budget;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads and replaces one owner's committed monthly figures. */
@Repository
class MonthStore {
    record HistoricalCategory(String code, String name, long plan, long actual, boolean recorded) {}
    record HistoricalEdit(String code, long plan, long actual, boolean actualProvided) {}
    record History(MonthBudget.Figures figures, long planRevision, List<HistoricalCategory> categories) {
        public String percent(long amount) {
            return figures.income() == 0 ? "—" : PlanCalculator.displayedPercent(amount, figures.income()) + " %";
        }
    }

    record Cash(LocalDate date, long cardBalance, BigInteger opening, BigInteger balance) {}
    private record Reference(LocalDate date, long cardBalance, BigInteger legacyLaterMovement) {}

    @Transactional
    public void reconcile(String owner, LocalDate date, long cardBalance) {
        // Before an owner's first snapshot, past-month entries belong to their budget month.
        // Keep the day for same-month entries and preserve new movements after the snapshot.
        jdbc.sql("""
                UPDATE cash_movement
                   SET effective_date = date(period || '-01', '+1 month', '-1 day')
                 WHERE owner = :owner AND substr(effective_date, 1, 7) > period
                   AND NOT EXISTS (SELECT 1 FROM cash_reconciliation WHERE owner = :owner)
                """).param("owner", owner).update();
        // Older databases have only an aggregate for edits after the original reference.
        jdbc.sql("""
                INSERT INTO cash_movement (owner, period, effective_date, amount)
                SELECT owner, substr(reference_date, 1, 7), date(reference_date, '+1 day'), later_movement
                  FROM cash_reconciliation WHERE owner = ? AND later_movement <> '0'
                """).param(owner).update();
        jdbc.sql("""
                INSERT INTO cash_reconciliation (owner, reference_date, card_balance, later_movement)
                VALUES (?, ?, ?, '0')
                ON CONFLICT (owner) DO UPDATE SET reference_date = excluded.reference_date,
                    card_balance = excluded.card_balance, later_movement = '0'
                """).params(owner, date.toString(), cardBalance).update();
    }

    @Transactional(readOnly = true)
    public Cash cash(String owner, YearMonth month) {
        var reference = reference(owner).orElse(null);
        if (reference == null) return null;
        YearMonth referenceMonth = YearMonth.from(reference.date());
        if (month.isBefore(referenceMonth)) return null;
        BigInteger throughReferenceMonth = movement(owner, referenceMonth);
        BigInteger laterMovement = jdbc.sql("""
                SELECT amount FROM cash_movement WHERE owner = ? AND period <= ? AND effective_date > ?
                """).params(owner, referenceMonth.toString(), reference.date().toString())
                .query((rs, i) -> new BigInteger(rs.getString(1))).list().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger opening = BigInteger.valueOf(reference.cardBalance())
                .subtract(throughReferenceMonth).add(laterMovement).add(reference.legacyLaterMovement());
        BigInteger throughMonth = month.equals(referenceMonth) ? throughReferenceMonth : movement(owner, month);
        return new Cash(reference.date(), reference.cardBalance(), opening, opening.add(throughMonth));
    }

    LocalDate reconciliationDate(String owner) {
        return reference(owner).map(Reference::date).orElse(null);
    }

    private Optional<Reference> reference(String owner) {
        return jdbc.sql("SELECT reference_date, card_balance, later_movement FROM cash_reconciliation WHERE owner = ?")
                .param(owner).query((rs, i) -> new Reference(LocalDate.parse(rs.getString(1)), rs.getLong(2),
                        new BigInteger(rs.getString(3)))).optional();
    }

    private BigInteger movement(String owner, YearMonth through) {
        return jdbc.sql("""
                SELECT income, contributions FROM month_actuals WHERE owner = :owner AND period <= :period
                UNION ALL
                SELECT 0, actual FROM category_month WHERE owner = :owner AND period <= :period
                """).param("owner", owner).param("period", through.toString())
                .query((rs, i) -> net(rs.getLong(1), rs.getLong(2))).list().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger net(long income, long outflow) {
        return BigInteger.valueOf(income).subtract(BigInteger.valueOf(outflow));
    }

    private void trackMovement(String owner, YearMonth month, LocalDate correction, BigInteger change) {
        LocalDate referenceDate = reconciliationDate(owner);
        if (correction != null && (referenceDate == null || !correction.isBefore(referenceDate))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Дата сверки изменилась.");
        }
        if (change.signum() == 0) return;

        LocalDate effectiveDate = correction;
        if (effectiveDate == null) {
            LocalDate today = period.todayInMoscow();
            effectiveDate = today;
            // A save on the reference day follows the already recorded card snapshot.
            if (today.equals(referenceDate)) {
                effectiveDate = effectiveDate.plusDays(1);
            }
        }
        jdbc.sql("INSERT INTO cash_movement (owner, period, effective_date, amount) VALUES (?, ?, ?, ?)")
                .params(owner, month.toString(), effectiveDate.toString(), change.toString()).update();
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
                        rs.getLong("opening_buffer")))
                .list();
        return new MonthBudget(terms, figures(owner, month), categories);
    }

    @Transactional
    public YearReport readYear(String owner, int year, YearMonth current) {
        plans.refreshForYear(owner, current);
        YearMonth january = YearMonth.of(year, 1);
        YearMonth nextJanuary = january.plusYears(1);
        YearMonth cutoff = current.isBefore(nextJanuary) ? current : nextJanuary;
        int completedMonths = (int) Math.max(0, january.until(cutoff, ChronoUnit.MONTHS));

        var months = new ArrayList<YearReport.Month>(12);
        var categories = new LinkedHashMap<String, YearReport.Category>();
        var rates = new LinkedHashMap<String, Long>();
        var totals = YearReport.Actuals.ZERO;
        var completedTotals = YearReport.Actuals.ZERO;
        BigInteger investmentPlan = BigInteger.ZERO;
        // The five original catalogue entries predate settings. A first snapshot alone
        // does not establish their start, but a later addition or a user-created key does.
        var starts = new HashMap<String, YearMonth>();
        jdbc.sql("""
                SELECT category, min(since) AS since FROM planning_category
                 WHERE owner = :owner GROUP BY category
                HAVING category NOT IN ('housing', 'food', 'health', 'leisure', 'travel')
                    OR min(since) > (SELECT min(since) FROM planning_settings WHERE owner = :owner)
                """).param("owner", owner).query(rs -> {
            starts.put(rs.getString("category"), YearMonth.parse(rs.getString("since")));
        });
        for (int index = 0; index < 12; index++) {
            YearMonth month = january.plusMonths(index);
            MonthBudget budget = read(owner, month, current, false);
            if (index == 0 && budget.terms() == null) {
                // Monthly records before January supply the opening rates when settings are absent.
                jdbc.sql("""
                        SELECT category, name, plan FROM (
                            SELECT m.category, c.name, m.plan,
                                   row_number() OVER (PARTITION BY m.category ORDER BY m.period DESC) AS recency
                              FROM category_month m JOIN category c ON c.code = m.category
                             WHERE m.owner = ? AND m.period < ?
                        ) WHERE recency = 1
                        """).params(owner, january.toString()).query(rs -> {
                    String code = rs.getString("category");
                    rates.put(code, rs.getLong("plan"));
                    categories.put(code, YearReport.Category.empty(code, rs.getString("name")));
                });
            }
            YearReport.Actuals actuals = YearReport.Actuals.from(budget);
            months.add(new YearReport.Month(month, actuals));
            totals = totals.plus(actuals);
            boolean completed = index < completedMonths;
            if (completed) completedTotals = completedTotals.plus(actuals);
            if (budget.terms() != null) {
                investmentPlan = investmentPlan.add(BigInteger.valueOf(budget.allocation().investments()));
            }
            for (var row : budget.categories()) {
                var category = categories.computeIfAbsent(row.code(), code -> YearReport.Category.empty(code, row.name()));
                // Backfill incomplete history only when it predates any known category inception.
                YearMonth start = starts.get(row.code());
                if (rates.put(row.code(), row.plan()) == null && (start == null || month.isBefore(start))) {
                    category = category.withPlan(BigInteger.valueOf(row.plan()).multiply(BigInteger.valueOf(index)));
                }
                categories.put(row.code(), category.withMonth(row, completed));
            }
            if (budget.terms() == null) {
                // With monthly history alone, the last recorded rate applies until the next one appears.
                var observed = budget.categories().stream().map(MonthBudget.Category::code)
                        .collect(Collectors.toSet());
                rates.forEach((code, rate) -> {
                    if (!observed.contains(code)) {
                        categories.put(code, categories.get(code).withPlan(BigInteger.valueOf(rate)));
                    }
                });
            }
        }

        // Carry only recorded plans forward, including records from earlier years.
        jdbc.sql("""
                SELECT m.category, c.name, m.plan, m.actual
                  FROM category_month m JOIN category c ON c.code = m.category
                 WHERE m.owner = ? AND m.period < ?
                """).params(owner, cutoff.toString()).query(rs -> {
            String code = rs.getString("category");
            String name = rs.getString("name");
            var category = categories.computeIfAbsent(code, key -> YearReport.Category.empty(key, name));
            var balance = BigInteger.valueOf(rs.getLong("plan")).subtract(BigInteger.valueOf(rs.getLong("actual")));
            categories.put(code, category.withBalance(balance));
        });
        BigInteger spendingPlan = categories.values().stream().map(YearReport.Category::plan)
                .reduce(BigInteger.ZERO, BigInteger::add);
        return new YearReport(year, cutoff, completedMonths, List.copyOf(months), totals, completedTotals,
                spendingPlan, investmentPlan, List.copyOf(categories.values()));
    }

    @Transactional
    public History readHistory(String owner, YearMonth month) {
        YearMonth current = period.current();
        plans.refreshForYear(owner, current);
        var terms = plans.terms(owner, current);
        var categories = jdbc.sql(readHistorySql)
                .param("owner", owner).param("period", month.toString()).param("since", terms == null ? "" : terms.since())
                .query((rs, index) -> new HistoricalCategory(rs.getString("category"), rs.getString("name"),
                        rs.getLong("plan"), rs.getLong("actual"), rs.getBoolean("recorded"))).list();
        return new History(figures(owner, month), terms == null ? 0 : terms.revision(), categories);
    }

    private MonthBudget.Figures figures(String owner, YearMonth month) {
        return savedFigures(owner, month).orElse(new MonthBudget.Figures(0, 0, 0));
    }

    Optional<MonthBudget.Figures> savedFigures(String owner, YearMonth month) {
        return jdbc.sql("SELECT income, contributions, revision FROM month_actuals WHERE owner = ? AND period = ?")
                .params(owner, month.toString())
                .query((rs, i) -> new MonthBudget.Figures(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .optional();
    }

    @Transactional
    public boolean replace(String owner, YearMonth month, MonthBudget saved, long revision, MonthActuals actuals) {
        return replace(owner, month, saved, revision, actuals, null);
    }

    @Transactional
    public boolean replace(String owner, YearMonth month, MonthBudget saved, long revision, MonthActuals actuals,
                           LocalDate correction) {
        boolean changed = actuals.income() != saved.figures().income()
                || actuals.contributions() != saved.figures().contributions()
                || saved.categories().stream().anyMatch(row -> row.actual() != actuals.spending().get(row.code()));
        var figures = new MonthBudget.Figures(actuals.income(), actuals.contributions(), changed ? revision + 1 : revision);
        if (!claim(owner, month, month, revision, saved.planRevision(), figures)) return false;

        BigInteger change = net(actuals.income(), actuals.contributions())
                .subtract(net(saved.figures().income(), saved.figures().contributions()));
        for (var row : saved.categories()) {
            change = change.add(net(row.actual(), actuals.spending().get(row.code())));
            saveCategory(owner, month, row.code(), row.plan(), actuals.spending().get(row.code()), false, 0);
        }
        trackMovement(owner, month, correction, change);
        if (changed) refreshPlan(owner, month, period.current());
        return true;
    }

    @Transactional
    public boolean replaceHistory(String owner, YearMonth month, History saved, long income, long contributions,
                                  List<HistoricalEdit> categories, LocalDate correction) {
        YearMonth current = period.current();
        long revision = saved.figures().revision();
        var figures = new MonthBudget.Figures(income, contributions, revision + 1);
        if (!month.isBefore(current) || !claim(owner, month, current, revision, saved.planRevision(), figures)) return false;
        for (var row : categories) {
            int initialRevision = row.actualProvided() ? 1 : 0;
            saveCategory(owner, month, row.code(), row.plan(), row.actual(), true, initialRevision);
        }
        BigInteger change = net(income, contributions).subtract(net(saved.figures().income(), saved.figures().contributions()));
        change = change.add(saved.categories().stream().map(row -> BigInteger.valueOf(row.actual()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        change = change.subtract(categories.stream().map(row -> BigInteger.valueOf(row.actual()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        trackMovement(owner, month, correction, change);
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

    private void saveCategory(String owner, YearMonth month, String category, long plan, long actual,
                              boolean history, int initialRevision) {
        jdbc.sql("""
                INSERT INTO category_month (owner, period, category, plan, actual, revision) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner, period, category) DO UPDATE
                   SET plan = CASE WHEN ? THEN excluded.plan ELSE category_month.plan END,
                       revision = CASE WHEN category_month.actual = excluded.actual
                                       THEN category_month.revision ELSE category_month.revision + 1 END,
                       actual = excluded.actual
                """).params(owner, month.toString(), category, plan, actual, initialRevision, history).update();
    }

    private void refreshPlan(String owner, YearMonth savedMonth, YearMonth current) {
        if (savedMonth.getYear() == current.getYear() - 1) {
            plans.recalculateFromHistory(owner, current);
        }
    }
}
