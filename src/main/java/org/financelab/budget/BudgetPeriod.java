package org.financelab.budget;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class BudgetPeriod {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("LLLL uuuu", RUSSIAN);
    private static final DateTimeFormatter START_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", RUSSIAN);
    static final List<String> MONTHS = Arrays.stream(Month.values())
            .map(month -> month.getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN)).toList();
    private final YearMonth fixed;

    BudgetPeriod(@Value("${budget.current-month:}") String fixed) {
        this.fixed = fixed.isBlank() ? null : YearMonth.parse(fixed);
    }

    YearMonth current() {
        return fixed == null ? YearMonth.now() : fixed;
    }

    YearMonth currentInMoscow() {
        return fixed == null ? YearMonth.now(ZoneId.of("Europe/Moscow")) : fixed;
    }

    LocalDate todayInMoscow() {
        return fixed == null ? LocalDate.now(ZoneId.of("Europe/Moscow")) : fixed.atEndOfMonth();
    }

    static String startDate(YearMonth month) {
        return month.atDay(1).format(START_DATE);
    }

    static String title(YearMonth month) {
        String title = month.format(TITLE);
        return title.substring(0, 1).toUpperCase(RUSSIAN) + title.substring(1);
    }
}
