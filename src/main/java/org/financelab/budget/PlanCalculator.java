package org.financelab.budget;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PlanCalculator {
    private PlanCalculator() {}

    record Totals(long income, BigInteger needs, long target, long investments, long pool, BigInteger shortfall) {
        static Totals of(long income, BigInteger needs, Fraction targetShare) {
            long target = targetShare.times(Fraction.of(income, 1)).rounded().longValueExact();
            BigInteger available = BigInteger.valueOf(income).subtract(needs);
            long covered = available.max(BigInteger.ZERO).longValueExact();
            long investments = Math.min(target, covered);
            return new Totals(income, needs, target, investments, covered - investments,
                    available.negate().max(BigInteger.ZERO));
        }
    }

    // Amounts sent to the browser are strings, including values beyond JavaScript's safe integer range.
    record Fields(String amount, String incomePercent, String poolPercent, String annual) {}
    record Preview(PlanDraft draft, List<Fields> fields, String targetAmount, String targetPercent,
                   String needs, String investments, String pool, String shortfall, String error) {}

    static Preview evaluate(PlanDraft input) {
        long income = amount(input.income());
        Fraction target = switch (input.targetUnit()) {
            case "fraction" -> Fraction.read(input.targetValue());
            case "incomePercent" -> Fraction.percent(input.targetValue());
            case "amount" -> partOfIncome(amount(input.targetValue()), income);
            default -> throw new IllegalArgumentException("Укажите инвестиционную цель.");
        };
        if (target.compareTo(Fraction.ONE) > 0) {
            throw new IllegalArgumentException("Инвестиционная цель должна быть от 0 до 100 % дохода.");
        }

        List<PlanDraft.Row> canonical = new ArrayList<>(input.rows());
        List<Fields> fields = new ArrayList<>(java.util.Collections.nCopies(canonical.size(), null));
        BigInteger needs = BigInteger.ZERO;
        for (int i = 0; i < canonical.size(); i++) {
            PlanDraft.Row row = canonical.get(i);
            if ("discretionary".equals(row.group())) continue;
            if (!"essential".equals(row.group())) throw new IllegalArgumentException("Выберите группу категории.");
            boolean annual = "annual".equals(row.unit());
            long annualTotal = annual ? amount(row.value()) : 0;
            long monthly = switch (row.unit()) {
                case "amount" -> amount(row.value());
                case "annual" -> Fraction.of(annualTotal, 12).rounded().longValueExact();
                case "incomePercent" -> asRubles(fromIncomePercent(row.value(), income));
                default -> throw new IllegalArgumentException("Укажите план необходимых расходов.");
            };
            String year = annual ? Long.toString(annualTotal) : "";
            canonical.set(i, row.withValue(annual ? "annual" : "amount", annual ? year : Long.toString(monthly)));
            fields.set(i, new Fields(Long.toString(monthly), displayedPercent(monthly, income), "", year));
            needs = needs.add(BigInteger.valueOf(monthly));
        }

        Totals totals = Totals.of(income, needs, target);
        long pool = totals.pool();
        record Quota(int index, Fraction share, Fraction amount) {}
        List<Quota> quotas = new ArrayList<>();
        Fraction sum = Fraction.ZERO;
        BigInteger whole = BigInteger.ZERO;
        for (int i = 0; i < canonical.size(); i++) {
            PlanDraft.Row row = canonical.get(i);
            if (!"discretionary".equals(row.group())) continue;
            Fraction share = poolShare(row, income, pool);
            Fraction quota = share.times(Fraction.of(pool, 1));
            quotas.add(new Quota(i, share, quota));
            whole = whole.add(quota.floor());
            sum = sum.plus(share);
            canonical.set(i, row.withValue("fraction", share.stored()));
        }

        String error = null;
        int extras = 0;
        if (sum.equals(Fraction.ONE)) {
            extras = BigInteger.valueOf(pool).subtract(whole).intValueExact();
        } else if (!quotas.isEmpty() || pool > 0) {
            Fraction difference = sum.minusOne().absolute();
            String rubles = Rubles.number(difference.times(Fraction.of(pool, 1)).rounded());
            String direction = sum.compareTo(Fraction.ONE) > 0 ? "Превышение" : "Не распределено";
            error = "Доли свободного бюджета составляют " + sum.asPercent() + " % из 100 %. "
                    + direction + " — " + difference.asPercent() + " % свободного бюджета (" + rubles + " ₽).";
        }
        // The input is in immutable addition order, including new rows appended in this draft.
        quotas.sort(Comparator.comparing((Quota q) -> q.amount().remainder()).reversed()
                .thenComparingInt(Quota::index));
        for (Quota quota : quotas) {
            BigInteger plan = quota.amount().floor();
            if (extras > 0) {
                plan = plan.add(BigInteger.ONE);
                extras--;
            }
            String percent = income == 0 ? "" : quota.share().times(Fraction.of(pool, income)).asPercent();
            fields.set(quota.index(), new Fields(plan.toString(), percent, quota.share().asPercent(), ""));
        }
        return new Preview(new PlanDraft(Long.toString(income), "fraction", target.stored(), List.copyOf(canonical)),
                fields, Long.toString(totals.target()), target.asPercent(), needs.toString(),
                Long.toString(totals.investments()), Long.toString(pool), totals.shortfall().toString(), error);
    }

    private static Fraction poolShare(PlanDraft.Row row, long income, long pool) {
        return switch (row.unit()) {
            case "fraction" -> Fraction.read(row.value());
            case "poolPercent" -> Fraction.percent(row.value());
            case "amount", "incomePercent" -> {
                if (pool == 0) throw new IllegalArgumentException("При нулевом остатке задайте долю свободного бюджета.");
                yield "amount".equals(row.unit()) ? Fraction.of(amount(row.value()), pool)
                        : fromIncomePercent(row.value(), income).times(Fraction.of(1, pool));
            }
            default -> throw new IllegalArgumentException("Укажите долю свободной категории.");
        };
    }

    static String displayedPercent(long amount, long income) {
        return income == 0 ? "" : Fraction.of(amount, income).asPercent();
    }

    private static Fraction fromIncomePercent(String value, long income) {
        if (income == 0) throw new IllegalArgumentException("Для доли от дохода укажите доход больше нуля.");
        return Fraction.percent(value).times(Fraction.of(income, 1));
    }

    private static Fraction partOfIncome(long amount, long income) {
        if (income == 0) throw new IllegalArgumentException("При нулевом доходе задайте инвестиционную цель в процентах.");
        return Fraction.of(amount, income);
    }

    private static long asRubles(Fraction amount) {
        try {
            return amount.rounded().longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("Сумма слишком велика. Уменьшите её и попробуйте снова.");
        }
    }

    private static long amount(String text) {
        long amount = Rubles.parse(text);
        if (amount < 0) throw new IllegalArgumentException("Плановая сумма не может быть отрицательной.");
        return amount;
    }
}
