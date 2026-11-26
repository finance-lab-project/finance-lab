package org.financelab.budget;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigInteger;
import java.security.Principal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.financelab.budget.InvestmentStore.AccountMonth;
import org.financelab.budget.InvestmentStore.Figures;
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
@RequestMapping("/investments")
class InvestmentController {
    private final InvestmentStore store;
    private final BudgetPeriod period;
    private final MonthStore budget;

    InvestmentController(InvestmentStore store, BudgetPeriod period, MonthStore budget) {
        this.store = store;
        this.period = period;
        this.budget = budget;
    }

    @GetMapping
    String select(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month) {
        if (year == null && month == null) return "redirect:/investments/" + period.current();
        if (year == null || month == null || month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите месяц и год.");
        }
        requireValidYear(year);
        return "redirect:/investments/" + YearMonth.of(year, month);
    }

    @GetMapping("/{month}")
    String investments(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                       Model model) {
        requireValidYear(month.getYear());
        return view(model, owner.getName(), month, store.read(owner.getName(), month));
    }

    @PostMapping("/{month}/accounts")
    String addAccount(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                      @RequestParam(defaultValue = "") String name, Model model, HttpServletResponse response,
                      RedirectAttributes redirect) {
        requireValidYear(month.getYear());
        String accountName = name.strip();
        String error = null;
        if (accountName.isEmpty()) {
            error = "Введите название счёта.";
        } else if (!store.addAccount(owner.getName(), accountName)) {
            error = "Счёт с таким названием уже есть.";
        }
        if (error != null) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("nameError", error);
            model.addAttribute("accountName", accountName);
            return view(model, owner.getName(), month, store.read(owner.getName(), month));
        }
        redirect.addFlashAttribute("accountAdded", true);
        return "redirect:/investments/" + month;
    }

    @PostMapping("/{month}/accounts/{accountId}")
    String saveMonth(Principal owner, @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                     @PathVariable long accountId, @RequestParam Map<String, String> fields,
                     Model model, HttpServletResponse response, RedirectAttributes redirect) {
        requireValidYear(month.getYear());
        var errors = new HashMap<String, String>();
        long closingValue = MonthActuals.amount("closingValue", fields, errors);
        long creditedIncome = MonthActuals.amount("creditedIncome", fields, errors);
        if (!errors.isEmpty()) {
            var accounts = store.read(owner.getName(), month);
            if (accounts.stream().noneMatch(account -> account.id() == accountId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("draftAccount", accountId);
            model.addAttribute("draft", fields);
            model.addAttribute("errors", errors);
            return view(model, owner.getName(), month, accounts);
        }
        if (!store.saveMonth(owner.getName(), accountId, month, new Figures(closingValue, creditedIncome))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        redirect.addFlashAttribute("savedAccount", accountId);
        return "redirect:/investments/" + month + "#account-" + accountId;
    }

    private String view(Model model, String owner, YearMonth month, List<AccountMonth> accounts) {
        model.addAttribute("period", month);
        model.addAttribute("months", BudgetPeriod.MONTHS);
        model.addAttribute("title", BudgetPeriod.title(month));
        model.addAttribute("accounts", accounts);
        model.addAttribute("previousTitle", BudgetPeriod.title(month.minusMonths(1)));
        boolean complete = !accounts.isEmpty() && accounts.stream().allMatch(account -> account.figures() != null);
        boolean previousComplete = !accounts.isEmpty() && accounts.stream().allMatch(account -> account.previousValue() != null);
        BigInteger value = complete ? total(accounts, account -> account.figures().closingValue()) : null;
        BigInteger previous = previousComplete ? total(accounts, AccountMonth::previousValue) : null;
        model.addAttribute("portfolioValue", value);
        model.addAttribute("previousValue", previous);
        model.addAttribute("valueChange", complete && previousComplete ? value.subtract(previous) : null);
        model.addAttribute("portfolioIncome", complete ? total(accounts, account -> account.figures().creditedIncome()) : null);
        model.addAttribute("contributions", budget.savedFigures(owner, month).map(MonthBudget.Figures::contributions).orElse(null));
        return "investments";
    }

    private static BigInteger total(List<AccountMonth> accounts, ToLongFunction<AccountMonth> field) {
        return accounts.stream().mapToLong(field).mapToObj(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static void requireValidYear(int year) {
        if (year < 1 || year > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите год от 1 до 9999.");
        }
    }
}
