package org.financelab.budget;

import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvestmentStore {
    record Figures(long closingValue, long creditedIncome) {}
    record AccountMonth(long id, String name, @Nullable Figures figures) {}

    private final JdbcClient jdbc;

    InvestmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AccountMonth> read(String owner, YearMonth month) {
        return jdbc.sql("""
                SELECT a.id, a.name, m.period, m.closing_value, m.credited_income
                  FROM investment_account a
                  LEFT JOIN investment_month m ON m.account_id = a.id AND m.period = ?
                 WHERE a.owner = ?
                 ORDER BY a.id
                """).params(month.toString(), owner)
                .query((rs, index) -> new AccountMonth(rs.getLong("id"), rs.getString("name"),
                        rs.getString("period") == null ? null
                                : new Figures(rs.getLong("closing_value"), rs.getLong("credited_income"))))
                .list();
    }

    boolean addAccount(String owner, String name) {
        return jdbc.sql("""
                INSERT INTO investment_account (owner, name) VALUES (?, ?)
                ON CONFLICT (owner, name) DO NOTHING
                """).params(owner, name).update() == 1;
    }

    boolean saveMonth(String owner, long accountId, YearMonth month, Figures figures) {
        return jdbc.sql("""
                INSERT INTO investment_month (account_id, period, closing_value, credited_income)
                SELECT id, ?, ?, ? FROM investment_account WHERE owner = ? AND id = ?
                ON CONFLICT (account_id, period) DO UPDATE SET
                    closing_value = excluded.closing_value, credited_income = excluded.credited_income
                """).params(month.toString(), figures.closingValue(), figures.creditedIncome(), owner, accountId)
                .update() == 1;
    }
}
