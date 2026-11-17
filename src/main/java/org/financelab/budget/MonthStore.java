package org.financelab.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads saved category balances and replaces one cumulative category amount. */
@Repository
class MonthStore {
    private final PlanStore plans;
    private final BudgetPeriod period;
    private final JdbcClient jdbc;
    private final String readMonthSql;
    private final String replaceActualSql;

    MonthStore(JdbcClient jdbc, PlanStore plans, BudgetPeriod period) throws IOException {
        this.plans = plans;
        this.period = period;
        this.jdbc = jdbc;
        this.readMonthSql = new ClassPathResource("sql/queries/read-month.sql").getContentAsString(StandardCharsets.UTF_8);
        this.replaceActualSql = new ClassPathResource("sql/queries/replace-actual.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public MonthBudget read(String owner, YearMonth month) {
        var terms = plans.terms(owner, month);
        YearMonth current = period.current();
        var categories = jdbc.sql(readMonthSql)
                .param("owner", owner).param("period", month.toString())
                .param("since", terms == null ? "" : terms.since())
                .param("cutoff", (month.isBefore(current) ? month : current).toString())
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getString("kind"), rs.getLong("plan"), rs.getLong("actual"),
                        rs.getLong("opening_buffer"), rs.getLong("revision")))
                .list();
        return new MonthBudget(terms, categories);
    }

    /**
     * Replaces category spending if the saved revision still matches the form. Repeating the
     * saved amount at the current revision succeeds and preserves that revision.
     * @param owner signed-in budget owner's name
     * @param month edited month
     * @param category the owner's editable category
     * @param revision revision displayed when the owner opened the form
     * @param amount cumulative spending in whole rubles
     * @return whether the owner's category row matched the submitted revision
     */
    @Transactional
    public boolean replaceActual(String owner, YearMonth month, MonthBudget.Category category, long revision, long amount) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.sql(replaceActualSql)
                .param("owner", owner).param("period", month.toString()).param("category", category.code())
                .param("plan", category.plan()).param("amount", amount).param("revision", revision)
                .update() == 1;
    }
}
