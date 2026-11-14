package org.financelab.budget;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MonthStore {
    private static final String MARCH = "2027-03";
    private final JdbcClient jdbc;

    MonthStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    MonthBudget read(String owner) {
        var categories = jdbc.sql("""
                SELECT m.category, c.name, m.plan, m.actual, m.revision,
                       (SELECT coalesce(sum(h.plan - h.actual), 0)
                          FROM category_month h
                         WHERE h.owner = m.owner AND h.category = m.category
                           AND h.period < m.period) AS opening_buffer
                  FROM category_month m JOIN category c ON c.code = m.category
                 WHERE m.owner = :owner AND m.period = :period
                 ORDER BY c.display_order
                """)
                .param("owner", owner).param("period", MARCH)
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getLong("plan"), rs.getLong("actual"), rs.getLong("opening_buffer"),
                        rs.getLong("revision")))
                .list();
        return new MonthBudget(categories);
    }

    @Transactional
    public boolean replaceFood(String owner, long revision, long amount) {
        return jdbc.sql("""
                UPDATE category_month
                   SET actual = :amount,
                       revision = CASE WHEN actual = :amount THEN revision ELSE revision + 1 END
                 WHERE owner = :owner AND period = :period AND category = 'food' AND revision = :revision
                """)
                .param("amount", amount).param("owner", owner).param("period", MARCH).param("revision", revision)
                .update() == 1;
    }
}
