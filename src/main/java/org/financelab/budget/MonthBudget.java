package org.financelab.budget;

import java.math.BigInteger;
import java.util.List;

public record MonthBudget(List<Category> categories) {
    public Category food() {
        return categories.stream().filter(Category::isFood).findFirst().orElseThrow();
    }

    public BigInteger totalPlan() {
        return categories.stream().map(row -> BigInteger.valueOf(row.plan()))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    public BigInteger totalSpending() {
        return categories.stream().map(row -> BigInteger.valueOf(row.actual()))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    public record Category(String code, String name, long plan, long actual, long buffer, long revision) {
        public boolean isFood() {
            return code.equals("food");
        }

        public BigInteger remainder() {
            return BigInteger.valueOf(plan).subtract(BigInteger.valueOf(actual));
        }
    }
}
