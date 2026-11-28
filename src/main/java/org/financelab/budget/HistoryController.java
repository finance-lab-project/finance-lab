package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.financelab.budget.MonthStore.HistoricalEdit;
import org.financelab.budget.MonthStore.History;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/history")
class HistoryController {
    private final MonthStore store;
    private final BudgetPeriod period;

    HistoryController(MonthStore store, BudgetPeriod period) {
        this.store = store;
        this.period = period;
    }

    @GetMapping
    String select(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                  Principal owner, Model model, HttpServletResponse response) {
        YearMonth latest = period.currentInMoscow().minusMonths(1);
        if (year == null && month == null) return "redirect:/history/" + latest;
        if (year != null && month != null && year >= 1 && year <= latest.getYear() && month >= 1 && month <= 12) {
            YearMonth selected = YearMonth.of(year, month);
            if (!selected.isAfter(latest)) return "redirect:/history/" + selected;
        }
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("periodError", true);
        return view(model, owner.getName(), latest, store.readHistory(owner.getName(), latest));
    }

    @GetMapping("/{month}")
    String history(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month, Model model) {
        requirePast(month);
        return view(model, owner.getName(), month, store.readHistory(owner.getName(), month));
    }

    @PostMapping("/{month}")
    String save(Principal principal, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                @RequestParam long revision, @RequestParam long planRevision, @RequestParam Map<String, String> fields,
                Model model, HttpServletResponse response, RedirectAttributes redirect) {
        requirePast(month);
        String owner = principal.getName();
        History saved = store.readHistory(owner, month);
        var draft = new HashMap<>(fields);
        var errors = new HashMap<String, String>();
        if (revision == saved.figures().revision() && planRevision == saved.planRevision()) {
            Long income = MonthActuals.figure("income", saved.figures().incomeRecorded(), draft, errors);
            Long contributions = MonthActuals.figure("contributions", saved.figures().contributionsRecorded(), draft, errors);
            List<HistoricalEdit> categories = parseCategories(saved, draft, errors);
            var correction = MonthActuals.correctionDate(month, store.reconciliationDate(owner), draft, errors);
            if (!errors.isEmpty()) {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                model.addAttribute("draft", draft);
                model.addAttribute("errors", errors);
                return view(model, owner, month, saved);
            }
            if (store.replaceHistory(owner, month, saved, income, contributions, categories, correction)) {
                redirect.addFlashAttribute("saved", true);
                return "redirect:/history/" + month;
            }
            saved = store.readHistory(owner, month);
        }

        savedFields(saved).forEach(draft::putIfAbsent);
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("draft", draft);
        model.addAttribute("conflict", true);
        return view(model, owner, month, saved);
    }

    private void requirePast(YearMonth month) {
        if (month.getYear() < 1 || !month.isBefore(period.currentInMoscow())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите завершённый месяц.");
        }
    }

    private String view(Model model, String owner, YearMonth month, History saved) {
        model.addAttribute("cashReferenceDate", store.reconciliationDate(owner));
        var original = savedFields(saved);
        model.addAttribute("original", original);
        model.mergeAttributes(Map.of("draft", original, "errors", Map.of()));
        model.addAttribute("history", saved);
        model.addAttribute("period", month);
        model.addAttribute("current", period.currentInMoscow());
        model.addAttribute("months", BudgetPeriod.MONTHS);
        return "history";
    }

    private static Map<String, String> savedFields(History saved) {
        Map<String, String> fields = new HashMap<>();
        MonthActuals.addFigureFields(fields, saved.figures());
        for (var row : saved.categories()) {
            fields.put("plan-" + row.code(), row.explicitPlan() ? Rubles.number(row.plan()) : "");
            fields.put("amount-" + row.code(), row.actualRecorded() ? Rubles.number(row.actual()) : "");
        }
        return fields;
    }

    private static List<HistoricalEdit> parseCategories(History saved, Map<String, String> draft,
                                                        Map<String, String> errors) {
        List<HistoricalEdit> categories = new ArrayList<>();
        for (var row : saved.categories()) {
            String planKey = "plan-" + row.code();
            String actualKey = "amount-" + row.code();
            boolean planMissing = draft.getOrDefault(planKey, "").isBlank();
            boolean actualMissing = draft.getOrDefault(actualKey, "").isBlank();
            if (!row.recorded() && planMissing && actualMissing) continue;

            long plan = planMissing ? row.effectivePlan() : MonthActuals.amount(planKey, draft, errors);
            long actual = !row.actualRecorded() && actualMissing ? 0 : MonthActuals.amount(actualKey, draft, errors);
            if (plan < 0) errors.put(planKey, "Плановая сумма не может быть отрицательной.");
            categories.add(new HistoricalEdit(row.code(), plan, actual, !actualMissing, !planMissing));
        }
        return categories;
    }
}
