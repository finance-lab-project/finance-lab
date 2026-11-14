package org.financelab.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads saved category balances and replaces the signed-in owner's cumulative food spending. */
@Repository
class MonthStore {
    private static final String MARCH = "2027-03";
    private final JdbcClient jdbc;
    private final String readMonthSql;
    private final String replaceFoodSql;

    MonthStore(JdbcClient jdbc) throws IOException {
        this.jdbc = jdbc;
        this.readMonthSql = new ClassPathResource("sql/queries/read-month.sql").getContentAsString(StandardCharsets.UTF_8);
        this.replaceFoodSql = new ClassPathResource("sql/queries/replace-food.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Loads the saved March snapshot with each category's carry from earlier recorded months.
     * @param owner signed-in budget owner's name
     * @return categories in display order
     */
    MonthBudget read(String owner) {
        Objects.requireNonNull(owner, "owner");
        var categories = jdbc.sql(readMonthSql)
                .param("owner", owner).param("period", MARCH)
                .query((rs, index) -> new MonthBudget.Category(rs.getString("category"), rs.getString("name"),
                        rs.getLong("plan"), rs.getLong("actual"), rs.getLong("opening_buffer"),
                        rs.getLong("revision")))
                .list();
        return new MonthBudget(categories);
    }

    /**
     * Replaces food spending if the saved revision still matches the form. Repeating the
     * saved amount at the current revision succeeds and preserves that revision.
     * @param owner signed-in budget owner's name
     * @param revision revision displayed when the owner opened the form
     * @param amount cumulative spending in whole rubles
     * @return whether the owner's food row matched the submitted revision
     */
    @Transactional
    public boolean replaceFood(String owner, long revision, long amount) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.sql(replaceFoodSql)
                .param("amount", amount).param("owner", owner).param("period", MARCH).param("revision", revision)
                .update() == 1;
    }
}
