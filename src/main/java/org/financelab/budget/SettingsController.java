package org.financelab.budget;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.RequestContextUtils;
import tools.jackson.databind.ObjectMapper;

@Controller
@RequestMapping("/settings")
class SettingsController {
    private final PlanStore plans;
    private final BudgetPeriod period;
    private final ObjectMapper json;

    SettingsController(PlanStore plans, BudgetPeriod period, ObjectMapper json) {
        this.plans = plans;
        this.period = period;
        this.json = json;
    }

    @GetMapping
    String settings(Principal owner, Model model) {
        YearMonth month = period.current();
        PlanStore.Settings saved = plans.read(owner.getName(), month);
        model.addAttribute("previewJson", json.writeValueAsString(PlanCalculator.evaluate(saved.draft())));
        model.addAttribute("revision", saved.revision());
        model.addAttribute("period", month.toString());
        model.addAttribute("title", BudgetPeriod.title(month));
        return "settings";
    }

    @PostMapping("/preview")
    @ResponseBody
    PlanCalculator.Preview preview(@RequestBody PlanDraft draft) {
        return PlanCalculator.evaluate(draft);
    }

    record Save(String period, long revision, PlanDraft draft) {}

    @PostMapping
    @ResponseBody
    ResponseEntity<?> save(Principal principal, @RequestBody Save form,
                           HttpServletRequest request, HttpServletResponse response) {
        if (form.draft() == null) throw new IllegalArgumentException("Проверьте поля настроек плана.");
        String owner = principal.getName();
        YearMonth month = period.current();
        PlanStore.Settings saved = plans.read(owner, month);
        if (!month.toString().equals(form.period()) || saved.revision() != form.revision()) return conflict();

        var rows = form.draft().rows();
        var existing = saved.draft().rows();
        if (rows.size() < existing.size()) throw new IllegalArgumentException("В плане должны остаться сохранённые категории.");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String expected = i < existing.size() ? existing.get(i).code() : "";
            if (!expected.equals(row.code())) throw new IllegalArgumentException("Неверная категория или порядок добавления.");
            if (row.name().isBlank()) throw new IllegalArgumentException("Введите название каждой категории.");
        }
        var plan = PlanCalculator.evaluate(form.draft());
        if (plan.error() != null) throw new IllegalArgumentException(plan.error());
        if (!plans.save(owner, month, form.revision(), plan)) return conflict();
        RequestContextUtils.getOutputFlashMap(request).put("saved", true);
        RequestContextUtils.saveOutputFlashMap(request.getContextPath() + "/months/" + month, request, response);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                "Настройки уже изменились. Черновик остался на экране. Откройте сохранённый план в другой вкладке для сверки."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("error", "Проверьте поля настроек плана."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    ResponseEntity<?> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.unprocessableContent().body(Map.of("error", invalid.getMessage()));
    }
}
