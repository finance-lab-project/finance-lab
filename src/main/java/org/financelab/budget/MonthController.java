package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class MonthController {
    private final MonthStore store;
    private final BudgetPeriod period;

    MonthController(MonthStore store, BudgetPeriod period) {
        this.store = store;
        this.period = period;
    }

    @GetMapping("/")
    String home() {
        return "redirect:/months/" + period.current();
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/privacy")
    String privacy() {
        return "privacy";
    }

    @GetMapping("/months")
    String select(@RequestParam int year, @RequestParam int month) {
        if (year < 1 || year > 9999 || month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите месяц и год.");
        }
        return "redirect:/months/" + YearMonth.of(year, month);
    }

    @GetMapping("/months/{month}")
    String month(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                 @RequestParam(defaultValue = "false") boolean edit, Model model) {
        return view(model, owner.getName(), month, store.read(owner.getName(), month), edit);
    }

    @PostMapping("/months/{month}/reconcile")
    String reconcile(Principal principal, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                     @RequestParam Map<String, String> fields, Model model, HttpServletResponse response) {
        String owner = principal.getName();
        var errors = new HashMap<String, String>();
        long balance = MonthActuals.amount("cardBalance", fields, errors);
        LocalDate date = null;
        try {
            date = LocalDate.parse(fields.getOrDefault("referenceDate", ""));
            if (date.getYear() < 1 || !month.equals(YearMonth.from(date)) || date.isAfter(period.todayInMoscow())) {
                errors.put("referenceDate", "Укажите прошедшую или сегодняшнюю дату в выбранном месяце.");
            }
        } catch (DateTimeParseException invalid) {
            errors.put("referenceDate", "Укажите дату сверки.");
        }
        if (!errors.isEmpty()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("reconciliationErrors", errors);
            model.addAttribute("reconciliationDraft", fields);
            return view(model, owner, month, store.read(owner, month), false);
        }
        store.reconcile(owner, date, balance);
        return "redirect:/months/" + month;
    }

    @PostMapping("/months/linked-value")
    ResponseEntity<String> linkedValue(@RequestParam String unit, @RequestParam String value,
                                       @RequestParam long planningIncome) {
        try {
            String linked = switch (unit) {
                case "amount" -> PlanCalculator.displayedPercent(Rubles.parse(value), planningIncome);
                case "percent" -> Rubles.number(MonthActuals.fromPercent(value, planningIncome));
                default -> throw new IllegalArgumentException("Выберите рубли или проценты.");
            };
            return ResponseEntity.ok(linked);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.unprocessableContent().body(invalid.getMessage());
        }
    }

    @PostMapping("/months/{month}")
    String save(Principal principal, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                @RequestParam long revision, @RequestParam long planRevision, @RequestParam Map<String, String> fields,
                Model model, HttpServletResponse response, RedirectAttributes redirect) {
        String owner = principal.getName();
        MonthBudget current = store.read(owner, month);
        var draft = new HashMap<>(fields);
        var errors = new HashMap<String, String>();
        if (revision == current.figures().revision() && planRevision == current.planRevision()) {
            MonthActuals actuals = MonthActuals.parse(current, draft, errors);
            var correction = MonthActuals.correctionDate(month, store.reconciliationDate(owner), draft, errors);
            if (!errors.isEmpty()) {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                model.addAttribute("revision", revision);
                model.addAttribute("draft", draft);
                model.addAttribute("errors", errors);
                return view(model, owner, month, current, true);
            }
            if (store.replace(owner, month, current, revision, actuals, correction)) {
                redirect.addFlashAttribute("saved", true);
                return "redirect:/months/" + month;
            }
            current = store.read(owner, month);
        }

        // Keep the submitted inputs for review and include any categories added on the other device.
        MonthActuals.savedFields(current).forEach(draft::putIfAbsent);
        MonthActuals.parse(current, draft, errors);
        // An invalid typed value still needs correction before the owner can accept the new revision.
        if (errors.keySet().stream().anyMatch(key -> !fields.getOrDefault(key, "").isBlank())) {
            model.addAttribute("revision", revision);
        }
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("conflict", true);
        model.addAttribute("draft", draft);
        model.addAttribute("errors", errors);
        return view(model, owner, month, current, true);
    }

    private String view(Model model, String owner, YearMonth month, MonthBudget budget, boolean editing) {
        var cash = store.cash(owner, month);
        model.addAttribute("cash", cash);
        var today = period.todayInMoscow();
        if (!month.isAfter(YearMonth.from(today))) {
            model.addAttribute("reconciliationMax", month.equals(YearMonth.from(today)) ? today : month.atEndOfMonth());
            var defaults = cash != null && month.equals(YearMonth.from(cash.date()))
                    ? Map.of("referenceDate", cash.date().toString(), "cardBalance", Rubles.number(cash.cardBalance()))
                    : Map.of();
            model.mergeAttributes(Map.of("reconciliationDraft", defaults, "reconciliationErrors", Map.of()));
        }
        model.addAttribute("editing", editing);
        if (editing) {
            model.addAttribute("cashReferenceDate", store.reconciliationDate(owner));
            var original = MonthActuals.savedFields(budget);
            model.addAttribute("original", original);
            model.mergeAttributes(Map.of("draft", original, "errors", Map.of(), "revision", budget.figures().revision()));
        }
        model.addAttribute("month", budget);
        model.addAttribute("period", month);
        YearMonth current = period.currentInMoscow();
        model.addAttribute("historyPeriod", month.isBefore(current) ? month : current.minusMonths(1));
        model.addAttribute("months", BudgetPeriod.MONTHS);
        model.addAttribute("title", BudgetPeriod.title(month));
        if (budget.terms() != null) model.addAttribute("allocation", budget.allocation());
        return "month";
    }
}
