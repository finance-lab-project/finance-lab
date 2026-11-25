package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.financelab.budget.FireStore.Account;
import org.financelab.budget.FireStore.AccountIncome;
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
    record IncomeGap(YearMonth month, String title, List<String> accounts) {}

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
        Long incomeGoal = store.goal(owner, "income");
        model.addAttribute("incomeGoal", incomeGoal);
        addIncomeProgress(owner, incomeGoal, model);
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
                model.addAttribute("percentage", displayPercentage(percentage));
                model.addAttribute("remaining", BigInteger.valueOf(capitalGoal).subtract(capital).max(BigInteger.ZERO));
            }
        }
        return "fire";
    }

    private void addIncomeProgress(String owner, Long goal, Model model) {
        YearMonth last = period.currentInMoscow().minusMonths(1);
        var history = store.incomeHistory(owner, last);
        if (history.isEmpty()) return;

        var months = history.stream().collect(Collectors.groupingBy(
                AccountIncome::month, LinkedHashMap::new, Collectors.toList()));
        var gaps = new ArrayList<IncomeGap>();
        BigInteger credited = BigInteger.ZERO;
        BigInteger completeTotal = BigInteger.ZERO;
        boolean hasPayoutData = false;
        int completeMonths = 0;
        for (var month : months.entrySet()) {
            var missing = month.getValue().stream()
                    .filter(account -> account.credited() == null).map(AccountIncome::name).toList();
            BigInteger total = month.getValue().stream().map(AccountIncome::credited)
                    .filter(amount -> amount != null).map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
            credited = credited.add(total);
            hasPayoutData |= missing.size() < month.getValue().size();
            if (missing.isEmpty()) {
                completeTotal = completeTotal.add(total);
                completeMonths++;
            } else {
                gaps.add(new IncomeGap(month.getKey(), BudgetPeriod.title(month.getKey()), missing));
            }
        }

        model.addAttribute("incomeFirst", history.getFirst().month());
        model.addAttribute("incomeLast", last);
        model.addAttribute("incomeFirstTitle", BudgetPeriod.title(history.getFirst().month()));
        model.addAttribute("incomeLastTitle", BudgetPeriod.title(last));
        if (hasPayoutData) model.addAttribute("incomeTotal", credited);
        model.addAttribute("incomeGaps", gaps);
        if (completeMonths == 0) return;

        BigDecimal total = new BigDecimal(completeTotal);
        BigDecimal count = BigDecimal.valueOf(completeMonths);
        model.addAttribute("incomeDuration", "за " + completeMonths
                + (completeMonths == 1 ? " месяц" : completeMonths <= 4 ? " месяца" : " месяцев"));
        model.addAttribute("incomeAverage", total.divide(count, 0, RoundingMode.HALF_UP));
        if (goal != null) {
            BigDecimal percentage = total.movePointRight(2)
                    .divide(count.multiply(BigDecimal.valueOf(goal)), 2, RoundingMode.HALF_UP);
            model.addAttribute("incomePercentage", displayPercentage(percentage));
        }
    }

    private static String displayPercentage(BigDecimal percentage) {
        return percentage.stripTrailingZeros().toPlainString().replace('.', ',');
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
