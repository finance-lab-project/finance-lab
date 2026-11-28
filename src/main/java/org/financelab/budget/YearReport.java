package org.financelab.budget;

import java.math.BigInteger;
import java.time.YearMonth;
import java.util.List;

record YearReport(int year, YearMonth cutoff, YearMonth averageStart, List<Month> months,
                  Actuals totals, Average incomeAverage, Average spendingAverage, Average contributionsAverage,
                  BigInteger spendingPlan, BigInteger investmentPlan, List<Category> categories) {
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

    record Average(BigInteger total, int count) {
        static final Average EMPTY = new Average(BigInteger.ZERO, 0);

        Average withMonth(BigInteger actual) {
            return new Average(total.add(actual), count + 1);
        }

        public BigInteger rounded() {
            return new Fraction(total, BigInteger.valueOf(count)).rounded();
        }
    }

    record Category(String code, String name, BigInteger plan, BigInteger actual,
                    Average average, BigInteger buffer) {
        static Category empty(String code, String name) {
            return new Category(code, name, BigInteger.ZERO, BigInteger.ZERO, Average.EMPTY, BigInteger.ZERO);
        }

        Category withMonth(MonthBudget.Category month) {
            return new Category(code, month.name(), plan.add(BigInteger.valueOf(month.plan())),
                    actual.add(BigInteger.valueOf(month.actual())), average, buffer);
        }

        Category withRecordedActual(BigInteger amount) {
            return new Category(code, name, plan, actual, average.withMonth(amount), buffer);
        }

        Category withBalance(BigInteger balance) {
            return new Category(code, name, plan, actual, average, buffer.add(balance));
        }
    }

    public BigInteger totalBuffer() {
        return categories.stream().map(Category::buffer).reduce(BigInteger.ZERO, BigInteger::add);
    }
}
