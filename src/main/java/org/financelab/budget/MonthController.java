package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class MonthController {
    static final String MARCH_PATH = "/months/2027-03";
    private final MonthStore store;
    private final Rubles rubles;

    MonthController(MonthStore store, Rubles rubles) {
        this.store = store;
        this.rubles = rubles;
    }

    @GetMapping("/")
    String home() {
        return "redirect:" + MARCH_PATH;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping(MARCH_PATH)
    String month(Principal owner, @RequestParam(defaultValue = "false") boolean edit, Model model) {
        MonthBudget month = store.read(owner.getName());
        model.addAttribute("month", month);
        model.addAttribute("editing", edit);
        model.addAttribute("draft", rubles.number(month.food().actual()));
        model.addAttribute("revision", month.food().revision());
        return "month";
    }

    @PostMapping(MARCH_PATH)
    String save(Principal owner, @RequestParam(defaultValue = "") String food, @RequestParam long revision,
                Model model, HttpServletResponse response, RedirectAttributes redirect) {
        long amount;
        try {
            amount = Rubles.parse(food);
        } catch (IllegalArgumentException invalidAmount) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("fieldError", invalidAmount.getMessage());
            return editAfterError(model, store.read(owner.getName()), food, revision);
        }
        if (!store.replaceFood(owner.getName(), revision, amount)) {
            MonthBudget current = store.read(owner.getName());
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("conflict", true);
            return editAfterError(model, current, food, current.food().revision());
        }
        redirect.addFlashAttribute("saved", true);
        return "redirect:" + MARCH_PATH;
    }

    private String editAfterError(Model model, MonthBudget month, String draft, long revision) {
        model.addAttribute("month", month);
        model.addAttribute("editing", true);
        model.addAttribute("draft", draft);
        model.addAttribute("revision", revision);
        return "month";
    }
}
