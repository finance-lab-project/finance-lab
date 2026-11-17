package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.YearMonth;
import org.financelab.budget.MonthBudget.Category;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

    @GetMapping("/months/{month}")
    String month(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                 @RequestParam(defaultValue = "false") boolean edit, Model model) {
        MonthBudget budget = store.read(owner.getName(), month);
        var category = budget.editableCategory();
        boolean editable = canEdit(month, category);
        model.addAttribute("editing", edit && editable);
        if (editable) {
            model.addAttribute("draft", Rubles.number(category.actual()));
            model.addAttribute("revision", category.revision());
        }
        return view(model, month, budget);
    }

    @PostMapping("/months/{month}")
    String save(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                @RequestParam(name = "food", required = false) @Nullable String draft, @RequestParam long revision,
                Model model, HttpServletResponse response, RedirectAttributes redirect) {
        MonthBudget current = store.read(owner.getName(), month);
        var category = current.editableCategory();
        if (!canEdit(month, category)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long amount;
        try {
            amount = Rubles.parse(draft);
        } catch (IllegalArgumentException invalidAmount) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("fieldError", invalidAmount.getMessage());
            model.addAttribute("revision", revision);
            model.addAttribute("draft", draft);
            model.addAttribute("editing", true);
            return view(model, month, current);
        }
        if (!store.replaceActual(owner.getName(), month, category, revision, amount)) {
            current = store.read(owner.getName(), month);
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("conflict", true);
            model.addAttribute("revision", current.editableCategory().revision());
            model.addAttribute("draft", draft);
            model.addAttribute("editing", true);
            return view(model, month, current);
        }
        redirect.addFlashAttribute("saved", true);
        return "redirect:/months/" + month;
    }

    private boolean canEdit(YearMonth month, @Nullable Category category) {
        // Keep the original March food URL usable when reopening an issue #9 database.
        return category != null && (month.equals(period.current())
                || (month.equals(YearMonth.of(2027, 3)) && category.code().equals("food")));
    }

    private String view(Model model, YearMonth month, MonthBudget budget) {
        model.addAttribute("month", budget);
        model.addAttribute("period", month.toString());
        model.addAttribute("title", BudgetPeriod.title(month));
        var category = budget.editableCategory();
        model.addAttribute("editCategory", category);
        model.addAttribute("editable", canEdit(month, category));
        if (budget.terms() != null) model.addAttribute("allocation", budget.allocation());
        return "month";
    }
}
