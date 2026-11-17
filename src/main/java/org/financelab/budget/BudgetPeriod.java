package org.financelab.budget;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class BudgetPeriod {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("LLLL uuuu", RUSSIAN);
    private final YearMonth fixed;

    BudgetPeriod(@Value("${budget.current-month:}") String fixed) {
        this.fixed = fixed.isBlank() ? null : YearMonth.parse(fixed);
    }

    YearMonth current() {
        return fixed == null ? YearMonth.now() : fixed;
    }

    static String title(YearMonth month) {
        String title = month.format(TITLE);
        return title.substring(0, 1).toUpperCase(RUSSIAN) + title.substring(1);
    }
}
