package org.financelab.budget;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * A snapshot of the committed figures for one owner's month. Draft form values
 * do not participate in its totals.
 * @param categories saved categories in display order
 */
public record MonthBudget(List<Category> categories) {
    /**
     * Takes a snapshot so later edits to the source list cannot change the totals.
     * @param categories saved category rows
     * @throws NullPointerException if the list or any row is null
     */
    public MonthBudget {
        categories = List.copyOf(categories);
    }

    /**
     * {@return the food category edited by the March form}
     * @throws java.util.NoSuchElementException if the snapshot has no food category
     */
    public Category food() {
        return categories.stream().filter(Category::isFood).findFirst().orElseThrow();
    }

    /** {@return the sum of the monthly plans, in whole rubles} */
    public BigInteger totalPlan() {
        return categories.stream().map(row -> BigInteger.valueOf(row.plan()))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** {@return total saved spending across the categories, in whole rubles} */
    public BigInteger totalSpending() {
        return categories.stream().map(row -> BigInteger.valueOf(row.actual()))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * The monthly plan and actual are independent of the balance carried into the month.
     * @param code stable category identifier
     * @param name display name
     * @param plan monthly allocation in whole rubles
     * @param actual saved cumulative spending in whole rubles
     * @param buffer balance carried from earlier months, in whole rubles
     * @param revision version of the saved category amount used to detect stale edits
     */
    public record Category(String code, String name, long plan, long actual, long buffer, long revision) {
        /**
         * Creates a saved category row.
         * @param code stable category identifier
         * @param name display name
         * @param plan monthly allocation in rubles
         * @param actual saved cumulative spending in rubles
         * @param buffer opening balance in rubles
         * @param revision version of the saved amount
         * @throws NullPointerException if the code or name is null
         */
        public Category {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }

        /** {@return whether this row represents the editable food category} */
        public boolean isFood() {
            return code.equals("food");
        }

        /** {@return the monthly plan less saved spending, excluding the opening buffer} */
        public BigInteger remainder() {
            return BigInteger.valueOf(plan).subtract(BigInteger.valueOf(actual));
        }
    }
}
