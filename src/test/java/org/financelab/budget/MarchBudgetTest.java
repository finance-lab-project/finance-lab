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
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(form.selectFirst("#food").val()).isEqualTo("6 000");
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
    void fractionalDraftStaysEditableAndCannotBypassAConflictingSave() throws Exception {
        try (var app = start()) {
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            Document oldForm = phone.get(EDIT).html();
            Page invalid = phone.save(oldForm, "1 234,50");
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.html().selectFirst("#food").val()).isEqualTo("1 234,50");
            assertThat(invalid.html().selectFirst("#field-error").text()).contains("целых рублях");
            assertMonth(desktop.get(MARCH).html(), "6 000", "20 000", "18 000");

            assertThat(desktop.save(desktop.get(EDIT).html(), "25 000").status()).isEqualTo(302);
            Page stillInvalid = phone.save(invalid.html(), "1 234,50");
            assertThat(revision(stillInvalid.html())).isEqualTo(revision(oldForm));
            Page conflict = phone.save(stillInvalid.html(), "28 177");
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.html().selectFirst("#save-conflict").text()).contains("25 000");
            assertThat(conflict.html().selectFirst("#food").val()).isEqualTo("28 177");
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
            assertThat(guest.post(MARCH, Map.of("food", "42", "revision", "0")).status()).isEqualTo(403);
            var first = signedIn(app, "owner-a");
            var second = signedIn(app, "owner-b");
            assertThat(first.save(first.get(EDIT).html(), "25 000").status()).isEqualTo(302);
            Document direct = second.get(EDIT + "&owner=owner-a").html();
            assertMonth(direct, "6 000", "20 000", "18 000");
            assertThat(second.post(MARCH, Map.of("owner", "owner-a", "food", "7 500,00",
                    "revision", revision(direct), "_csrf", csrf(direct))).status()).isEqualTo(302);
            assertMonth(second.get(MARCH).html(), "7 500", "21 500", "16 500");
            assertMonth(first.get(MARCH).html(), "25 000", "39 000", "−1 000");
            assertThat(second.get("/owners/owner-a" + MARCH).status()).isEqualTo(404);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(FinanceLabApplication.class).run(
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
        assertThat(html.selectFirst("#row-food .actual").wholeText()).isEqualTo(food + " ₽");
        assertThat(html.selectFirst("#spending").wholeText()).isEqualTo(spending + " ₽");
        assertThat(html.selectFirst("#row-food .remainder").wholeText()).isEqualTo(remainder + " ₽");
        assertThat(html.selectFirst("#row-food .plan").wholeText()).isEqualTo("24 000 ₽");
        assertThat(html.selectFirst("#row-food .buffer").wholeText()).isEqualTo("−2 000 ₽");
    }

    private static String csrf(Document html) {
        return html.selectFirst("input[name=_csrf]").val();
    }

    private static String revision(Document html) {
        return html.selectFirst("input[name=revision]").val();
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
            return post(MARCH, Map.of("food", draft, "revision", revision(form), "_csrf", csrf(form)));
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
