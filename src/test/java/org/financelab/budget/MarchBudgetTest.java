package org.financelab.budget;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarchBudgetTest {
    private static final String MARCH = "/months/2027-03";
    private static final String EDIT = MARCH + "?edit=true";
    @TempDir Path directory;

    @Test
    void savedReplacementSurvivesRefreshRepeatAndRestart() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            Document form = desktop.get(EDIT).html();
            assertThat(form.expectFirst("#amount-food").val()).isEqualTo("6 000");
            assertMonth(phone.get(MARCH).html(), "6 000", "20 000", "18 000");

            assertThat(desktop.save(form, "25 000").status()).isEqualTo(302);
            assertThat(desktop.get(MARCH).html().selectFirst("#save-success")).isNotNull();
            assertMonth(phone.get(MARCH).html(), "25 000", "39 000", "−1 000");
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
        var builder = new SpringApplicationBuilder(FinanceLabApplication.class);
        if (demo) builder.profiles("demo");
        return builder.run("--budget.current-month=2027-03",
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("budget.sqlite"),
                "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private static Browser signedIn(ConfigurableApplicationContext app, String name) throws Exception {
        var browser = new Browser(app);
        Document login = browser.get("/login").html();
        assertThat(browser.post("/login", Map.of("username", name, "password", "fixture-" + name,
                "_csrf", csrf(login))).status()).isEqualTo(302);
        return browser;
    }

    private static void assertMonth(Document html, String food, String spending, String remainder) {
        assertThat(html.expectFirst("#row-food .actual").wholeText()).isEqualTo(food + " ₽");
        assertThat(html.expectFirst("#spending").wholeText()).isEqualTo(spending + " ₽");
        assertThat(html.expectFirst("#row-food .remainder").wholeText()).isEqualTo(remainder + " ₽");
        assertThat(html.expectFirst("#row-food .plan").wholeText()).isEqualTo("24 000 ₽");
        assertThat(html.expectFirst("#row-food .buffer").wholeText()).isEqualTo("−2 000 ₽");
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

    private record Page(int status, Document html) { }

    private static class Browser {
        private final URI base;
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

        Browser(ConfigurableApplicationContext app) {
            base = URI.create("http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port"));
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

        private Page request(HttpRequest request) throws IOException, InterruptedException {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Page(response.statusCode(), Jsoup.parse(response.body()));
        }
    }
}
