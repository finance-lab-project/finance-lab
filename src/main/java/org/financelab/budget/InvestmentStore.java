package org.financelab.budget;

import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class InvestmentStore {
    enum AccountKind {
        INVESTMENT("Инвестиционный счёт"),
        DEPOSIT("Вклад"),
        SAVINGS("Накопительный счёт");

        private final String label;

        AccountKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    record Figures(long closingValue, long creditedIncome) {}
    record AccountMonth(long id, String name, AccountKind kind, @Nullable Figures figures, @Nullable Long previousValue) {}

    private final JdbcClient jdbc;

    InvestmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AccountMonth> read(String owner, YearMonth month) {
        return jdbc.sql("""
                SELECT a.id, a.name, k.kind, m.period, m.closing_value, m.credited_income,
                       prior.closing_value AS previous_value
                  FROM investment_account a
                  LEFT JOIN savings_product k ON k.account_id = a.id
                  LEFT JOIN investment_month m ON m.account_id = a.id AND m.period = ?
                  LEFT JOIN investment_month prior ON prior.account_id = a.id AND prior.period = ?
                 WHERE a.owner = ?
                 ORDER BY a.id
                """).params(month.toString(), month.minusMonths(1).toString(), owner)
                .query((rs, index) -> new AccountMonth(rs.getLong("id"), rs.getString("name"),
                        rs.getString("kind") == null ? AccountKind.INVESTMENT : AccountKind.valueOf(rs.getString("kind")),
                        rs.getString("period") == null ? null
                                : new Figures(rs.getLong("closing_value"), rs.getLong("credited_income")),
                        rs.getObject("previous_value") == null ? null : rs.getLong("previous_value")))
                .list();
    }

    boolean addAccount(String owner, String name) {
        return jdbc.sql("""
                INSERT INTO investment_account (owner, name) VALUES (?, ?)
                ON CONFLICT (owner, name) DO NOTHING
                """).params(owner, name).update() == 1;
    }

    @Transactional
    boolean addAccount(String owner, String name, AccountKind kind) {
        if (!addAccount(owner, name)) return false;
        if (kind != AccountKind.INVESTMENT) {
            jdbc.sql("""
                    INSERT INTO savings_product (account_id, kind)
                    SELECT id, ? FROM investment_account WHERE owner = ? AND name = ?
                    """).params(kind.name(), owner, name).update();
        }
        return true;
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
