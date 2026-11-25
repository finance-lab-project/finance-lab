package org.financelab.budget;

import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class FireStore {
    record Account(long id, String name, boolean selected, @Nullable YearMonth month, @Nullable Long balance) {}

    private final JdbcClient jdbc;

    FireStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Long goal(String owner, String kind) {
        return jdbc.sql("SELECT amount FROM fire_goal WHERE owner = ? AND kind = ?")
                .params(owner, kind).query(Long.class).optional().orElse(null);
    }

    void saveGoal(String owner, String kind, long amount) {
        jdbc.sql("""
                INSERT INTO fire_goal (owner, kind, amount) VALUES (?, ?, ?)
                ON CONFLICT (owner, kind) DO UPDATE SET amount = excluded.amount
                """).params(owner, kind, amount).update();
    }

    List<Account> accounts(String owner) {
        return jdbc.sql("""
                WITH selected AS (
                    SELECT a.id FROM investment_account a JOIN fire_account f ON f.account_id = a.id
                     WHERE a.owner = :owner
                ), observation AS (
                    SELECT MAX(m.period) AS period FROM investment_month m JOIN selected s ON s.id = m.account_id
                )
                SELECT a.id, a.name, s.id IS NOT NULL AS selected, o.period, m.closing_value
                  FROM investment_account a CROSS JOIN observation o
                  LEFT JOIN selected s ON s.id = a.id
                  LEFT JOIN investment_month m ON m.account_id = s.id AND m.period = o.period
                 WHERE a.owner = :owner
                 ORDER BY a.id
                """).param("owner", owner).query((rs, index) -> {
                    String month = rs.getString("period");
                    Long balance = rs.getObject("closing_value") == null ? null : rs.getLong("closing_value");
                    return new Account(rs.getLong("id"), rs.getString("name"), rs.getBoolean("selected"),
                            month == null ? null : YearMonth.parse(month), balance);
                }).list();
    }

    @Transactional
    void saveAccounts(String owner, List<Long> accountIds) {
        jdbc.sql("""
                DELETE FROM fire_account WHERE account_id IN (SELECT id FROM investment_account WHERE owner = ?)
                """).param(owner).update();
        for (long id : accountIds.stream().distinct().toList()) {
            int inserted = jdbc.sql("""
                    INSERT INTO fire_account (account_id) SELECT id FROM investment_account WHERE owner = ? AND id = ?
                    """).params(owner, id).update();
            if (inserted == 0) throw new IllegalArgumentException("Выберите счета из списка.");
        }
    }
}
