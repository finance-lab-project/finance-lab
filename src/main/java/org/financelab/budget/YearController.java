package org.financelab.budget;

import java.security.Principal;
import java.time.YearMonth;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
class YearController {
    private final MonthStore store;
    private final BudgetPeriod period;

    YearController(MonthStore store, BudgetPeriod period) {
        this.store = store;
        this.period = period;
    }

    @GetMapping("/years")
    String select(@RequestParam int year) {
        return "redirect:/years/" + validYear(year);
    }

    @GetMapping("/years/{year}")
    String year(Principal owner, @PathVariable int year, Model model) {
        YearMonth current = period.current();
        YearReport report = store.readYear(owner.getName(), validYear(year), current);
        model.addAttribute("report", report);
        model.addAttribute("months", BudgetPeriod.MONTHS);
        model.addAttribute("monthLink", year == current.getYear() ? current : YearMonth.of(year, 1));
        model.addAttribute("bufferDate", BudgetPeriod.startDate(report.cutoff()));
        int count = report.completedMonths();
        if (count > 0) {
            model.addAttribute("averagePeriod", count == 1 ? BudgetPeriod.title(YearMonth.of(year, 1))
                    : "Январь — " + BudgetPeriod.MONTHS.get(count - 1) + " " + year);
        }
        return "year";
    }

    private static int validYear(int year) {
        if (year < 1 || year > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите год от 1 до 9999.");
        }
        return year;
    }
}
