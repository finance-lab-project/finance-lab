package org.financelab.budget;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import org.financelab.budget.PlanStore.Terms;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of the committed figures for one owner's month. Draft form values
 * do not participate in its totals.
 * @param terms planning settings effective in this month, if configured
 * @param figures saved income, investment contributions and version of the month's actuals
 * @param categories saved categories in display order
 */
public record MonthBudget(@Nullable Terms terms, Figures figures, List<Category> categories) {
    public record Figures(long income, long contributions, long revision) {}

    /**
     * Takes a snapshot so later edits to the source list cannot change the totals.
     * @param terms effective planning settings, if configured
     * @param figures saved monthly figures
     * @param categories saved category rows
     * @throws NullPointerException if the list or any row is null
     */
    public MonthBudget {
        categories = List.copyOf(categories);
    }

    public long planningIncome() {
        return terms == null ? 0 : terms.income();
    }

    public long planRevision() {
        return terms == null ? 0 : terms.revision();
    }

    public PlanCalculator.Totals allocation() {
        BigInteger needs = categories.stream().filter(row -> row.group().equals("essential"))
                .map(row -> BigInteger.valueOf(row.plan())).reduce(BigInteger.ZERO, BigInteger::add);
        return PlanCalculator.Totals.of(terms.income(), needs, terms.target());
    }

    public String percent(long amount) {
        return PlanCalculator.displayedPercent(amount, planningIncome());
    }

    /** {@return the sum of the monthly plans, in whole rubles} */
    public BigInteger totalPlan() {
        return categories.stream().map(row -> BigInteger.valueOf(row.plan()))
                .reduce(BigInteger.ZERO, (total, amount) -> total.add(amount));
    }

    /** {@return total saved spending across the categories, in whole rubles} */
    public BigInteger totalSpending() {
        return categories.stream().map(row -> BigInteger.valueOf(row.actual()))
                .reduce(BigInteger.ZERO, (total, amount) -> total.add(amount));
    }

    /**
     * The monthly plan and actual are independent of the balance carried into the month.
     * @param code stable category identifier
     * @param name display name
     * @param group essential or discretionary expense group
     * @param plan monthly allocation in whole rubles
     * @param actual saved cumulative spending in whole rubles
     * @param buffer balance carried from earlier months, in whole rubles
     */
    public record Category(String code, String name, String group, long plan, long actual, long buffer) {
        /**
         * Creates a saved category row.
         * @param code stable category identifier
         * @param name display name
         * @param group expense group
         * @param plan monthly allocation in rubles
         * @param actual saved cumulative spending in rubles
         * @param buffer opening balance in rubles
         * @throws NullPointerException if the code or name is null
         */
        public Category {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }

        /** {@return the monthly plan less saved spending, excluding the opening buffer} */
        public BigInteger remainder() {
            return BigInteger.valueOf(plan).subtract(BigInteger.valueOf(actual));
        }
    }
}
