package org.financelab.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoneyInputTest {
    @Test
    void nodeInputRegressions(@TempDir Path temp) throws Exception {
        Path log = temp.resolve("money-test.log");
        var process = new ProcessBuilder("node", "--test", "src/test/js/money.test.js")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Node test suite finished").isTrue();
            assertThat(process.exitValue()).as("Node test suite output:%n%s", Files.readString(log)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void editsMoneyInAMobileBrowserContext() {
        try (var playwright = Playwright.create();
             var browser = playwright.chromium().launch();
             var context = browser.newContext(new Browser.NewContextOptions().setIsMobile(true).setHasTouch(true)
                     .setViewportSize(390, 844).setLocale("ru-RU"))) {
            var page = context.newPage();
            page.setContent("""
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <input data-money inputmode="decimal" placeholder="0">
                    """);
            page.addScriptTag(new Page.AddScriptTagOptions()
                    .setPath(Path.of("src/main/resources/static/money.js")));
            var input = page.locator("input");
            input.tap();
            assertThat(input.inputValue()).isEmpty();

            // Send text through the browser editor without hardware key events.
            page.keyboard().insertText("30000");
            assertThat(input.inputValue()).isEqualTo("30\u00a0000\u00a0₽");
            assertThat(input.evaluate("field => field.selectionStart")).isEqualTo(6);

            input.evaluate("field => field.setSelectionRange(1, 1)");
            page.keyboard().insertText("1");
            assertThat(input.inputValue()).isEqualTo("310\u00a0000\u00a0₽");
            assertThat(input.evaluate("field => field.selectionStart")).isEqualTo(2);

            input.evaluate("field => field.select()");
            page.keyboard().insertText("0");
            assertThat(input.inputValue()).isEqualTo("0\u00a0₽");
            input.press("End");
            page.evaluate("""
                    window.addEventListener("beforeinput", event => event.stopImmediatePropagation(),
                        {capture: true, once: true})
                    """);
            input.press("Backspace");
            assertThat(input.inputValue()).isEmpty();
            assertThat(input.evaluate("field => field.selectionStart")).isEqualTo(0);
        }
    }
}
