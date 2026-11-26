package org.financelab.budget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.financelab.budget.MarchBudgetTest.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FireTest {
    @TempDir Path directory;

    @Test
    void ownerGoalsAndSelectedBalancesDriveCapitalProgressAcrossSessions() throws Exception {
        try (var app = new SpringApplicationBuilder(FinanceLabApplication.class).profiles("fixture")
                .contextFactory(webType -> new GenericWebApplicationContext(new MockServletContext()))
                .run("--spring.datasource.url=jdbc:sqlite:" + directory.resolve("fire.sqlite"),
                        "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) app).build();
            var desktop = new MockHttpSession();
            var phone = new MockHttpSession();
            var investments = app.getBean(InvestmentStore.class);
            String march = "/investments/2027-03";
            String february = "/investments/2027-02";

            assertThat(page(mvc, desktop, "owner-a").select("#fire-empty-selection")).hasSize(1);
            for (String name : new String[]{"Вклад", "Брокерский счёт", "Резерв"}) {
                assertThat(investments.addAccount("owner-a", name)).isTrue();
            }
            assertThat(investments.addAccount("owner-b", "Другой счёт")).isTrue();
            var choices = page(mvc, desktop, "owner-a").select("input[name=accountIds]");
            assertThat(choices).hasSize(3);
            String first = choices.get(0).val();
            String second = choices.get(1).val();
            String excluded = choices.get(2).val();
            String foreign = page(mvc, phone, "owner-b").expectFirst("input[name=accountIds]").val();

            saveBalance(mvc, march, first, "1000000");
            saveBalance(mvc, february, second, "2000000");
            saveBalance(mvc, march, excluded, "500000");
            saveBalance(mvc, "/investments/2027-04", excluded, "500000");

            mvc.perform(post("/fire/accounts").session(desktop).principal(() -> "owner-a")
                    .param("accountIds", first, second)).andExpect(status().isFound());
            var missing = page(mvc, phone, "owner-a");
            assertThat(missing.expectFirst("#capital-target").text()).isEqualTo("Цель не задана");
            assertThat(missing.expectFirst("#fire-missing-balances").text()).contains("Брокерский счёт");
            assertThat(missing.expectFirst("#observation-month time").attr("datetime")).isEqualTo("2027-03");
            assertThat(missing.select("#capital-percentage")).isEmpty();

            saveBalance(mvc, march, second, "2000000");
            var withoutTarget = page(mvc, phone, "owner-a");
            assertThat(withoutTarget.expectFirst("#fire-capital").text()).isEqualTo("3 000 000 ₽");
            assertThat(withoutTarget.expectFirst("#capital-target").text()).isEqualTo("Цель не задана");
            assertThat(withoutTarget.select("#fire-missing-balances, #capital-percentage")).isEmpty();

            mvc.perform(post("/fire/goals/capital").session(desktop).principal(() -> "owner-a")
                    .param("amount", "30 000 000")).andExpect(status().isFound());
            mvc.perform(post("/fire/goals/income").session(phone).principal(() -> "owner-a")
                    .param("amount", "100 000")).andExpect(status().isFound());
            var progress = page(mvc, phone, "owner-a");
            assertThat(progress.expectFirst("#fire-capital").text()).isEqualTo("3 000 000 ₽");
            assertThat(progress.expectFirst("#capital-target").text()).isEqualTo("30 000 000 ₽");
            assertThat(progress.expectFirst("#capital-percentage").text()).isEqualTo("10%");
            assertThat(progress.expectFirst("#capital-remaining").text()).isEqualTo("27 000 000 ₽");
            assertThat(progress.select("input[name=accountIds][checked]").eachAttr("value")).containsExactly(first, second);

            for (String invalid : new String[]{"0", "-1", "1,50"}) {
                mvc.perform(post("/fire/goals/capital").session(desktop).principal(() -> "owner-a")
                        .param("amount", invalid)).andExpect(status().isUnprocessableContent());
            }
            assertThat(page(mvc, phone, "owner-a").expectFirst("#capital-percentage").text()).isEqualTo("10%");
            mvc.perform(post("/fire/goals/capital").session(desktop).principal(() -> "owner-a")
                    .param("amount", "3000000")).andExpect(status().isFound());
            progress = page(mvc, phone, "owner-a");
            assertThat(progress.expectFirst("#income-goal-form input[name=amount]").val()).isEqualTo("100\u00a0000");
            assertThat(progress.expectFirst("#capital-percentage").text()).isEqualTo("100%");
            assertThat(progress.expectFirst("#capital-remaining").text()).isEqualTo("0 ₽");

            saveBalance(mvc, march, second, "2100000");
            assertThat(page(mvc, phone, "owner-a").expectFirst("#fire-capital").text()).isEqualTo("3 100 000 ₽");
            assertThat(page(mvc, phone, "owner-a").expectFirst("#capital-remaining").text()).isEqualTo("0 ₽");

            mvc.perform(post("/fire/accounts").session(phone).principal(() -> "owner-a")
                    .param("accountIds", first, foreign)).andExpect(status().isUnprocessableContent());
            assertThat(page(mvc, desktop, "owner-a").select("input[name=accountIds][checked]").eachAttr("value"))
                    .containsExactly(first, second);
            assertThat(page(mvc, phone, "owner-b").expectFirst("#capital-target").text()).isEqualTo("Цель не задана");
            assertThat(page(mvc, phone, "owner-b").select("input[name=accountIds][checked]")).isEmpty();

            saveBalance(mvc, "/investments/2027-04", first, "1000000");
            mvc.perform(post("/fire/accounts").session(desktop).principal(() -> "owner-a")
                    .param("accountIds", first, second, excluded)).andExpect(status().isFound());
            var missingWithTarget = page(mvc, phone, "owner-a");
            assertThat(missingWithTarget.expectFirst("#fire-missing-balances").text()).contains("Брокерский счёт");
            assertThat(missingWithTarget.select("#capital-percentage")).isEmpty();
            saveBalance(mvc, "/investments/2027-04", second, "2000000");
            assertThat(page(mvc, phone, "owner-a").expectFirst("#fire-capital").text()).isEqualTo("3 500 000 ₽");

            mvc.perform(post("/fire/accounts").session(desktop).principal(() -> "owner-a"))
                    .andExpect(status().isFound());
            var empty = page(mvc, phone, "owner-a");
            assertThat(empty.select("#fire-empty-selection")).hasSize(1);
            assertThat(empty.select("#capital-percentage")).isEmpty();
        }
    }

    @Test
    void authenticatedClientsShareSettingsAndIncomeEditsPreserveCapital() throws Exception {
        try (var app = new SpringApplicationBuilder(FinanceLabApplication.class).profiles("fixture")
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("fire-browser.sqlite"),
                        "--budget.owner-a-password=fixture-owner-a", "--budget.owner-b-password=fixture-owner-b",
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            assertThat(app.getBean(InvestmentStore.class).addAccount("owner-a", "Вклад")).isTrue();
            var desktop = signedIn(app, "owner-a");
            var phone = signedIn(app, "owner-a");
            var initial = desktop.get("/fire");
            assertThat(initial.status()).isEqualTo(200);
            var accounts = initial.html().expectFirst("#fire-accounts-form");
            String accountId = accounts.expectFirst("input[name=accountIds]").val();
            assertThat(desktop.postForm(accounts, Map.of("accountIds", accountId)).status()).isEqualTo(302);
            assertThat(desktop.postForm(initial.html().expectFirst("#capital-goal-form"),
                    Map.of("amount", "30000000")).status()).isEqualTo(302);
            assertThat(desktop.postForm(initial.html().expectFirst("#income-goal-form"),
                    Map.of("amount", "100000")).status()).isEqualTo(302);

            var reopened = phone.get("/fire");
            assertThat(reopened.status()).isEqualTo(200);
            assertThat(reopened.html().select("input[name=accountIds][checked]").eachAttr("value"))
                    .containsExactly(accountId);
            assertThat(reopened.html().expectFirst("#capital-target").text()).isEqualTo("30 000 000 ₽");
            var incomeForm = reopened.html().expectFirst("#income-goal-form");
            assertThat(incomeForm.expectFirst("input[name=amount]").val()).isEqualTo("100\u00a0000");
            assertThat(phone.postForm(incomeForm, Map.of("amount", "125000")).status()).isEqualTo(302);

            var updated = desktop.get("/fire");
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.html().expectFirst("#income-goal-form input[name=amount]").val())
                    .isEqualTo("125\u00a0000");
            assertThat(updated.html().expectFirst("#capital-target").text()).isEqualTo("30 000 000 ₽");
        }
    }

    private static void saveBalance(MockMvc mvc, String path, String id, String amount) throws Exception {
        mvc.perform(post(path + "/accounts/" + id).principal(() -> "owner-a")
                .param("closingValue", amount).param("creditedIncome", "0")).andExpect(status().isFound());
    }

    private static Document page(MockMvc mvc, MockHttpSession session, String owner) throws Exception {
        return Jsoup.parse(mvc.perform(get("/fire").session(session).principal(() -> owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
