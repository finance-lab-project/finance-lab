package org.financelab.budget;

import java.io.IOException;
import java.math.BigInteger;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.context.support.GenericWebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarchBudgetTest {
    private static final String MARCH = "/months/2027-03";
    private static final String EDIT = MARCH + "?edit=true";
    @TempDir Path directory;

    @Test
    void pilotReopensTheSameMonthFromAnotherSessionAndAfterCredentialRotation() throws Exception {
        String login = "pilot-owner";
        String firstPassword = "first-synthetic-pilot-password";
        String replacement = "replacement-synthetic-pilot-password";
        try (var app = startPilot(firstPassword)) {
            var desktop = new Browser(app);
            var phone = new Browser(app);
            assertThat(desktop.get(MARCH).status()).isEqualTo(302);
            for (String fixture : List.of("owner-a", "owner-b")) {
                desktop.login(fixture, "fixture-" + fixture);
                assertThat(desktop.get(MARCH).status()).isEqualTo(302);
            }
            desktop.login(login, firstPassword);
            phone.login(login, firstPassword);
            Document empty = desktop.get(MARCH).html();
            assertThat(empty.text()).contains("План пока не задан");
            assertThat(empty.expectFirst("#actual-income").text()).isEqualTo("0 ₽");
            assertThat(app.getBean(JdbcClient.class).sql("SELECT count(*) FROM category_month")
                    .query(Long.class).single()).isZero();
            assertThat(desktop.save(desktop.get(EDIT).html(), Map.of("income", "12345")).status()).isEqualTo(302);
            assertThat(phone.get(MARCH).html().expectFirst("#actual-income").text()).isEqualTo("12 345 ₽");
        }
        try (var restarted = startPilot(replacement)) {
            var browser = new Browser(restarted);
            browser.login(login, firstPassword);
            assertThat(browser.get(MARCH).status()).isEqualTo(302);
            browser.login(login, replacement);
            assertThat(browser.get(MARCH).html().expectFirst("#actual-income").text()).isEqualTo("12 345 ₽");
        }
    }

    @Test
    void reconcilesBudgetCardsWithoutChangingActualsOrCategoryCarry() throws Exception {
        String referenceDate = "2027-03-15";
        var reconciliation = Map.of("referenceDate", referenceDate, "cardBalance", "100 000");
        try (var app = startCashTest("2027-03")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO month_actuals VALUES
                        ('owner-a', '2027-02', 10000, 5000, 0),
                        ('owner-a', '2027-03', 20000, 10000, 0)
                    """).update();
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                        ('owner-a', '2027-02', 'food', 18000, 15000),
                        ('owner-a', '2027-03', 'food', 60000, 50000)
                    """).update();
            jdbc.sql("INSERT INTO investment_account VALUES (1, 'owner-a', 'Вклад')").update();
            jdbc.sql("INSERT INTO investment_month VALUES (1, '2027-03', 250000, 1000)").update();
            jdbc.sql("INSERT INTO fire_account VALUES (1)").update();
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            Document before = reviewPage(mvc, MARCH);
            assertThat(before.select("#cash-opening")).isEmpty();
            var form = before.expectFirst("#cash-reconciliation-form");
            cashPost(mvc, form, Map.of("referenceDate", "2027-04-01", "cardBalance", "100000"))
                    .andExpect(status().is(422));
            cashPost(mvc, form, reconciliation).andExpect(status().isFound());
            Document reconciled = reviewPage(mvc, MARCH);
            assertCash(reconciled, "150 000 ₽", "100 000 ₽");
            for (String selector : List.of("#actual-income", "#actual-contributions", "#spending",
                    "#row-food .buffer", "#row-food .plan")) {
                assertThat(reconciled.expectFirst(selector).text()).isEqualTo(before.expectFirst(selector).text());
            }
            assertThat(reviewPage(mvc, "/fire").expectFirst("#fire-capital").text()).isEqualTo("250 000 ₽");
            var edit = reviewPage(mvc, EDIT).expectFirst("#month-form");
            cashPost(mvc, edit, Map.of("amount-food", "55000", "correctionDate", referenceDate))
                    .andExpect(status().is(422));
            cashPost(mvc, edit, Map.of("amount-food", "55000", "correctionDate", "2027-03-10"))
                    .andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "100 000 ₽");
            cashPost(mvc, reviewPage(mvc, EDIT).expectFirst("#month-form"), Map.of("amount-food", "60000"))
                    .andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "95 000 ₽");
            cashPost(mvc, form, reconciliation).andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "95 000 ₽");
            assertThat(mvc.perform(get(MARCH).principal(() -> "owner-b")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).doesNotContain("cash-reference-date");
        }
        try (var restarted = startCashTest("2027-04")) {
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) restarted).build();
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "95 000 ₽");
            assertThat(reviewPage(mvc, "/fire").expectFirst("#income-total").text()).isEqualTo("1 000 ₽");
            String history = "/history/2027-03";
            cashPost(mvc, reviewPage(mvc, history).expectFirst("#history-form"),
                    Map.of("amount-food", "65000", "correctionDate", "2027-03-10")).andExpect(status().isFound());
            Document corrected = reviewPage(mvc, MARCH);
            assertCash(corrected, "160 000 ₽", "95 000 ₽");
            assertThat(corrected.expectFirst("#spending").text()).isEqualTo("65 000 ₽");
            assertThat(corrected.expectFirst("#row-food .buffer").text()).isEqualTo("3 000 ₽");
            cashPost(mvc, reviewPage(mvc, "/months/2027-04?edit=true").expectFirst("#month-form"),
                    Map.of("income", "2000")).andExpect(status().isFound());
            assertCash(reviewPage(mvc, "/months/2027-04"), "160 000 ₽", "97 000 ₽");
        }
    }

    @Test
    void distinguishesEarlierMonthCorrectionsFromNewExpenses() throws Exception {
        try (var app = startCashTest("2027-03")) {
            var food = new PlanDraft.Row("", "Продукты", "essential", "amount", "60000");
            var plan = PlanCalculator.evaluate(new PlanDraft("60000", "amount", "0", List.of(food)));
            assertThat(app.getBean(PlanStore.class).save("owner-a", YearMonth.of(2027, 3), 0, plan)).isTrue();
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            String history = "/history/2027-02";
            var initial = reviewPage(mvc, history).expectFirst("#history-form");
            String spendingField = initial.expectFirst("input[name^=amount-]").attr("name");
            cashPost(mvc, initial, Map.of(spendingField, "50000")).andExpect(status().isFound());
            var form = reviewPage(mvc, MARCH).expectFirst("#cash-reconciliation-form");
            var reconciliation = Map.of("referenceDate", "2027-03-15", "cardBalance", "100000");
            cashPost(mvc, form, reconciliation).andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "150 000 ₽", "100 000 ₽");

            var februaryForm = reviewPage(mvc, history).expectFirst("#history-form");
            var correctionDate = februaryForm.expectFirst("#correctionDate");
            assertThat(correctionDate.attr("min")).isEqualTo("2027-02-01");
            assertThat(correctionDate.attr("max")).isEqualTo("2027-02-28");
            cashPost(mvc, februaryForm, Map.of(spendingField, "55000", "correctionDate", "2027-03-10"))
                    .andExpect(status().is(422));
            cashPost(mvc, februaryForm, Map.of(spendingField, "55000", "correctionDate", "2027-02-10"))
                    .andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "100 000 ₽");

            cashPost(mvc, reviewPage(mvc, history).expectFirst("#history-form"), Map.of(spendingField, "60000"))
                    .andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "95 000 ₽");
            cashPost(mvc, form, reconciliation).andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "155 000 ₽", "95 000 ₽");
        }
    }

    @Test
    void backdatedReconciliationSeparatesAlreadyRecordedLaterSpending() throws Exception {
        try (var app = startCashTest("2027-03")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual)
                    VALUES ('owner-a', '2027-03', 'food', 60000, 50000)
                    """).update();
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            cashPost(mvc, reviewPage(mvc, EDIT).expectFirst("#month-form"), Map.of("amount-food", "55000"))
                    .andExpect(status().isFound());
            var form = reviewPage(mvc, MARCH).expectFirst("#cash-reconciliation-form");
            var backdated = Map.of("referenceDate", "2027-03-15", "cardBalance", "100000");
            cashPost(mvc, form, backdated).andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "150 000 ₽", "95 000 ₽");
            cashPost(mvc, form, Map.of("referenceDate", "2027-03-31", "cardBalance", "95000"))
                    .andExpect(status().isFound());
            assertThat(reviewPage(mvc, MARCH).expectFirst("#cash-opening").text()).isEqualTo("150 000 ₽");
            cashPost(mvc, form, backdated).andExpect(status().isFound());
            assertCash(reviewPage(mvc, MARCH), "150 000 ₽", "95 000 ₽");
            cashPost(mvc, form, Map.of("referenceDate", "2027-03-15", "cardBalance", "105000"))
                    .andExpect(status().isFound());
            Document updated = reviewPage(mvc, MARCH);
            assertThat(updated.expectFirst("#cash-opening").text()).isEqualTo("155 000 ₽");
            assertThat(updated.expectFirst("#cash-balance").text()).isEqualTo("100 000 ₽");
        }
    }

    private ConfigurableApplicationContext startCashTest(String month) {
        return new SpringApplicationBuilder(FinanceLabApplication.class).profiles("fixture")
                .contextFactory(webType -> new GenericWebApplicationContext(new MockServletContext()))
                .run("--spring.datasource.url=jdbc:sqlite:" + directory.resolve("cash.sqlite"),
                        "--budget.current-month=" + month,
                        "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private static ResultActions cashPost(MockMvc mvc, Element form, Map<String, String> edits) throws Exception {
        var fields = new LinkedMultiValueMap<String, String>();
        for (var input : form.select("input[name]:not([disabled])")) {
            fields.set(input.attr("name"), input.val());
        }
        edits.forEach(fields::set);
        return mvc.perform(post(form.attr("action")).principal(() -> "owner-a").params(fields));
    }

    private static void assertCash(Document page, String opening, String balance) {
        assertThat(page.expectFirst("#cash-reference-date").text()).isEqualTo("2027-03-15");
        assertThat(page.expectFirst("#cash-reference-balance").text()).isEqualTo("100 000 ₽");
        assertThat(page.expectFirst("#cash-opening").text()).isEqualTo(opening);
        assertThat(page.expectFirst("#cash-balance").text()).isEqualTo(balance);
    }

    @Test
    void savedReplacementSurvivesRefreshRepeatAndRestart() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            Document form = desktop.get(EDIT).html();
            assertThat(form.expectFirst("#amount-food").val()).isEqualTo("6 000");
            assertMonth(phone.get(MARCH).html(), "6 000", "20 000", "18 000");

            assertThat(desktop.save(form, "25 000").status()).isEqualTo(302);
            Document desktopSaved = desktop.get(MARCH).html();
            assertThat(desktopSaved.selectFirst("#save-success")).isNotNull();
            assertThat(desktopSaved.expectFirst("#row-food .available").text()).isEqualTo("−3 000 ₽");
            Document phoneSaved = phone.get(MARCH).html();
            assertMonth(phoneSaved, "25 000", "39 000", "−1 000");
            assertThat(phoneSaved.expectFirst("#row-food .available").text()).isEqualTo("−3 000 ₽");
            Document repeat = phone.get(EDIT).html();
            assertThat(phone.save(repeat, "25 000").status()).isEqualTo(302);
            assertThat(revision(phone.get(EDIT).html())).isEqualTo(revision(repeat));
            assertMonth(phone.get(MARCH).html(), "25 000", "39 000", "−1 000");
        }
        try (var restarted = start()) {
            assertMonth(signedIn(restarted, "owner-a").get(MARCH).html(), "25 000", "39 000", "−1 000");
            assertMonth(signedIn(restarted, "owner-b").get(MARCH).html(), "6 000", "20 000", "18 000");
        }
    }

    @Test
    void investmentMonthsKeepOwnerFiguresAndBudgetAcrossSessionsAndRestart() throws Exception {
        String february = "/investments/2027-02";
        String march = "/investments/2027-03";
        try (var app = start()) {
            assertThat(new Browser(app).get(february).status()).isEqualTo(302);
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            assertThat(desktop.save(desktop.get(EDIT).html(), Map.of("income", "12345", "contributions", "4567"))
                    .status()).isEqualTo(302);
            assertThat(desktop.get(MARCH).html().expectFirst("aside a[href=/investments]").text())
                    .isEqualTo("Инвестиции");
            assertThat(desktop.get("/investments?year=2027&month=2").status()).isEqualTo(302);

            var addAccount = desktop.get(february).html().expectFirst("#add-investment-account");
            assertThat(addAccount.select("select[name=kind] option").eachText())
                    .containsExactly("Инвестиционный счёт", "Вклад", "Накопительный счёт");
            assertThat(desktop.postForm(addAccount, Map.of("name", "Учебный вклад", "kind", "DEPOSIT")).status())
                    .isEqualTo(302);
            assertThat(desktop.postForm(addAccount, Map.of("name", "Учебные сбережения", "kind", "SAVINGS")).status())
                    .isEqualTo(302);
            assertThat(desktop.postForm(addAccount, Map.of("name", "Учебный брокерский счёт")).status())
                    .isEqualTo(302);
            var duplicate = desktop.postForm(addAccount, Map.of("name", "Учебный вклад", "kind", "SAVINGS"));
            assertThat(duplicate.status()).isEqualTo(422);
            assertThat(duplicate.html().expectFirst("select[name=kind] option[selected]").val()).isEqualTo("SAVINGS");
            assertThat(desktop.postForm(addAccount, Map.of("name", "Недопустимый тип", "kind", "OTHER")).status())
                    .isEqualTo(400);

            var accounts = desktop.get(february).html().select(".investment-account");
            assertThat(accounts.select("legend").eachText())
                    .containsExactly("Учебный вклад", "Учебные сбережения", "Учебный брокерский счёт");
            assertThat(accounts.getFirst().text()).contains("Вклад", "Остаток на конец месяца");
            assertThat(accounts.get(1).text()).contains("Накопительный счёт", "Остаток на конец месяца");
            assertThat(accounts.getLast().text()).contains("Инвестиционный счёт", "Стоимость на конец месяца");
            assertThat(desktop.postForm(accounts.get(1), Map.of("closingValue", "20000", "creditedIncome", "200"))
                    .status()).isEqualTo(302);
            assertInvestmentFigures(accounts.getFirst(), "", "");
            assertThat(accounts.getFirst().text()).contains("Нет записи за этот месяц.");
            var coupon = Map.of("closingValue", "101 500", "creditedIncome", "1500");
            assertThat(desktop.postForm(accounts.getFirst(), coupon).status()).isEqualTo(302);
            assertThat(desktop.postForm(accounts.getLast(), Map.of("closingValue", "40000", "creditedIncome", "0"))
                    .status()).isEqualTo(302);

            accounts = phone.get(february).html().select(".investment-account");
            assertInvestmentFigures(accounts.getFirst(), "101 500", "1 500");
            assertInvestmentFigures(accounts.getLast(), "40 000", "0");
            assertThat(accounts.getLast().text()).doesNotContain("Нет записи за этот месяц.");
            for (var unreported : phone.get(march).html().select(".investment-account")) {
                assertInvestmentFigures(unreported, "", "");
                assertThat(unreported.text()).contains("Нет записи за этот месяц.");
            }

            var correction = Map.of("closingValue", "102000", "creditedIncome", "1700");
            assertThat(phone.postForm(accounts.getFirst(), correction).status()).isEqualTo(302);
            assertThat(phone.postForm(accounts.getFirst(), correction).status()).isEqualTo(302);
            var invalid = phone.postForm(accounts.getFirst(), Map.of("closingValue", "103000", "creditedIncome", ""));
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.html().expectFirst("input[name=creditedIncome][aria-invalid=true]").val()).isEmpty();

            var other = signedIn(app, "owner-b");
            Document otherPage = other.get(february).html();
            assertThat(otherPage.select(".investment-account")).isEmpty();
            assertThat(other.post(accounts.getFirst().attr("action"),
                    Map.of("closingValue", "1", "creditedIncome", "1", "_csrf", csrf(otherPage))).status())
                    .isEqualTo(404);
            assertInvestmentFigures(desktop.get(february).html().expectFirst(".investment-account"), "102 000", "1 700");
            assertThat(app.getBean(JdbcClient.class).sql("SELECT count(*) FROM investment_month")
                    .query(Long.class).single()).isEqualTo(3);
        }
        try (var restarted = start()) {
            var owner = signedIn(restarted, "owner-a");
            var accounts = owner.get(february).html().select(".investment-account");
            assertInvestmentFigures(accounts.getFirst(), "102 000", "1 700");
            assertThat(accounts.getFirst().text()).contains("Вклад");
            assertThat(accounts.get(1).text()).contains("Накопительный счёт");
            assertInvestmentFigures(accounts.get(1), "20 000", "200");
            assertInvestmentFigures(accounts.getLast(), "40 000", "0");
            assertInvestmentFigures(owner.get(march).html().expectFirst(".investment-account"), "", "");
            Document budget = owner.get(EDIT).html();
            assertThat(budget.expectFirst("input[name=income]").val()).isEqualTo("12 345");
            assertThat(budget.expectFirst("input[name=contributions]").val()).isEqualTo("4 567");
            assertMonth(budget, "6 000", "20 000", "18 000");
        }
    }

    @Test
    void investmentReviewComparesCompleteCalendarMonthsAndReadsSavedBudgetContributions() throws Exception {
        String february = "/investments/2027-02";
        String march = "/investments/2027-03";
        try (var app = new SpringApplicationBuilder(FinanceLabApplication.class).profiles("demo")
                .contextFactory(webType -> new GenericWebApplicationContext(new MockServletContext()))
                .run("--spring.datasource.url=jdbc:sqlite:" + directory.resolve("review.sqlite"),
                        "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            for (String name : List.of("Вклад", "Брокерский счёт")) {
                mvc.perform(post(february + "/accounts").principal(() -> "owner-a").param("name", name))
                        .andExpect(status().isFound());
            }
            Document blank = reviewPage(mvc, february);
            var accounts = blank.select(".investment-account");
            assertThat(accounts).hasSize(2);
            var first = accounts.getFirst().attr("action");
            var second = accounts.getLast().attr("action");
            mvc.perform(post(first).principal(() -> "owner-a").param("closingValue", "95000").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            Document incomplete = reviewPage(mvc, february);
            assertThat(incomplete.expectFirst("#portfolio-value").text()).isEqualTo("Нет полной оценки");
            assertThat(incomplete.expectFirst("#portfolio-income").text()).isEqualTo("Нет полного итога");
            assertThat(incomplete.select(".investment-account").getLast().text()).contains("Нет записи за этот месяц.");
            mvc.perform(post(second).principal(() -> "owner-a").param("closingValue", "145000").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            Document firstMonth = reviewPage(mvc, february);
            assertThat(firstMonth.expectFirst("#portfolio-value").text()).isEqualTo("240 000 ₽");
            assertThat(firstMonth.expectFirst("#portfolio-income").text()).isEqualTo("0 ₽");
            assertThat(firstMonth.expectFirst("#previous-value").text()).isEqualTo("Нет полной оценки");
            assertThat(firstMonth.expectFirst("#value-change").text()).isEqualTo("Нет данных для сравнения");

            mvc.perform(post(first.replace(february, march)).principal(() -> "owner-a")
                            .param("closingValue", "100000").param("creditedIncome", "1000"))
                    .andExpect(status().isFound());
            mvc.perform(post(second.replace(february, march)).principal(() -> "owner-a")
                            .param("closingValue", "160000").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            for (String contribution : List.of("15000", "0")) {
                Document budgetForm = reviewPage(mvc, EDIT);
                var fields = new LinkedMultiValueMap<String, String>();
                monthFields(budgetForm).forEach(fields::add);
                fields.set("contributions", contribution);
                mvc.perform(post(MARCH).principal(() -> "owner-a").params(fields)).andExpect(status().isFound());

                Document review = reviewPage(mvc, march);
                assertThat(review.expectFirst("#portfolio-value").text()).isEqualTo("260 000 ₽");
                assertThat(review.expectFirst("#previous-value").text()).isEqualTo("240 000 ₽");
                assertThat(review.expectFirst("#value-change").text()).isEqualTo("+20 000 ₽");
                assertThat(review.expectFirst("#value-change").parent().expectFirst("dt").text())
                        .isEqualTo("Изменение стоимости · Февраль 2027 → Март 2027");
                assertThat(review.expectFirst("#portfolio-income").text()).isEqualTo("1 000 ₽");
                assertThat(review.expectFirst("#budget-contributions").text())
                        .isEqualTo(contribution.equals("0") ? "0 ₽" : "15 000 ₽");
                assertInvestmentFigures(review.select(".investment-account").getFirst(), "100 000", "1 000");
                assertInvestmentFigures(review.select(".investment-account").getLast(), "160 000", "0");
            }

            mvc.perform(post(first.replace(february, "/investments/2027-05")).principal(() -> "owner-a")
                            .param("closingValue", "0").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            mvc.perform(post(second.replace(february, "/investments/2027-05")).principal(() -> "owner-a")
                            .param("closingValue", "0").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            Document may = reviewPage(mvc, "/investments/2027-05");
            assertThat(may.expectFirst("#portfolio-value").text()).isEqualTo("0 ₽");
            assertThat(may.expectFirst("#value-change").text()).isEqualTo("Нет данных для сравнения");
            assertThat(may.expectFirst("#budget-contributions").text()).isEqualTo("Нет записи в бюджете");

            String april = "/investments/2027-04";
            mvc.perform(post(first.replace(february, april)).principal(() -> "owner-a")
                            .param("closingValue", "100000").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            Document partialPrior = reviewPage(mvc, "/investments/2027-05");
            assertThat(partialPrior.expectFirst("#portfolio-value").text()).isEqualTo("0 ₽");
            assertThat(partialPrior.expectFirst("#previous-value").text()).isEqualTo("Нет полной оценки");
            assertThat(partialPrior.expectFirst("#value-change").text()).isEqualTo("Нет данных для сравнения");

            mvc.perform(post(second.replace(february, april)).principal(() -> "owner-a")
                            .param("closingValue", "0").param("creditedIncome", "0"))
                    .andExpect(status().isFound());
            Document completePrior = reviewPage(mvc, "/investments/2027-05");
            assertThat(completePrior.expectFirst("#previous-value").text()).isEqualTo("100 000 ₽");
            assertThat(completePrior.expectFirst("#value-change").text()).isEqualTo("−100 000 ₽");
        }
    }

    @Test
    void ac04ReplacesAnIndependentTwentyThousandBaseline() throws Exception {
        try (var app = start()) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.save(owner.get(EDIT).html(), "20 000").status()).isEqualTo(302);
            Document baseline = owner.get(EDIT).html();
            assertMonth(baseline, "20 000", "34 000", "4 000");
            assertThat(owner.save(baseline, "25 000").status()).isEqualTo(302);
            assertMonth(owner.get(MARCH).html(), "25 000", "39 000", "−1 000");
        }
    }

    @Test
    void allActualsAreValidatedAndReplacedTogetherAgainstPlanningIncome() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            Document form = desktop.get(EDIT).html();
            assertThat(form.select(".editor")).hasSize(5);
            Document phoneForm = phone.get(EDIT).html();
            assertThat(form.expectFirst("#percent-food").val()).isEqualTo("6");
            var edits = Map.of("income", "73 001", "contributions", "1 234,50", "amount-food", "25 000,00",
                    "unit-leisure", "percent", "percent-leisure", "112,3455");
            Page invalid = desktop.save(form, edits);
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.html().expectFirst("#error-contributions").text()).contains("целых рублях");
            assertThat(invalid.html().expectFirst("#income").val()).isEqualTo("73 001");
            assertThat(invalid.html().expectFirst("#percent-leisure").val()).isEqualTo("112,3455");
            assertThat(invalid.html().expectFirst("#amount-leisure").val()).isEqualTo("112 346");
            assertThat(invalid.html().expectFirst("#amount-food").val()).isEqualTo("25 000,00");
            assertThat(invalid.html().expectFirst("#row-food .available").text()).isEqualTo("16 000 ₽");
            assertMonth(phone.get(MARCH).html(), "6 000", "20 000", "18 000");
            assertThat(phone.get(MARCH).html().expectFirst("#actual-income").text()).isEqualTo("50 000 ₽");

            assertThat(desktop.save(invalid.html(), Map.of("contributions", "15 002")).status()).isEqualTo(302);
            Document saved = phone.get(MARCH).html();
            assertMonth(saved, "25 000", "150 346", "−1 000");
            assertThat(saved.expectFirst("#plan-income").text()).isEqualTo("100 000 ₽");
            assertThat(saved.expectFirst("#actual-income").text()).isEqualTo("73 001 ₽");
            assertThat(saved.expectFirst("#actual-contributions").text()).isEqualTo("15 002 ₽");
            assertThat(saved.expectFirst("#row-leisure .actual").text()).isEqualTo("112 346 ₽");
            Page stale = phone.save(phoneForm, Map.of("income", "1", "amount-health", "123"));
            assertThat(stale.status()).isEqualTo(409);
            assertThat(stale.html().expectFirst("#amount-health").val()).isEqualTo("123");
            assertThat(stale.html().expectFirst("#row-health .actual").text()).isEqualTo("0 ₽");
            assertThat(phone.save(phone.get(EDIT).html(), Map.of()).status()).isEqualTo(302);
        }
        try (var app = start()) {
            var phone = signedIn(app, "owner-a");
            Document saved = phone.get(MARCH).html();
            assertMonth(saved, "25 000", "150 346", "−1 000");
            assertThat(saved.expectFirst("#actual-contributions").text()).isEqualTo("15 002 ₽");
            var other = signedIn(app, "owner-b").get(MARCH).html();
            assertThat(other.expectFirst("#actual-contributions").text()).isEqualTo("10 000 ₽");
        }
    }

    @Test
    void aFailedCategoryWriteRollsBackTheWholeMonth() throws Exception {
        try (var app = start()) {
            var month = java.time.YearMonth.of(2027, 3);
            var store = app.getBean(MonthStore.class);
            var before = store.read("owner-a", month);
            var fields = MonthActuals.savedFields(before);
            fields.putAll(Map.of("income", "1", "contributions", "2", "amount-food", "3", "amount-travel", "4"));
            var actuals = MonthActuals.parse(before, fields, new HashMap<>());
            app.getBean(JdbcClient.class).sql("""
                    CREATE TRIGGER fail_last_category BEFORE UPDATE OF actual ON category_month
                    WHEN NEW.category = 'travel' BEGIN SELECT RAISE(ABORT, 'test write failure'); END
                    """).update();
            assertThatThrownBy(() -> store.replace("owner-a", month, before, before.figures().revision(), actuals))
                    .isInstanceOf(DataAccessException.class);
            assertThat(store.read("owner-a", month)).isEqualTo(before);
        }
    }

    @Test
    void zeroPlanningIncomeAllowsIndependentActualsInRubles() throws Exception {
        try (var app = start(false)) {
            var owner = signedIn(app, "owner-a");
            var plan = new PlanDraft("0", "incomePercent", "0", List.of(
                    new PlanDraft.Row("", "Покупки", "essential", "amount", "0")));
            assertThat(owner.plan(owner.get("/settings").html(), plan).status()).isEqualTo(204);
            Document form = owner.get(EDIT).html();
            String code = settingsDraft(owner.get("/settings").html()).rows().getFirst().code();
            assertThat(form.expectFirst("#percent-" + code).hasAttr("disabled")).isTrue();
            var invalid = owner.save(form, Map.of("unit-" + code, "percent", "percent-" + code, "10"));
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.html().expectFirst("#error-percent-" + code).text()).contains("в рублях");
            assertThat(owner.save(form, Map.of("income", "27", "contributions", "5", "amount-" + code, "11"))
                    .status()).isEqualTo(302);
            var saved = owner.get(MARCH).html();
            assertThat(saved.expectFirst("#plan-income").text()).isEqualTo("0 ₽");
            assertThat(saved.expectFirst("#actual-income").text()).isEqualTo("27 ₽");
            assertThat(saved.expectFirst("#spending").text()).isEqualTo("11 ₽");
        }
    }

    @Test
    void reportShowsCommittedYearResultsAndExcludesMarchFromAveragesAndBuffer() throws Exception {
        try (var app = start()) {
            assertThat(new Browser(app).get("/years/2027").status()).isEqualTo(302);
            var owner = signedIn(app, "owner-a");
            var other = signedIn(app, "owner-b");
            String path = owner.get(MARCH).html().expectFirst("[data-year-report]").attr("href");
            assertThat(path).isEqualTo("/years/2027");
            Page page = owner.get(path);
            assertThat(page.status()).isEqualTo(200);
            Document report = page.html();
            assertThat(report.expectFirst("#average-period").text()).isEqualTo("Январь — февраль 2027");
            assertThat(report.expectFirst("#buffer-cutoff").text()).contains("1 марта 2027 года");
            assertThat(report.select("#year-income, #year-spending, #year-contributions").eachText())
                    .containsExactly("250 000 ₽", "131 000 ₽", "90 000 ₽");
            assertThat(report.select("#average-income, #average-spending, #average-contributions").eachText())
                    .containsExactly("100 000 ₽", "55 500 ₽", "40 000 ₽");
            assertThat(report.expectFirst("#year-buffer").text()).isEqualTo("−5 000 ₽");
            assertThat(report.select("#row-food td").eachText())
                    .containsExactly("288 000 ₽", "54 000 ₽", "24 000 ₽", "−2 000 ₽");
            assertThat(report.select("#year-months tbody tr")).hasSize(12);
            var march = report.expectFirst("#year-months tr:has(a[href='" + MARCH + "'])");
            assertThat(march.select("td").eachText()).containsExactly("50 000 ₽", "20 000 ₽", "10 000 ₽");
            assertThat(report.select("#year-months tbody tr:last-child td").eachText()).containsOnly("0 ₽");

            assertThat(owner.save(owner.get(EDIT).html(), "25 000").status()).isEqualTo(302);
            report = owner.get(path).html();
            assertThat(report.expectFirst("#year-spending").text()).isEqualTo("150 000 ₽");
            assertThat(report.expectFirst("#average-spending").text()).isEqualTo("55 500 ₽");
            assertThat(report.expectFirst("#year-buffer").text()).isEqualTo("−5 000 ₽");
            assertThat(report.select("#row-food td").eachText())
                    .containsExactly("288 000 ₽", "73 000 ₽", "24 000 ₽", "−2 000 ₽");
            assertThat(other.get(path).html().expectFirst("#year-spending").text()).isEqualTo("131 000 ₽");
        }
    }

    @Test
    void yearProjectsSparseMonthlyRatesWithoutRecordingMissingMonths() {
        try (var app = start(false, "2028-01")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                        ('owner-a', '2027-11', 'food', 30000, 25000),
                        ('owner-a', '2027-12', 'food', 30000, 27000),
                        ('owner-b', '2027-01', 'food', 30000, 0),
                        ('owner-b', '2027-07', 'food', 35000, 0)
                    """).update();
            var store = app.getBean(MonthStore.class);
            YearMonth january = YearMonth.of(2028, 1);
            var late = store.readYear("owner-a", 2027, january);
            assertThat(late.spendingPlan()).isEqualTo(BigInteger.valueOf(360000));
            assertThat(late.categories()).singleElement().satisfies(category -> {
                assertThat(category.plan()).isEqualTo(late.spendingPlan());
                assertThat(category.actual()).isEqualTo(BigInteger.valueOf(52000));
                assertThat(category.buffer()).isEqualTo(BigInteger.valueOf(8000));
            });
            assertThat(late.months().getFirst().actuals()).isEqualTo(YearReport.Actuals.ZERO);
            assertThat(store.readYear("owner-b", 2027, january).spendingPlan()).isEqualTo(BigInteger.valueOf(390000));
            assertThat(jdbc.sql("SELECT count(*) FROM category_month").query(Long.class).single()).isEqualTo(4L);
            assertThat(jdbc.sql("SELECT count(*) FROM month_actuals").query(Long.class).single()).isZero();
        }
    }

    @Test
    void yearUsesLatestMonthlyRateBeforeJanuaryWithoutRecordingProjections() {
        try (var app = start(false, "2028-01")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                        ('owner-a', '2026-11', 'food', 20000, 20000),
                        ('owner-a', '2026-12', 'food', 30000, 25000),
                        ('owner-a', '2027-07', 'food', 35000, 27000),
                        ('owner-b', '2026-12', 'food', 45000, 45000)
                    """).update();
            var report = app.getBean(MonthStore.class).readYear("owner-a", 2027, YearMonth.of(2028, 1));

            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(390000));
            assertThat(report.categories()).singleElement().satisfies(category -> {
                assertThat(category.plan()).isEqualTo(report.spendingPlan());
                assertThat(category.actual()).isEqualTo(BigInteger.valueOf(27000));
            });
            assertThat(report.totalBuffer()).isEqualTo(BigInteger.valueOf(13000));
            assertThat(report.months()).filteredOn(month -> month.period().getMonthValue() != 7)
                    .extracting(YearReport.Month::actuals).containsOnly(YearReport.Actuals.ZERO);
            assertThat(jdbc.sql("SELECT count(*) FROM category_month").query(Long.class).single()).isEqualTo(4L);
            assertThat(jdbc.sql("SELECT count(*) FROM month_actuals").query(Long.class).single()).isZero();
        }
    }

    @Test
    void upgradeKeepsAmbiguousLegacyZeroRateUntilOwnerCorrectsIt() throws Exception {
        var december = YearMonth.of(2027, 12);
        try (var app = startCashTest(december.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("DROP TABLE category_month_inherited_plan").update();
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual, revision) VALUES
                        ('owner-a', '2027-08', 'food', 0, 500, 1),
                        ('owner-a', '2027-11', 'food', 30000, 0, 0),
                        ('owner-a', '2027-12', 'food', 30000, 0, 0)
                    """).update();
        }
        try (var app = startCashTest(december.toString())) {
            var store = app.getBean(MonthStore.class);
            assertThat(store.readYear("owner-a", 2027, december).spendingPlan()).isEqualTo(BigInteger.valueOf(60000));
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            var form = reviewPage(mvc, "/history/2027-08").expectFirst("#history-form");
            assertThat(form.expectFirst("#plan-food").val()).isEqualTo("0");
            cashPost(mvc, form, Map.of("plan-food", "30000")).andExpect(status().isFound());
        }
        try (var app = startCashTest(december.toString())) {
            var report = app.getBean(MonthStore.class).readYear("owner-a", 2027, december);
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(360000));
            assertThat(report.categories()).singleElement().satisfies(food ->
                    assertThat(food.actual()).isEqualTo(BigInteger.valueOf(500)));
        }
    }

    @Test
    void earlierActualDoesNotMoveReservedCategoryInception() throws Exception {
        try (var app = startCashTest("2027-08")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO planning_settings VALUES ('owner-a', '2027-01', 100000, '0/1', 1),
                                                         ('owner-a', '2027-07', 100000, '0/1', 2)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                        ('owner-a', '2027-07', 'health', 'Health', 'essential', 1, 'amount', '1000', 1000)
                    """).update();
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            var form = reviewPage(mvc, "/history/2027-06").expectFirst("#history-form");
            assertThat(form.expectFirst("#plan-health").val()).isEmpty();
            cashPost(mvc, form, Map.of("amount-health", "200")).andExpect(status().isFound());
            var year = app.getBean(MonthStore.class).readYear("owner-a", 2027, YearMonth.of(2027, 8));
            assertThat(year.spendingPlan()).isEqualTo(BigInteger.valueOf(6000));
            assertThat(year.categories()).singleElement().satisfies(health -> {
                assertThat(health.plan()).isEqualTo(year.spendingPlan());
                assertThat(health.actual()).isEqualTo(BigInteger.valueOf(200));
            });
        }
    }

    @Test
    void firstSettingsSaveStartsNewCategoryInItsCreationMonth() {
        YearMonth july = YearMonth.of(2027, 7);
        try (var app = start(false, july.toString())) {
            var draft = new PlanDraft("100000", "fraction", "0/1", List.of(
                    new PlanDraft.Row("", "Books", "essential", "amount", "1000")));
            assertThat(app.getBean(PlanStore.class).save("owner-a", july, 0, PlanCalculator.evaluate(draft))).isTrue();
            var report = app.getBean(MonthStore.class).readYear("owner-a", 2027, july);
            assertThat(report.categories()).singleElement().satisfies(row -> {
                assertThat(row.plan()).isEqualTo(BigInteger.valueOf(6000));
                assertThat(row.actual()).isZero();
                assertThat(row.buffer()).isZero();
            });
            assertThat(report.months().subList(0, 6)).extracting(YearReport.Month::actuals)
                    .containsOnly(YearReport.Actuals.ZERO);
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(6000));
            var jdbc = app.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT period FROM category_month")
                    .query(String.class).single()).isEqualTo("2027-07");
            assertThat(jdbc.sql("SELECT count(*) FROM cash_movement").query(Long.class).single()).isZero();

            var store = app.getBean(MonthStore.class);
            var june = july.minusMonths(1);
            var history = store.readHistory("owner-a", june);
            String code = report.categories().getFirst().code();
            assertThat(store.replaceHistory("owner-a", june, history, 0L, 0L,
                    List.of(new MonthStore.HistoricalEdit(code, 1000, 200, true, true)), null)).isTrue();
            var updated = store.readYear("owner-a", 2027, july);
            assertThat(updated.spendingPlan()).isEqualTo(BigInteger.valueOf(6000));
            assertThat(updated.categories().getFirst().actual()).isEqualTo(BigInteger.valueOf(200));
            assertThat(updated.totalBuffer()).isEqualTo(BigInteger.valueOf(800));
        }
    }

    @Test
    void historyFormPreservesEffectiveRateForActualOnlyEntriesAndAcceptsExplicitZero() throws Exception {
        YearMonth july = YearMonth.of(2027, 7);
        YearMonth october = YearMonth.of(2027, 10);
        var draft = new PlanDraft("100000", "fraction", "0/1", List.of(
                new PlanDraft.Row("", "Books", "essential", "amount", "1000")));
        try (var app = start(false, july.toString())) {
            assertThat(app.getBean(PlanStore.class).save("owner-a", july, 0, PlanCalculator.evaluate(draft))).isTrue();
        }

        try (var app = start(false, october.toString())) {
            var owner = signedIn(app, "owner-a");
            var store = app.getBean(MonthStore.class);
            String code = store.readYear("owner-a", 2027, october).categories().getFirst().code();
            String planKey = "plan-" + code;
            String actualKey = "amount-" + code;
            String august = "/history/2027-08";
            var form = owner.get(august).html();
            assertThat(form.expectFirst("#" + planKey).val()).isEmpty();
            assertThat(owner.saveHistory(form, Map.of(actualKey, "200")).status()).isEqualTo(302);
            var report = store.readYear("owner-a", 2027, october);
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(6000));
            assertThat(report.categories()).singleElement().satisfies(row -> {
                assertThat(row.plan()).isEqualTo(report.spendingPlan());
                assertThat(row.actual()).isEqualTo(BigInteger.valueOf(200));
            });

            assertThat(owner.saveHistory(owner.get(august).html(), Map.of(planKey, "0")).status()).isEqualTo(302);
            assertThat(owner.saveHistory(owner.get("/history/2027-09").html(),
                    Map.of(actualKey, "100")).status()).isEqualTo(302);
            assertThat(store.readYear("owner-a", 2027, october).spendingPlan()).isEqualTo(BigInteger.valueOf(1000));
        }
    }

    @Test
    void laterActualSavedBeforeHistoricalRateChangeDoesNotEndThatRate() throws Exception {
        YearMonth september = YearMonth.of(2027, 9);
        try (var app = start(false, september.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("INSERT INTO planning_settings VALUES ('owner-a', '2027-01', 100000, '0/1', 1)").update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                        ('owner-a', '2027-01', 'food', 'Food', 'essential', 1, 'amount', '30000', 30000)
                    """).update();

            var owner = signedIn(app, "owner-a");
            String august = "/history/2027-08";
            String july = "/history/2027-07";
            assertThat(owner.saveHistory(owner.get(august).html(), Map.of("amount-food", "1000")).status())
                    .isEqualTo(302);
            assertThat(owner.saveHistory(owner.get(july).html(), Map.of("plan-food", "35000")).status())
                    .isEqualTo(302);

            var store = app.getBean(MonthStore.class);
            var report = store.readYear("owner-a", 2027, september);
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(390000));
            assertThat(report.categories()).singleElement().satisfies(food -> {
                assertThat(food.plan()).isEqualTo(report.spendingPlan());
                assertThat(food.actual()).isEqualTo(BigInteger.valueOf(1000));
            });

            var savedAugust = owner.get(august).html();
            assertThat(savedAugust.expectFirst("#plan-food").val()).isEmpty();
            assertThat(owner.saveHistory(savedAugust, Map.of("amount-food", "1200")).status()).isEqualTo(302);
            assertThat(store.readYear("owner-a", 2027, september).spendingPlan()).isEqualTo(BigInteger.valueOf(390000));

            assertThat(owner.saveHistory(owner.get(august).html(), Map.of("plan-food", "0")).status())
                    .isEqualTo(302);
            assertThat(store.readYear("owner-a", 2027, september).spendingPlan()).isEqualTo(BigInteger.valueOf(215000));
        }
    }

    @Test
    void historyFormBackfillsFirstLegacyRateForEarlierActual() throws Exception {
        YearMonth december = YearMonth.of(2027, 12);
        try (var app = start(false, december.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO planning_settings VALUES ('owner-a', '2027-11', 100000, '0/1', 1)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                      ('owner-a', '2027-11', 'food', 'Food', 'essential', 1, 'amount', '30000', 30000)
                    """).update();
            var owner = signedIn(app, "owner-a");
            var store = app.getBean(MonthStore.class);
            String october = "/history/2027-10";
            var form = owner.get(october).html();
            assertThat(form.expectFirst("#plan-food").val()).isEmpty();
            assertThat(owner.saveHistory(form, Map.of("amount-food", "200")).status()).isEqualTo(302);

            var report = store.readYear("owner-a", 2027, december);
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(360000));
            assertThat(report.categories()).singleElement().satisfies(row -> {
                assertThat(row.plan()).isEqualTo(report.spendingPlan());
                assertThat(row.actual()).isEqualTo(BigInteger.valueOf(200));
                assertThat(row.buffer()).isEqualTo(BigInteger.valueOf(-200));
            });
            assertThat(store.read("owner-a", december.minusMonths(1)).categories()).singleElement()
                    .satisfies(row -> assertThat(row.buffer()).isEqualTo(-200L));

            assertThat(owner.saveHistory(owner.get(october).html(), Map.of("plan-food", "0")).status())
                    .isEqualTo(302);
            assertThat(store.readYear("owner-a", 2027, december).spendingPlan())
                    .isEqualTo(BigInteger.valueOf(60000));
        }
    }

    @Test
    void sameMonthSettingsPreserveExplicitRatesAndTheHistoryForm() throws Exception {
        var january = YearMonth.of(2028, 1);
        try (var app = startCashTest("2028-02")) {
            app.getBean(JdbcClient.class).sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                        ('owner-a', '2028-01', 'food', 0, 2300), ('owner-a', '2028-01', 'health', 1000, 100)
                    """).update();
            var settings = new PlanDraft("100000", "fraction", "0/1", List.of(
                    new PlanDraft.Row("food", "Food", "essential", "amount", "30000"),
                    new PlanDraft.Row("health", "Health", "essential", "amount", "2000")));
            assertThat(app.getBean(PlanStore.class).save("owner-a", january, 0,
                    PlanCalculator.evaluate(settings))).isTrue();
            var report = app.getBean(MonthStore.class).readYear("owner-a", 2028, january.plusMonths(1));
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(12000));
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            var form = reviewPage(mvc, "/history/2028-01").expectFirst("#history-form");
            assertThat(form.expectFirst("#plan-food").val()).isEqualTo("0");
            assertThat(form.expectFirst("#plan-health").val().replace("\u00a0", "")).isEqualTo("1000");
            assertThat(form.expectFirst("#amount-food").val().replace("\u00a0", "")).isEqualTo("2300");
        }
    }

    @Test
    void savingDecemberSettingsPreservesLaterExplicitLegacyRates() {
        var december = YearMonth.of(2027, 12);
        try (var app = start(false, december.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                        ('owner-a', '2028-01', 'food', 0, 2300),
                        ('owner-a', '2028-07', 'food', 1000, 100)
                    """).update();
            var settings = new PlanDraft("100000", "fraction", "0/1", List.of(
                    new PlanDraft.Row("food", "Food", "essential", "amount", "30000")));
            assertThat(app.getBean(PlanStore.class).save("owner-a", december, 0,
                    PlanCalculator.evaluate(settings))).isTrue();
            var year = app.getBean(MonthStore.class).readYear("owner-a", 2028, december);
            assertThat(year.spendingPlan()).isEqualTo(BigInteger.valueOf(6000));
            assertThat(year.categories()).singleElement().satisfies(food ->
                    assertThat(food.actual()).isEqualTo(BigInteger.valueOf(2400)));
        }
    }

    @Test
    void januaryRolloverAndHistoryCorrectionPreserveExplicitLegacyZero() throws Exception {
        YearMonth january = YearMonth.of(2028, 1);
        try (var app = start(false, january.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("INSERT INTO planning_settings VALUES ('owner-a', '2027-12', 100000, '0/1', 1)").update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                        ('owner-a', '2027-12', 'food', 'Food', 'essential', 1, 'amount', '30000', 30000)
                    """).update();
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual)
                    VALUES ('owner-a', '2027-12', 'food', 30000, 120000),
                           ('owner-a', '2028-01', 'food', 0, 2300)
                    """).update();

            var store = app.getBean(MonthStore.class);
            assertThat(store.readYear("owner-a", 2028, january).spendingPlan()).isZero();

            var owner = signedIn(app, "owner-a");
            String december = "/history/2027-12";
            assertThat(owner.saveHistory(owner.get(december).html(), Map.of("amount-food", "144000")).status())
                    .isEqualTo(302);
            assertThat(app.getBean(PlanStore.class).read("owner-a", january).draft().rows())
                    .singleElement().satisfies(food -> assertThat(food.value()).isEqualTo("12000"));

            var report = store.readYear("owner-a", 2028, january);
            assertThat(report.categories()).singleElement().satisfies(food -> {
                assertThat(food.plan()).isZero();
                assertThat(food.actual()).isEqualTo(BigInteger.valueOf(2300));
            });
            assertThat(report.spendingPlan()).isZero();
            assertThat(jdbc.sql("""
                    SELECT plan, actual FROM category_month
                     WHERE owner = 'owner-a' AND period = '2028-01' AND category = 'food'
                    """).query((rs, i) -> List.of(rs.getLong(1), rs.getLong(2))).single())
                    .containsExactly(0L, 2300L);
        }
    }

    @Test
    void laterSettingsCannotEraseAnEarlierLegacyPlan() {
        try (var app = start(false, "2028-01")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual) VALUES
                      ('owner-a', '2027-11', 'food', 30000, 100), ('owner-a', '2027-12', 'food', 30000, 150)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_settings VALUES
                      ('owner-a', '2027-05', 100000, '0/1', 1), ('owner-a', '2028-01', 100000, '0/1', 2)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                      ('owner-a', '2028-01', 'food', 'Food', 'essential', 1, 'amount', '32000', 32000)
                    """).update();
            var report = app.getBean(MonthStore.class).readYear("owner-a", 2027, YearMonth.of(2028, 1));
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(360000));
        }
    }

    @Test
    void projectsRatesThroughTheLastSupportedDecember() {
        try (var app = start(false, "2027-03")) {
            app.getBean(JdbcClient.class).sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual)
                    VALUES ('owner-a', '9999-11', 'food', 30000, 0), ('owner-a', '9999-12', 'food', 35000, 0)
                    """).update();
            var report = app.getBean(MonthStore.class).readYear("owner-a", 9999, YearMonth.of(2027, 3));
            assertThat(report.spendingPlan()).isEqualTo(BigInteger.valueOf(365000));
        }
    }

    @Test
    void historicalRateSupersedesOlderSettingsUntilTheNextDatedRate() throws Exception {
        try (var app = startCashTest("2027-08")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO planning_settings VALUES ('owner-a', '2027-01', 100000, '0/1', 1)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_category VALUES
                        ('owner-a', '2027-01', 'food', 'Food', 'essential', 1, 'amount', '30000', 30000)
                    """).update();
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual)
                    VALUES ('owner-a', '2027-07', 'food', 35000, 27000)
                    """).update();
            var year = app.getBean(MonthStore.class).readYear("owner-a", 2027, YearMonth.of(2027, 12));
            assertThat(year.spendingPlan()).isEqualTo(BigInteger.valueOf(390000));

            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            var form = reviewPage(mvc, "/months/2027-08?edit=true");
            var fields = monthFields(form);
            fields.put("amount-food", "1000");
            var request = post("/months/2027-08").principal(() -> "owner-a");
            fields.forEach(request::param);
            mvc.perform(request).andExpect(status().isFound());
            assertThat(jdbc.sql("SELECT plan FROM category_month WHERE owner = 'owner-a' AND period = '2027-08'")
                    .query(Long.class).single()).isEqualTo(35000L);
            assertThat(app.getBean(MonthStore.class).readYear("owner-a", 2027, YearMonth.of(2027, 8)).spendingPlan())
                    .isEqualTo(BigInteger.valueOf(390000));
        }
    }

    @Test
    void monthlyCategoryRateContinuesAcrossAnotherCategorySettingsAndJanuary() {
        YearMonth december = YearMonth.of(2027, 12);
        try (var app = start(false, december.toString())) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO category_month (owner, period, category, plan, actual)
                    VALUES ('owner-a', '2027-11', 'food', 30000, 24000)
                    """).update();
            var draft = new PlanDraft("100000", "fraction", "0/1", List.of(
                    new PlanDraft.Row("", "Books", "essential", "amount", "1000")));
            assertThat(app.getBean(PlanStore.class).save("owner-a", december, 0, PlanCalculator.evaluate(draft))).isTrue();
            var store = app.getBean(MonthStore.class);
            var year = store.readYear("owner-a", 2027, december);
            assertThat(year.spendingPlan()).isEqualTo(BigInteger.valueOf(361000));
            assertThat(year.categories()).filteredOn(row -> row.code().equals("food")).singleElement()
                    .satisfies(food -> assertThat(food.plan()).isEqualTo(BigInteger.valueOf(360000)));
            var next = store.readYear("owner-a", 2028, december.plusMonths(1));
            assertThat(next.spendingPlan()).isEqualTo(BigInteger.valueOf(372000));
            assertThat(jdbc.sql("SELECT count(*) FROM category_month").query(Integer.class).single()).isEqualTo(2);
        }
    }

    @Test
    void yearUsesEffectiveSettingsAndRespectsCategoryInception() {
        try (var app = start(false, "2028-01")) {
            var jdbc = app.getBean(JdbcClient.class);
            jdbc.sql("""
                    INSERT INTO planning_settings (owner, since, income, target, revision) VALUES
                        ('owner-a', '2027-03', 100000, '2/5', 1),
                        ('owner-a', '2027-07', 100000, '2/5', 2)
                    """).update();
            jdbc.sql("""
                    INSERT INTO planning_category (owner, since, category, name, kind, addition_order, unit, value, plan)
                    VALUES
                        ('owner-a', '2027-03', 'food', 'Продукты', 'essential', 1, 'amount', '30000', 30000),
                        ('owner-a', '2027-07', 'food', 'Продукты', 'essential', 1, 'amount', '35000', 35000),
                        ('owner-a', '2027-07', 'health', 'Здоровье', 'essential', 2, 'amount', '1000', 1000)
                    """).update();
            var store = app.getBean(MonthStore.class);
            YearMonth january = YearMonth.of(2028, 1);
            var previous = store.readYear("owner-a", 2027, january);
            assertThat(previous.categories()).extracting(YearReport.Category::plan)
                    .containsExactly(BigInteger.valueOf(390000), BigInteger.valueOf(6000));
            assertThat(previous.spendingPlan()).isEqualTo(BigInteger.valueOf(396000));
            var rollover = store.readYear("owner-a", 2028, january);
            assertThat(rollover.spendingPlan()).isEqualTo(BigInteger.valueOf(432000));
            assertThat(rollover.totalBuffer()).isZero();
        }
    }

    @Test
    void pastYearCountsMissingMonthsAndJanuaryHasNoAverage() throws Exception {
        try (var app = start(true, "2028-01")) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.get("/years?year=2027").status()).isEqualTo(302);
            assertThat(owner.get("/years/0").status()).isEqualTo(400);
            Document previous = owner.get("/years/2027").html();
            assertThat(previous.expectFirst("#average-period").text()).isEqualTo("Январь — декабрь 2027");
            assertThat(previous.expectFirst("#year-spending").text()).isEqualTo("131 000 ₽");
            assertThat(previous.select("#average-income, #average-spending, #average-contributions").eachText())
                    .containsExactly("20 833 ₽", "10 917 ₽", "7 500 ₽");
            assertThat(previous.expectFirst("#buffer-cutoff").text()).contains("1 января 2028 года");
            assertThat(previous.expectFirst("#year-buffer").text()).isEqualTo("35 000 ₽");
            assertThat(previous.expectFirst("#row-food .buffer").text()).isEqualTo("16 000 ₽");

            Document january = owner.get("/years/2028").html();
            assertThat(january.expectFirst("#average-period").text()).isEqualTo("Нет завершённых месяцев");
            assertThat(january.select("#average-income, #average-spending, #average-contributions")).isEmpty();
            assertThat(january.expectFirst("#row-food .average").text()).isEqualTo("—");
            assertThat(january.expectFirst("#year-buffer").text()).isEqualTo("35 000 ₽");
        }
    }

    @Test
    void editingSelectedPeriodsChangesOnlyTheirActualsAndLaterOpeningBuffers() throws Exception {
        try (var app = start()) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.get("/months?year=2027&month=1").status()).isEqualTo(302);
            String january = "/months/2027-01";
            String february = "/months/2027-02";
            Document form = owner.get(january + "?edit=true").html();
            assertThat(form.expectFirst("h1").text()).isEqualTo("Январь 2027");
            assertThat(form.expectFirst("#actual-income").text()).isEqualTo("98 000 ₽");
            assertThat(owner.get(february).html().expectFirst("#row-food .buffer").text()).isEqualTo("−3 000 ₽");
            assertThat(owner.save(form, Map.of("amount-food", "28 000", "income", "87 000")).status()).isEqualTo(302);
            var januarySaved = owner.get(january).html();
            assertThat(januarySaved.expectFirst("#row-food .buffer").text()).isEqualTo("−2 000 ₽");
            assertThat(januarySaved.expectFirst("#row-food .remainder").text()).isEqualTo("−4 000 ₽");
            var februarySaved = owner.get(february).html();
            assertThat(februarySaved.expectFirst("#row-food .buffer").text()).isEqualTo("−6 000 ₽");
            assertThat(februarySaved.expectFirst("#row-food .plan").text()).isEqualTo("24 000 ₽");
            assertThat(februarySaved.expectFirst("#actual-income").text()).isEqualTo("102 000 ₽");

            String availableLabel = "Доступно с учётом буфера";
            assertThat(februarySaved.select("thead th").eachText()).contains(availableLabel);
            var travel = februarySaved.expectFirst("#row-travel");
            assertThat(travel.expectFirst(".plan").text()).isEqualTo("8 000 ₽");
            assertThat(travel.expectFirst(".actual").text()).isEqualTo("6 000 ₽");
            assertThat(travel.expectFirst(".remainder").text()).isEqualTo("2 000 ₽");
            assertThat(travel.expectFirst(".buffer").text()).isEqualTo("3 000 ₽");
            assertThat(travel.expectFirst(".available").attr("data-label")).isEqualTo(availableLabel);
            assertThat(travel.expectFirst(".available").text()).isEqualTo("5 000 ₽");

            var nextYear = owner.get("/months/2028-01?edit=true").html();
            assertThat(nextYear.expectFirst("#actual-income").text()).isEqualTo("0 ₽");
            assertThat(owner.save(nextYear, Map.of("contributions", "7", "amount-travel", "501")).status()).isEqualTo(302);
            var reopened = owner.get("/months/2028-01").html();
            assertThat(reopened.expectFirst("#row-travel .actual").text()).isEqualTo("501 ₽");
            assertThat(reopened.expectFirst("#actual-contributions").text()).isEqualTo("7 ₽");
        }
    }

    @Test
    void changedPlanningIncomeRequiresReviewBeforeSavingAPercentage() throws Exception {
        try (var app = start()) {
            var owner = signedIn(app, "owner-a");
            Document oldForm = owner.get(EDIT).html();
            Document settings = owner.get("/settings").html();
            var plan = settingsDraft(settings);
            assertThat(owner.plan(settings, new PlanDraft("120000", plan.targetUnit(), plan.targetValue(), plan.rows()))
                    .status()).isEqualTo(204);
            Page conflict = owner.save(oldForm, Map.of("unit-food", "percent", "percent-food", "25"));
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.html().expectFirst("#percent-food").val()).isEqualTo("25");
            assertThat(conflict.html().expectFirst("#amount-food").val()).isEqualTo("30 000");
            assertThat(owner.get(MARCH).html().expectFirst("#row-food .actual").text()).isEqualTo("6 000 ₽");
            assertThat(owner.save(conflict.html(), Map.of()).status()).isEqualTo(302);
            assertThat(owner.get(MARCH).html().expectFirst("#row-food .actual").text()).isEqualTo("30 000 ₽");
        }
    }

    @Test
    void fractionalDraftStaysEditableAndCannotBypassAConflictingSave() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            Document oldForm = phone.get(EDIT).html();
            var incomplete = monthFields(oldForm);
            incomplete.remove("amount-food");
            Page missing = phone.post(MARCH, incomplete);
            assertThat(missing.status()).isEqualTo(422);
            assertThat(missing.html().expectFirst("#error-amount-food").text()).contains("целых рублях");
            assertThat(missing.html().expectFirst("#amount-food").val()).isEmpty();
            assertMonth(missing.html(), "6 000", "20 000", "18 000");

            Page invalid = phone.save(oldForm, "1 234,50");
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.html().expectFirst("#amount-food").val()).isEqualTo("1 234,50");
            assertThat(invalid.html().expectFirst("#error-amount-food").text()).contains("целых рублях");
            assertMonth(desktop.get(MARCH).html(), "6 000", "20 000", "18 000");

            assertThat(desktop.save(desktop.get(EDIT).html(), "25 000").status()).isEqualTo(302);
            Page stillInvalid = phone.save(invalid.html(), "1 234,50");
            assertThat(revision(stillInvalid.html())).isEqualTo(revision(oldForm));
            Page conflict = phone.save(stillInvalid.html(), "28 177");
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.html().expectFirst("#save-conflict").text()).contains("Месяц уже изменён");
            assertMonth(conflict.html(), "25 000", "39 000", "−1 000");
            assertThat(conflict.html().expectFirst("#amount-food").val()).isEqualTo("28 177");
            assertThat(conflict.html().select("#save-success")).isEmpty();
            assertMonth(desktop.get(MARCH).html(), "25 000", "39 000", "−1 000");

            assertThat(phone.save(conflict.html(), "28 177").status()).isEqualTo(302);
            assertMonth(desktop.get(MARCH).html(), "28 177", "42 177", "−4 177");
        }
    }

    @Test
    void directMonthRequestsUseOnlyTheAuthenticatedOwner() throws Exception {
        try (var app = start()) {
            var guest = new Browser(app);
            assertThat(guest.get(MARCH).status()).isEqualTo(302);
            assertThat(guest.post(MARCH, Map.of("amount-food", "42", "revision", "0")).status()).isEqualTo(403);
            var first = signedIn(app, "owner-a");
            var second = signedIn(app, "owner-b");
            assertThat(first.save(first.get(EDIT).html(), "25 000").status()).isEqualTo(302);
            Document direct = second.get(EDIT + "&owner=owner-a").html();
            assertMonth(direct, "6 000", "20 000", "18 000");
            assertThat(second.save(direct, Map.of("owner", "owner-a", "amount-food", "7 500,00")).status()).isEqualTo(302);
            assertMonth(second.get(MARCH).html(), "7 500", "21 500", "16 500");
            assertMonth(first.get(MARCH).html(), "25 000", "39 000", "−1 000");
            assertThat(second.get("/owners/owner-a" + MARCH).status()).isEqualTo(404);
        }
    }

    @Test
    void settingsChangeOnlyThisOwnerFromMarchOnwardAndRejectInvalidOrStaleReplacements() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            var other = signedIn(app, "owner-b");
            Document form = desktop.get("/settings").html();
            Document phoneForm = phone.get("/settings").html();
            PlanDraft draft = settingsDraft(form);
            assertThat(draft.rows()).extracting(PlanDraft.Row::name)
                    .containsExactly("Жильё", "Продукты", "Здоровье", "Развлечения", "Путешествия");
            var invalid = new java.util.ArrayList<>(draft.rows());
            invalid.set(4, invalid.get(4).withValue("poolPercent", "50"));
            Page rejected = desktop.plan(form, new PlanDraft("100000", "incomePercent", "40", invalid));
            assertThat(rejected.status()).isEqualTo(422);
            assertThat(rejected.html().text()).contains("110 %", "10 %");
            assertThat(desktop.plan(form, new PlanDraft("1 234,50", "incomePercent", "40", draft.rows())).status())
                    .isEqualTo(422);
            assertAllocations(phone.get(MARCH).html(), "40 000", "20 000");
            PlanDraft raised = new PlanDraft("120000", draft.targetUnit(), draft.targetValue(), draft.rows());
            assertThat(desktop.plan(form, raised).status()).isEqualTo(204);
            assertThat(phone.plan(phoneForm, draft).status()).isEqualTo(409);
            for (String past : List.of("2027-01", "2027-02")) {
                Document month = phone.get("/months/" + past).html();
                assertAllocations(month, "40 000", "20 000");
                assertThat(month.expectFirst("#row-leisure .plan").wholeText()).isEqualTo("12 000 ₽");
            }
            for (String period : List.of("2027-03", "2027-04", "2028-01")) {
                Document month = phone.get("/months/" + period).html();
                assertAllocations(month, "48 000", "32 000");
                assertThat(month.expectFirst("#row-leisure .plan").wholeText()).isEqualTo("19 200 ₽");
                assertThat(month.expectFirst("#row-food .buffer").wholeText()).isEqualTo("−2 000 ₽");
            }
            assertMonth(phone.get(MARCH).html(), "6 000", "20 000", "18 000");
            assertAllocations(other.get(MARCH).html(), "40 000", "20 000");
            Document report = phone.get("/years/2027").html();
            assertThat(report.expectFirst("#year-planned-spending").text()).isEqualTo("840 000 ₽");
            assertThat(report.expectFirst("#year-planned-investments").text()).isEqualTo("560 000 ₽");
            Document otherReport = other.get("/years/2027").html();
            assertThat(otherReport.expectFirst("#year-planned-spending").text()).isEqualTo("720 000 ₽");
            assertThat(otherReport.expectFirst("#year-planned-investments").text()).isEqualTo("480 000 ₽");
        }
    }

    @Test
    void emptyOwnerCanSetUpRenameRegroupAndReopenWithoutHistory() throws Exception {
        String first;
        String second;
        try (var app = start(false)) {
            var owner = signedIn(app, "owner-a");
            Document empty = owner.get(MARCH).html();
            assertThat(empty.text()).contains("План пока не задан");
            assertThat(empty.expectFirst("#spending").wholeText()).isEqualTo("0 ₽");
            Document form = owner.get("/settings").html();
            assertThat(settingsDraft(form).rows()).isEmpty();
            var a = new PlanDraft.Row("", "А", "discretionary", "poolPercent", "50");
            var b = new PlanDraft.Row("", "Б", "discretionary", "poolPercent", "50");
            assertThat(owner.plan(form, new PlanDraft("7", "incomePercent", "0", List.of(a, b))).status()).isEqualTo(204);
            form = owner.get("/settings").html();
            var entries = settingsDraft(form).rows();
            first = entries.get(0).code();
            second = entries.get(1).code();
            assertThat(first).isNotBlank().isNotEqualTo(second);
            assertThat(owner.plan(form, new PlanDraft("7", "incomePercent", "0", List.of(
                    new PlanDraft.Row(first, "Я", "discretionary", "fraction", "1/2"), entries.get(1)))).status())
                    .isEqualTo(204);
        }
        try (var app = start(false)) {
            var owner = signedIn(app, "owner-a");
            Document month = owner.get(MARCH).html();
            assertThat(month.select(".category-name").eachText()).containsExactly("Я", "Б");
            assertThat(month.select(".plan").eachText()).containsExactly("4 ₽", "3 ₽");
            assertThat(month.select(".buffer, .actual").eachText()).containsOnly("0 ₽");
            Document form = owner.get("/settings").html();
            assertThat(settingsDraft(form).rows()).extracting(PlanDraft.Row::code).containsExactly(first, second);
            var regrouped = new PlanDraft("101", "incomePercent", "50", List.of(
                    new PlanDraft.Row(first, "Я", "essential", "annual", "366"),
                    new PlanDraft.Row(second, "Б", "discretionary", "poolPercent", "60"),
                    new PlanDraft.Row("", "В", "discretionary", "poolPercent", "40")));
            var other = signedIn(app, "owner-b");
            assertThat(other.plan(other.get("/settings").html(), regrouped).status()).isEqualTo(422);
            assertThat(owner.plan(form, regrouped).status()).isEqualTo(204);
            month = owner.get(MARCH).html();
            assertThat(month.select(".plan").eachText()).containsExactly("31 ₽", "11 ₽", "8 ₽");
            assertAllocations(month, "51", "19");
            assertThat(owner.save(owner.get(EDIT).html(), Map.of("amount-" + first, "9")).status()).isEqualTo(302);
            assertThat(owner.get(MARCH).html().select(".actual").eachText()).containsExactly("9 ₽", "0 ₽", "0 ₽");
            assertThat(owner.get("/months/2027-02").html().select("tbody tr")).isEmpty();
            assertThat(other.get(MARCH).html().select("tbody tr")).isEmpty();
        }
        try (var demo = start()) {
            var existingOwner = signedIn(demo, "owner-a");
            assertThat(existingOwner.get("/months/2027-02").html().select("tbody tr")).isEmpty();
            assertThat(existingOwner.get(MARCH).html().select(".actual").eachText()).containsExactly("9 ₽", "0 ₽", "0 ₽");
            assertMonth(signedIn(demo, "owner-b").get(MARCH).html(), "6 000", "20 000", "18 000");
        }
    }

    @Test
    void correctingPreviousYearRecalculatesCurrentAndFuturePlansAndKeepsPastSnapshots() throws Exception {
        String december = "/history/2026-12";
        try (var app = start()) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.get(MARCH).html().expectFirst("aside a[href^=/history]").text()).isEqualTo("Ввод истории");
            assertThat(owner.get("/history?year=2027&month=3").status()).isEqualTo(400);
            assertThat(owner.get("/history?year=2026&month=12").status()).isEqualTo(302);
            assertFood(owner.get("/months/2027-01").html(), "24 000", "−2 000");
            assertFood(owner.get("/months/2027-02").html(), "24 000", "−3 000");
            assertFood(owner.get(MARCH).html(), "24 000", "−2 000");

            Document draft = owner.get(december).html();
            assertThat(draft.expectFirst("#plan-food").val()).isEqualTo("22 000");
            assertThat(draft.expectFirst("#amount-food").val()).isEqualTo("24 000");
            assertThat(draft.select(".history-share").eachText()).hasSize(10).containsOnly("—");
            assertThat(draft.expectFirst("#income").val()).isEqualTo("0");
            assertThat(owner.saveHistory(draft, Map.of("amount-food", "36 000", "contributions", "2500")).status())
                    .isEqualTo(302);
            assertThat(owner.saveHistory(draft, Map.of("amount-food", "48000")).status()).isEqualTo(409);
            var oldMonth = owner.get("/months/2026-12").html();
            assertThat(oldMonth.expectFirst("#actual-income").text()).isEqualTo("0 ₽");
            assertThat(oldMonth.expectFirst("#actual-contributions").text()).isEqualTo("2 500 ₽");
            assertThat(oldMonth.expectFirst("#row-food .plan").text()).isEqualTo("22 000 ₽");

            for (String path : List.of(MARCH, "/months/2027-04", "/months/2028-01")) {
                var month = owner.get(path).html();
                assertFood(month, "25 000", "−14 000");
                assertThat(month.expectFirst("#plan-needs").text()).isEqualTo("41 000 ₽");
                assertThat(month.expectFirst("#row-housing .plan").text()).isEqualTo("13 000 ₽");
                assertThat(month.expectFirst("#row-health .plan").text()).isEqualTo("3 000 ₽");
                assertThat(month.expectFirst("#row-leisure .plan").text()).isEqualTo("11 400 ₽");
                assertThat(month.expectFirst("#row-travel .plan").text()).isEqualTo("7 600 ₽");
            }
            assertFood(owner.get("/months/2027-01").html(), "24 000", "−14 000");
            assertFood(owner.get("/months/2027-02").html(), "24 000", "−15 000");
            Document updatedReport = owner.get("/years/2027").html();
            assertThat(updatedReport.select("#row-food td").eachText())
                    .containsExactly("298 000 ₽", "54 000 ₽", "24 000 ₽", "−14 000 ₽");
            assertFood(signedIn(app, "owner-b").get(MARCH).html(), "24 000", "−2 000");
        }
        try (var app = start()) {
            var owner = signedIn(app, "owner-a");
            assertFood(owner.get(MARCH).html(), "25 000", "−14 000");
            assertThat(owner.get(december).html().expectFirst("#amount-food").val()).isEqualTo("36 000");
        }
    }

    @Test
    void firstUseInJanuaryRefreshesThePlanBeforeEitherBudgetView() throws Exception {
        String december = "/months/2027-12";
        String january = "/months/2028-01";
        String february = "/months/2028-02";
        String report = "/years/2028";
        var categories = new HashMap<String, List<PlanDraft.Row>>();
        var draft = new PlanDraft("60000", "incomePercent", "40", List.of(
                new PlanDraft.Row("", "Продукты", "essential", "amount", "10000"),
                new PlanDraft.Row("", "Транспорт", "essential", "amount", "1000"),
                new PlanDraft.Row("", "Здоровье", "essential", "amount", "1500"),
                new PlanDraft.Row("", "Досуг", "discretionary", "poolPercent", "100")));
        try (var app = start(false, "2027-12")) {
            for (String name : List.of("owner-a", "owner-b")) {
                var owner = signedIn(app, name);
                assertThat(owner.plan(owner.get("/settings").html(), draft).status()).isEqualTo(204);
                var rows = settingsDraft(owner.get("/settings").html()).rows();
                categories.put(name, rows);
                String food = rows.getFirst().code();
                String transport = rows.get(1).code();
                assertThat(owner.saveHistory(owner.get("/history/2027-01").html(),
                        Map.of("plan-" + food, "132000", "amount-" + food, "132000")).status()).isEqualTo(302);
                Document edit = owner.get(december + "?edit=true").html();
                assertThat(edit.expectFirst("#row-" + food + " .plan").text()).isEqualTo("10 000 ₽");
                assertThat(owner.save(edit, Map.of("income", "55000", "amount-" + food, "12000",
                        "amount-" + transport, "12006")).status()).isEqualTo(302);
            }
        }

        String food = categories.get("owner-a").getFirst().code();
        String foodRow = "#row-" + food;
        String januaryRevision;
        try (var app = start(false, "2028-01")) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.get(report).html().expectFirst(foodRow + " .plan").text()).isEqualTo("144 000 ₽");
            Document januaryMonth = owner.get(january + "?edit=true").html();
            assertThat(januaryMonth.select(".plan").eachText())
                    .containsExactly("12 000 ₽", "1 001 ₽", "1 500 ₽", "21 499 ₽");
            assertThat(januaryMonth.expectFirst("#plan-income").text()).isEqualTo("60 000 ₽");
            assertThat(januaryMonth.expectFirst(foodRow + " .buffer").text()).isEqualTo("−2 000 ₽");
            januaryRevision = januaryMonth.expectFirst("input[name=planRevision]").val();

            var other = signedIn(app, "owner-b");
            String otherFoodRow = "#row-" + categories.get("owner-b").getFirst().code();
            assertThat(other.get(january).html().expectFirst(otherFoodRow + " .plan").text()).isEqualTo("12 000 ₽");
            assertThat(other.get(report).html().expectFirst(otherFoodRow + " .plan").text()).isEqualTo("144 000 ₽");
        }

        try (var app = start(false, "2028-02")) {
            var owner = signedIn(app, "owner-a");
            Document reopened = owner.get(january + "?edit=true").html();
            assertThat(reopened.select(".plan").eachText())
                    .containsExactly("12 000 ₽", "1 001 ₽", "1 500 ₽", "21 499 ₽");
            assertThat(reopened.expectFirst("input[name=planRevision]").val()).isEqualTo(januaryRevision);
            Document savedDecember = owner.get(december).html();
            assertThat(savedDecember.expectFirst(foodRow + " .plan").text()).isEqualTo("10 000 ₽");
            assertThat(savedDecember.expectFirst(foodRow + " .actual").text()).isEqualTo("12 000 ₽");
            assertThat(savedDecember.expectFirst("#actual-income").text()).isEqualTo("55 000 ₽");
            assertThat(reopened.expectFirst(foodRow + " .buffer").text()).isEqualTo("−2 000 ₽");
            assertThat(owner.get(february).html().expectFirst(foodRow + " .buffer").text()).isEqualTo("−2 000 ₽");

            String transport = categories.get("owner-a").get(1).code();
            assertThat(owner.saveHistory(owner.get("/history/2027-12").html(),
                    Map.of("amount-" + food, "24000", "amount-" + transport, "0")).status()).isEqualTo(302);
            assertThat(owner.get(february).html().select(".plan").eachText())
                    .containsExactly("13 000 ₽", "0 ₽", "1 500 ₽", "21 500 ₽");
            assertThat(owner.get(january).html().expectFirst(foodRow + " .plan").text()).isEqualTo("12 000 ₽");
        }
    }

    @Test
    void savedPriorYearExpensesRecalculateAllocationAndPreserveHistoricalActuals() throws Exception {
        try (var app = start(false)) {
            var owner = signedIn(app, "owner-a");
            var draft = new PlanDraft("101", "incomePercent", "50", List.of(
                    new PlanDraft.Row("", "Продукты", "essential", "amount", "40"),
                    new PlanDraft.Row("", "Досуг", "discretionary", "poolPercent", "60"),
                    new PlanDraft.Row("", "Поездки", "discretionary", "poolPercent", "40")));
            assertThat(owner.plan(owner.get("/settings").html(), draft).status()).isEqualTo(204);
            String food = settingsDraft(owner.get("/settings").html()).rows().getFirst().code();
            String december = "/history/2026-12";
            assertThat(owner.get(MARCH).html().expectFirst("#row-" + food + " .plan").text()).isEqualTo("40 ₽");
            assertThat(owner.saveHistory(owner.get(december).html(), Map.of("amount-" + food, "366")).status())
                    .isEqualTo(302);

            Document march = owner.get(MARCH).html();
            assertThat(march.expectFirst("#plan-income").text()).isEqualTo("101 ₽");
            assertThat(march.select(".plan").eachText()).containsExactly("31 ₽", "11 ₽", "8 ₽");
            assertAllocations(march, "51", "19");
            assertThat(owner.get("/months/2026-12").html().expectFirst("#row-" + food + " .actual").text())
                    .isEqualTo("366 ₽");
        }
    }

    @Test
    void explicitlySavedZeroExpensesCountAtRolloverAndAfterHistorySave() throws Exception {
        var draft = new PlanDraft("10000", "incomePercent", "40", List.of(
                new PlanDraft.Row("", "Транспорт", "essential", "amount", "1000"),
                new PlanDraft.Row("", "Здоровье", "essential", "amount", "1500"),
                new PlanDraft.Row("", "Досуг", "discretionary", "poolPercent", "100")));
        String health;
        try (var app = start(false, "2027-12")) {
            var owner = signedIn(app, "owner-a");
            assertThat(owner.plan(owner.get("/settings").html(), draft).status()).isEqualTo(204);
            var rows = settingsDraft(owner.get("/settings").html()).rows();
            String transport = rows.getFirst().code();
            health = rows.get(1).code();
            assertThat(owner.saveHistory(owner.get("/history/2027-01").html(),
                    Map.of("amount-" + transport, "0", "plan-" + health, "1500")).status()).isEqualTo(302);
        }

        try (var app = start(false, "2028-01")) {
            var owner = signedIn(app, "owner-a");
            String january = "/months/2028-01";
            assertThat(owner.get(january).html().select(".plan").eachText())
                    .containsExactly("0 ₽", "1 500 ₽", "4 500 ₽");
            assertThat(owner.saveHistory(owner.get("/history/2027-02").html(),
                    Map.of("amount-" + health, "0")).status()).isEqualTo(302);
            assertThat(owner.get(january).html().select(".plan").eachText())
                    .containsExactly("0 ₽", "0 ₽", "6 000 ₽");
        }
    }

    @Test
    void partialBackfillCommitsEachMonthAndRollsBackAnUnsuccessfulRecalculation() throws Exception {
        try (var app = start(false)) {
            var owner = signedIn(app, "owner-a");
            Document settings = owner.get("/settings").html();
            var food = new PlanDraft.Row("", "Продукты", "essential", "amount", "4000");
            var health = new PlanDraft.Row("", "Здоровье", "essential", "amount", "1000");
            var leisure = new PlanDraft.Row("", "Досуг", "discretionary", "poolPercent", "100");
            assertThat(owner.plan(settings, new PlanDraft("10000", "incomePercent", "40", List.of(food, health, leisure)))
                    .status()).isEqualTo(204);
            String code = settingsDraft(owner.get("/settings").html()).rows().getFirst().code();
            String planKey = "plan-" + code;
            String actualKey = "amount-" + code;
            String row = "#row-" + code;
            String january = "/history/2026-01";
            String december = "/history/2026-12";

            assertThat(owner.saveHistory(owner.get(january).html(), Map.of(planKey, "10000", actualKey, "12 006")).status())
                    .isEqualTo(302);
            Document marchAfterJanuary = owner.get(MARCH).html();
            assertThat(marchAfterJanuary.expectFirst(row + " .plan").text()).isEqualTo("1 001 ₽");
            assertThat(marchAfterJanuary.expectFirst(row + " .buffer").text()).isEqualTo("−2 006 ₽");
            assertThat(marchAfterJanuary.select(".plan").eachText()).containsExactly("1 001 ₽", "1 000 ₽", "3 999 ₽");

            Document draft = owner.get(december).html();
            var rejected = owner.saveHistory(draft, Map.of(planKey, "10000", actualKey, "23 994,50", "income", "60000"));
            assertThat(rejected.status()).isEqualTo(422);
            assertThat(rejected.html().expectFirst("#" + actualKey).val()).isEqualTo("23 994,50");

            var jdbc = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            jdbc.sql("""
                    CREATE TRIGGER reject_recalculation BEFORE UPDATE OF plan ON category_month
                    WHEN NEW.period = '2027-03' AND NEW.plan <> OLD.plan
                    BEGIN SELECT RAISE(ABORT, 'fixture failure'); END
                    """).update();
            var decemberAmounts = Map.of(planKey, "10000", actualKey, "23994", "income", "60000");
            assertThat(owner.saveHistory(draft, decemberAmounts).status()).isEqualTo(500);
            assertThat(owner.get(december).html().expectFirst("#income").val()).isEqualTo("0");
            assertThat(owner.get(december).html().expectFirst("#" + planKey).val()).isEmpty();
            assertThat(owner.get(MARCH).html().expectFirst(row + " .plan").text()).isEqualTo("1 001 ₽");
            assertThat(owner.get(MARCH).html().expectFirst(row + " .buffer").text()).isEqualTo("−2 006 ₽");

            jdbc.sql("DROP TRIGGER reject_recalculation").update();
            assertThat(owner.saveHistory(draft, decemberAmounts).status()).isEqualTo(302);
            assertThat(owner.get(MARCH).html().select(".plan").eachText()).containsExactly("3 000 ₽", "1 000 ₽", "2 000 ₽");
            assertThat(owner.get(MARCH).html().expectFirst(row + " .buffer").text()).isEqualTo("−16 000 ₽");
            Document saved = owner.get(december).html();
            assertThat(saved.expectFirst(row + " [data-history-share^=amount]").text()).isEqualTo("39,99 %");

            assertThat(owner.saveHistory(saved, Map.of(planKey, "11000")).status()).isEqualTo(302);
            assertThat(owner.get("/months/2026-12").html().expectFirst(row + " .plan").text()).isEqualTo("11 000 ₽");
            assertThat(owner.get(january).html().expectFirst("#" + planKey).val()).isEqualTo("10 000");
            var future = owner.get("/months/2028-01").html();
            assertThat(future.expectFirst(row + " .plan").text()).isEqualTo("3 000 ₽");
            assertThat(future.expectFirst(row + " .buffer").text()).isEqualTo("−15 000 ₽");
        }
    }

    private static void assertFood(Document month, String plan, String buffer) {
        assertThat(month.expectFirst("#row-food .plan").text()).isEqualTo(plan + " ₽");
        assertThat(month.expectFirst("#row-food .buffer").text()).isEqualTo(buffer + " ₽");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static PlanDraft settingsDraft(Document page) {
        String initial = page.expectFirst("#plan-form").attr("data-initial");
        return JSON.readValue(initial, PlanCalculator.Preview.class).draft();
    }

    private static void assertAllocations(Document month, String investments, String discretionary) {
        assertThat(month.expectFirst("#plan-investments").wholeText()).isEqualTo(investments + " ₽");
        assertThat(month.expectFirst("#plan-pool").wholeText()).isEqualTo(discretionary + " ₽");
    }

    private ConfigurableApplicationContext start() {
        return start(true);
    }

    private ConfigurableApplicationContext start(boolean demo) {
        return start(demo, "2027-03");
    }

    private ConfigurableApplicationContext start(boolean demo, String currentMonth) {
        var builder = new SpringApplicationBuilder(FinanceLabApplication.class);
        builder.profiles(demo ? "demo" : "fixture");
        return builder.run("--budget.current-month=" + currentMonth,
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("budget.sqlite"),
                "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private ConfigurableApplicationContext startPilot(String password) {
        return new SpringApplicationBuilder(FinanceLabApplication.class).run(
                "--budget.current-month=2027-03", "--server.port=0", "--server.address=127.0.0.1",
                "--BUDGET_DB=" + directory.resolve("pilot.sqlite"),
                "--BUDGET_PILOT_PASSWORD_HASH=" + new BCryptPasswordEncoder().encode(password),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    static Browser signedIn(ConfigurableApplicationContext app, String name) throws Exception {
        var browser = new Browser(app);
        browser.login(name, "fixture-" + name);
        return browser;
    }

    private static void assertMonth(Document html, String food, String spending, String remainder) {
        assertThat(html.expectFirst("#row-food .actual").wholeText()).isEqualTo(food + " ₽");
        assertThat(html.expectFirst("#spending").wholeText()).isEqualTo(spending + " ₽");
        assertThat(html.expectFirst("#row-food .remainder").wholeText()).isEqualTo(remainder + " ₽");
        assertThat(html.expectFirst("#row-food .plan").wholeText()).isEqualTo("24 000 ₽");
        assertThat(html.expectFirst("#row-food .buffer").wholeText()).isEqualTo("−2 000 ₽");
    }

    private static Document reviewPage(MockMvc mvc, String path) throws Exception {
        return Jsoup.parse(mvc.perform(get(path).principal(() -> "owner-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertInvestmentFigures(Element account, String closingValue, String creditedIncome) {
        assertThat(account.expectFirst("input[name=closingValue]").val()).isEqualTo(closingValue);
        assertThat(account.expectFirst("input[name=creditedIncome]").val()).isEqualTo(creditedIncome);
    }

    private static String csrf(Document html) {
        return html.expectFirst("input[name=_csrf]").val();
    }

    private static String revision(Document html) {
        return html.expectFirst("input[name=revision]").val();
    }

    private static Map<String, String> monthFields(Document page) {
        Map<String, String> fields = new HashMap<>();
        for (var input : page.select("#month-form input[name]:not([disabled])")) {
            fields.put(input.attr("name"), input.val());
        }
        return fields;
    }

    record Page(int status, Document html) { }

    static class Browser {
        private final URI base;
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

        Browser(ConfigurableApplicationContext app) {
            base = URI.create("http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port"));
        }

        void login(String name, String password) throws IOException, InterruptedException {
            Document form = get("/login").html();
            assertThat(post("/login", Map.of("username", name, "password", password, "_csrf", csrf(form))).status())
                    .isEqualTo(302);
        }

        Page get(String path) throws IOException, InterruptedException {
            return request(HttpRequest.newBuilder(base.resolve(path)).GET().build());
        }

        Page save(Document form, String draft) throws IOException, InterruptedException {
            return save(form, Map.of("amount-food", draft));
        }

        Page save(Document form, Map<String, String> edits) throws IOException, InterruptedException {
            var fields = monthFields(form);
            fields.putAll(edits);
            return post(form.expectFirst("#month-form").attr("action"), fields);
        }

        Page saveHistory(Document page, Map<String, String> edits) throws IOException, InterruptedException {
            var form = page.expectFirst("#history-form");
            Map<String, String> fields = new HashMap<>();
            for (var input : form.select("input[name]")) {
                fields.put(input.attr("name"), input.val());
            }
            fields.putAll(edits);
            return post(form.attr("action"), fields);
        }

        Page plan(Document page, PlanDraft draft) throws IOException, InterruptedException {
            var form = page.expectFirst("#plan-form");
            var save = new SettingsController.Save(form.attr("data-period"), Long.parseLong(form.attr("data-revision")), draft);
            return request(HttpRequest.newBuilder(base.resolve("/settings"))
                    .header(form.attr("data-csrf-header"), csrf(page))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(save))).build());
        }

        Page post(String path, Map<String, String> fields) throws IOException, InterruptedException {
            String body = fields.entrySet().stream()
                    .map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return request(HttpRequest.newBuilder(base.resolve(path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        Page postForm(Element form, Map<String, String> fields) throws IOException, InterruptedException {
            var data = new HashMap<>(fields);
            data.put("_csrf", form.expectFirst("input[name=_csrf]").val());
            return post(form.attr("action"), data);
        }

        private Page request(HttpRequest request) throws IOException, InterruptedException {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Page(response.statusCode(), Jsoup.parse(response.body()));
        }
    }
}
