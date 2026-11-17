package org.financelab.budget;

import java.util.List;

/** Each linked row has one authoritative input. Displayed, rounded values are never posted as exact shares. */
record PlanDraft(String income, String targetUnit, String targetValue, List<Row> rows) {
    PlanDraft {
        if (targetUnit == null || rows == null || rows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Проверьте поля настроек плана.");
        }
        rows = List.copyOf(rows);
    }

    static PlanDraft empty() {
        return new PlanDraft("0", "fraction", "0/1", List.of());
    }

    record Row(String code, String name, String group, String unit, String value) {
        Row {
            if (code == null || name == null || group == null || unit == null) {
                throw new IllegalArgumentException("Проверьте поля категории.");
            }
        }

        Row withValue(String unit, String value) {
            return new Row(code, name, group, unit, value);
        }
    }
}
