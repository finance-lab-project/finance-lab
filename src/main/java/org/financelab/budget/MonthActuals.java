package org.financelab.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

record MonthActuals(Long income, Long contributions, Map<String, Long> spending) {
    static Map<String, String> savedFields(MonthBudget month) {
        Map<String, String> fields = new HashMap<>();
        addFigureFields(fields, month.figures());
        for (var row : month.categories()) {
            fields.put("amount-" + row.code(), row.actualRecorded() ? Rubles.number(row.actual()) : "");
            fields.put("percent-" + row.code(), row.actualRecorded() ? month.percent(row.actual()) : "");
        }
        return fields;
    }

    // Only the last edited member of a linked pair is authoritative. A displayed,
    // rounded percentage must never change a ruble amount on an unrelated edit.
    static MonthActuals parse(MonthBudget month, Map<String, String> draft, Map<String, String> errors) {
        Long income = figure("income", month.figures().incomeRecorded(), draft, errors);
        Long contributions = figure("contributions", month.figures().contributionsRecorded(), draft, errors);
        Map<String, Long> spending = new HashMap<>();
        for (var row : month.categories()) {
            String amountKey = "amount-" + row.code();
            String percentKey = "percent-" + row.code();
            if ("percent".equals(draft.get("unit-" + row.code()))) {
                try {
                    long value = fromPercent(draft.get(percentKey), month.planningIncome());
                    spending.put(row.code(), value);
                    draft.put(amountKey, Rubles.number(value));
                } catch (IllegalArgumentException invalid) {
                    errors.put(percentKey, invalid.getMessage());
                    draft.put(amountKey, "");
                }
            } else {
                String input = draft.get(amountKey);
                if (!row.actualRecorded() && input != null && input.isBlank()) continue;
                long value = amount(amountKey, draft, errors);
                spending.put(row.code(), value);
                draft.put(percentKey, errors.containsKey(amountKey) ? "" : month.percent(value));
            }
        }
        return new MonthActuals(income, contributions, spending);
    }

    static void addFigureFields(Map<String, String> fields, MonthBudget.Figures figures) {
        fields.put("income", figures.incomeRecorded() ? Rubles.number(figures.income()) : "");
        fields.put("contributions", figures.contributionsRecorded() ? Rubles.number(figures.contributions()) : "");
    }

    static Long figure(String key, boolean recorded, Map<String, String> draft, Map<String, String> errors) {
        if (!recorded && draft.getOrDefault(key, "").isBlank()) return null;
        return amount(key, draft, errors);
    }

    static long amount(String key, Map<String, String> draft, Map<String, String> errors) {
        try {
            return Rubles.parse(draft.get(key));
        } catch (IllegalArgumentException invalid) {
            errors.put(key, invalid.getMessage());
            return 0;
        }
    }

    static LocalDate correctionDate(YearMonth month, LocalDate referenceDate, Map<String, String> draft,
                                    Map<String, String> errors) {
        String value = draft.getOrDefault("correctionDate", "");
        if (value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            if (referenceDate != null && YearMonth.from(date).equals(month) && date.isBefore(referenceDate)) return date;
        } catch (DateTimeParseException invalid) {
            // Report the same field error for malformed and out-of-range dates.
        }
        errors.put("correctionDate", "Укажите дату в этом месяце до даты сверки.");
        return null;
    }

    static long fromPercent(String text, long planningIncome) {
        if (planningIncome == 0) {
            throw new IllegalArgumentException("При нулевом доходе для плана введите сумму в рублях.");
        }
        String value = text == null ? "" : text.strip().replace('−', '-').replace(',', '.');
        if (!value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("Введите процент от дохода для плана, например 12,5.");
        }
        try {
            return new BigDecimal(value).movePointLeft(2).multiply(BigDecimal.valueOf(planningIncome))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("Сумма слишком велика. Уменьшите её и попробуйте снова.");
        }
    }
}
