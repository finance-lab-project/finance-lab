package org.financelab.budget;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanCalculatorTest {
    @Test
    void linkedFieldsUseTotalIncomeAndPreserveTheSplitAcrossCappedAndUncoveredMonths() {
        var plan = PlanCalculator.evaluate(new PlanDraft("100 000", "incomePercent", "40", List.of(
                row("essential", "annual", "156000"), row("essential", "incomePercent", "24"),
                row("essential", "amount", "3000"), row("discretionary", "amount", "12000"),
                row("discretionary", "incomePercent", "8"))));
        assertThat(plan.error()).isNull();
        assertThat(plan.needs()).isEqualTo("40000");
        assertThat(plan.investments()).isEqualTo("40000");
        assertThat(plan.pool()).isEqualTo("20000");
        assertThat(plan.fields().subList(3, 5)).extracting(PlanCalculator.Fields::incomePercent)
                .containsExactly("12", "8");
        assertThat(plan.fields().subList(3, 5)).extracting(PlanCalculator.Fields::poolPercent)
                .containsExactly("60", "40");

        var capped = withIncome(plan, "50000");
        assertThat(capped.targetAmount()).isEqualTo("20000");
        assertThat(capped.investments()).isEqualTo("10000");
        assertThat(capped.pool()).isEqualTo("0");
        var shortfall = withIncome(capped, "30000");
        assertThat(shortfall.shortfall()).isEqualTo("10000");
        assertThat(shortfall.investments()).isEqualTo("0");
        assertThat(shortfall.fields().subList(3, 5)).extracting(PlanCalculator.Fields::amount).containsExactly("0", "0");
        assertThat(withIncome(shortfall, "120000").fields().subList(3, 5))
                .extracting(PlanCalculator.Fields::amount).containsExactly("19200", "12800");
    }

    @Test
    void largestRemainderUsesExactSharesAndAdditionOrder() {
        var thirds = calculate("100", "0", row("discretionary", "poolPercent", "33,3"),
                row("discretionary", "poolPercent", "33.3"), row("discretionary", "poolPercent", "33.4"));
        assertThat(thirds.fields()).extracting(PlanCalculator.Fields::amount).containsExactly("33", "33", "34");
        var tie = calculate("7", "0", row("discretionary", "incomePercent", "50"),
                row("discretionary", "incomePercent", "50"));
        assertThat(tie.fields()).extracting(PlanCalculator.Fields::amount).containsExactly("4", "3");
        var repeating = calculate("3", "0", row("discretionary", "amount", "1"), row("discretionary", "amount", "2"));
        assertThat(repeating.draft().rows().getFirst().value()).isEqualTo("1/3");
        var large = withIncome(repeating, "9007199254740993");
        assertThat(large.error()).isNull();
        assertThat(large.fields()).extracting(PlanCalculator.Fields::amount)
                .containsExactly("3002399751580331", "6004799503160662");
    }

    @Test
    void roundsAnnualBaselinesAndInvestmentTargetsBeforeDistributingTheRemainingRubles() {
        var plan = calculate("101", "50", row("essential", "annual", "366"),
                row("discretionary", "poolPercent", "60"), row("discretionary", "poolPercent", "40"));
        assertThat(plan.needs()).isEqualTo("31");
        assertThat(plan.targetAmount()).isEqualTo("51");
        assertThat(plan.investments()).isEqualTo("51");
        assertThat(plan.pool()).isEqualTo("19");
        assertThat(plan.fields()).extracting(PlanCalculator.Fields::amount).containsExactly("31", "11", "8");
        assertThat(plan.draft().rows().getFirst().value()).isEqualTo("366");
    }

    @Test
    void invalidSharesShowTheDifferenceEvenAtZeroAndKopecksAreRejected() {
        var invalid = calculate("100000", "40", row("essential", "amount", "40000"),
                row("discretionary", "poolPercent", "60"), row("discretionary", "poolPercent", "50"));
        assertThat(invalid.error()).contains("110 %", "Превышение", "10 %", "2 000 ₽");
        assertThat(withIncome(invalid, "30000").error()).contains("110 %", "10 %", "0 ₽");
        assertThatThrownBy(() -> calculate("1234,50", "0")).hasMessageContaining("целых рублях");
        assertThatThrownBy(() -> calculate("100", "0", row("essential", "amount", "1,01")))
                .hasMessageContaining("целых рублях");
    }

    private static PlanDraft.Row row(String group, String unit, String value) {
        return new PlanDraft.Row("", "Категория", group, unit, value);
    }

    private static PlanCalculator.Preview withIncome(PlanCalculator.Preview plan, String income) {
        var draft = plan.draft();
        return PlanCalculator.evaluate(new PlanDraft(income, draft.targetUnit(), draft.targetValue(), draft.rows()));
    }

    private static PlanCalculator.Preview calculate(String income, String target, PlanDraft.Row... rows) {
        return PlanCalculator.evaluate(new PlanDraft(income, "incomePercent", target, List.of(rows)));
    }
}
