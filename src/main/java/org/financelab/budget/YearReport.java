package org.financelab.budget;

import java.math.BigInteger;
import java.time.YearMonth;
import java.util.List;

record YearReport(int year, YearMonth cutoff, int completedMonths, List<Month> months,
                  Actuals totals, Actuals completedTotals, BigInteger spendingPlan, BigInteger investmentPlan,
                  List<Category> categories) {
    record Month(YearMonth period, Actuals actuals) {}

    record Actuals(BigInteger income, BigInteger spending, BigInteger contributions) {
        static final Actuals ZERO = new Actuals(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);

        static Actuals from(MonthBudget month) {
            return new Actuals(BigInteger.valueOf(month.figures().income()), month.totalSpending(),
                    BigInteger.valueOf(month.figures().contributions()));
        }

        Actuals plus(Actuals other) {
            return new Actuals(income.add(other.income), spending.add(other.spending),
                    contributions.add(other.contributions));
        }
    }

    record Category(String code, String name, BigInteger plan, BigInteger actual,
                    BigInteger completedActual, BigInteger buffer) {
        static Category empty(String code, String name) {
            return new Category(code, name, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        }

        Category withMonth(MonthBudget.Category month, boolean completed) {
            var amount = BigInteger.valueOf(month.actual());
            return new Category(code, month.name(), plan, actual.add(amount),
                    completed ? completedActual.add(amount) : completedActual, buffer);
        }

        Category withBalance(BigInteger balance) {
            return new Category(code, name, plan, actual, completedActual, buffer.add(balance));
        }

        Category withPlan(BigInteger amount) {
            return new Category(code, name, plan.add(amount), actual, completedActual, buffer);
        }
    }

    public BigInteger totalBuffer() {
        return categories.stream().map(Category::buffer).reduce(BigInteger.ZERO, BigInteger::add);
    }

    public BigInteger roundedAverage(BigInteger completedTotal) {
        return new Fraction(completedTotal, BigInteger.valueOf(completedMonths)).rounded();
    }
}
