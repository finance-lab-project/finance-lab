package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.Principal;
import java.util.List;
import org.financelab.budget.FireStore.Account;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/fire")
class FireController {
    private final FireStore store;
    private final BudgetPeriod period;

    FireController(FireStore store, BudgetPeriod period) {
        this.store = store;
        this.period = period;
    }

    @GetMapping
    String fire(Principal principal, Model model) {
        String owner = principal.getName();
        Long capitalGoal = store.goal(owner, "capital");
        var accounts = store.accounts(owner);
        var selected = accounts.stream().filter(Account::selected).toList();
        var missing = selected.stream().filter(account -> account.balance() == null).map(Account::name).toList();

        model.addAttribute("period", period.current());
        model.addAttribute("capitalGoal", capitalGoal);
        model.addAttribute("incomeGoal", store.goal(owner, "income"));
        model.addAttribute("accounts", accounts);
        model.addAttribute("emptySelection", selected.isEmpty());
        model.addAttribute("missingAccounts", missing);
        if (!selected.isEmpty() && selected.getFirst().month() != null) {
            model.addAttribute("observationMonth", selected.getFirst().month());
            model.addAttribute("observationTitle", BudgetPeriod.title(selected.getFirst().month()));
        }
        if (!selected.isEmpty() && missing.isEmpty()) {
            BigInteger capital = selected.stream().map(account -> BigInteger.valueOf(account.balance()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            model.addAttribute("capital", capital);
            if (capitalGoal != null) {
                BigDecimal percentage = new BigDecimal(capital).movePointRight(2)
                        .divide(BigDecimal.valueOf(capitalGoal), 2, RoundingMode.HALF_UP);
                model.addAttribute("percentage", percentage.stripTrailingZeros().toPlainString().replace('.', ','));
                model.addAttribute("remaining", BigInteger.valueOf(capitalGoal).subtract(capital).max(BigInteger.ZERO));
            }
        }
        return "fire";
    }

    @PostMapping("/goals/{kind:capital|income}")
    String saveGoal(Principal owner, @PathVariable String kind, @RequestParam(defaultValue = "") String amount,
                    Model model, HttpServletResponse response, RedirectAttributes redirect) {
        try {
            long goal = Rubles.parse(amount);
            if (goal <= 0) throw new IllegalArgumentException("Введите сумму больше нуля.");
            store.saveGoal(owner.getName(), kind, goal);
        } catch (IllegalArgumentException invalid) {
            model.addAttribute(kind + "Draft", amount);
            model.addAttribute(kind + "Error", invalid.getMessage());
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return fire(owner, model);
        }
        redirect.addFlashAttribute("saved", true);
        return "redirect:/fire";
    }

    @PostMapping("/accounts")
    String saveAccounts(Principal owner, @RequestParam(required = false) List<Long> accountIds,
                        Model model, HttpServletResponse response, RedirectAttributes redirect) {
        try {
            store.saveAccounts(owner.getName(), accountIds == null ? List.of() : accountIds);
        } catch (IllegalArgumentException invalid) {
            model.addAttribute("accountsError", invalid.getMessage());
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return fire(owner, model);
        }
        redirect.addFlashAttribute("saved", true);
        return "redirect:/fire";
    }
}
