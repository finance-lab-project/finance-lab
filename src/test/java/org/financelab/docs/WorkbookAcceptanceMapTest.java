package org.financelab.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbookAcceptanceMapTest {
    private static final Path MAP = Path.of("docs/workbook-acceptance-map.md");

    @Test
    void everySourceAreaHasCoverageAndACompleteRepeatableExample() throws IOException {
        var formulaCounts = Map.ofEntries(
                Map.entry("Portfolio risk map", 121),
                Map.entry("Annual spending/income", 123),
                Map.entry("Subscriptions", 12),
                Map.entry("FIRE", 176),
                Map.entry("Currency", 9),
                Map.entry("Deposits", 1),
                Map.entry("Bonds", 63),
                Map.entry("Equity transactions", 175),
                Map.entry("Equity overview", 212),
                Map.entry("Equity analysis", 22),
                Map.entry("Funds", 29),
                Map.entry("Asset classes", 327),
                Map.entry("Economic exposure", 28),
                Map.entry("Dated decision analysis", 29),
                Map.entry("Dividends and residual cash", 10),
                Map.entry("Capital map", 86));
        var sourceAreas = formulaCounts.keySet();
        var statuses = Set.of("Verified in app", "Partial", "Absent", "Requires private comparison");
        var covered = new HashSet<String>();
        var examples = new HashSet<String>();
        boolean inExamples = false;
        for (String line : Files.readAllLines(MAP)) {
            if (line.equals("## Repeatable acceptance examples")) inExamples = true;
            if (line.equals("## One-off decisions")) break;
            if (!line.startsWith("| ") || line.startsWith("| ---")
                    || line.startsWith("| Source area")) continue;
            var cells = line.substring(1, line.length() - 1).split("\\|", -1);
            assertEquals(inExamples ? 6 : 4, cells.length, line);
            for (String cell : cells) assertFalse(cell.isBlank(), line);
            if (inExamples) {
                var metric = cells[0].strip().split(" · ", 2);
                assertEquals(2, metric.length, line);
                assertFalse(metric[1].isBlank(), line);
                assertTrue(sourceAreas.contains(metric[0]), line);
                examples.add(metric[0]);
            } else {
                String area = cells[0].strip();
                assertTrue(covered.add(area), line);
                assertEquals(formulaCounts.get(area), Integer.valueOf(cells[1].strip()), line);
                assertTrue(statuses.contains(cells[2].strip()), line);
            }
        }
        assertEquals(sourceAreas, covered);
        assertEquals(sourceAreas, examples);
    }

    @Test
    void annualRateExamplePreservesTheApprovedAllocationAndExplicitZero() throws IOException {
        assertExampleContains("Annual spending/income · annual baseline and saved zero",
                "366 RUB/year", "101 RUB monthly planning income", "50% investment target",
                "monthly essential plan is 31 RUB", "investments round to 51 RUB",
                "19 RUB splits into 11 RUB and 8 RUB at 60% and 40%",
                "historical actuals remain 366 RUB", "The draft retains the 366 RUB annual source amount",
                "explicitly saved 0 RUB expense and eleven unentered months",
                "replace a 1,000 RUB manual baseline with 0",
                "retains its 1,500 RUB baseline",
                "caps investments at 10,000 against a 20,000 target and leaves a zero pool",
                "Income of 30,000 gives a 10,000 shortfall, zero investments and a zero pool");
    }

    @Test
    void fireExampleDocumentsBothWindowBoundariesAndIncompleteMonths() throws IOException {
        assertExampleContains("FIRE · observed capital and income progress",
                "last completed Moscow month",
                "later of the earliest selected-account record on or before that end and the first month of the trailing 12-month window",
                "22,500 RUB/month while October is incomplete", "22.5% progress",
                "Recording October as zero changes the average to 15,000 RUB/month and progress to 15%",
                "November 2025 through October 2026 total 180,000 RUB",
                "every month in that window is incomplete",
                "the average and progress are omitted");
    }

    @Test
    void monthlyPlanExampleDocumentsTheSettingsFallback() throws IOException {
        assertExampleContains("Annual spending/income · monthly actuals and plan",
                "recorded monthly category plan",
                "If the monthly category plan is unrecorded, use the applicable settings plan.");
    }

    @Test
    void riskExampleDocumentsInputsAndExpectedCoverageAtTheFreshnessBoundary() throws IOException {
        assertExampleContains("Portfolio risk map · covered weights and look-through",
                "On 2026-11-28", "7 calendar days inclusive", "100 RUB dated 2026-11-21",
                "B has no quote", "200 RUB dated 2026-11-20",
                "Covered value is the sum of fresh holding values",
                "1 fresh, 1 missing and 1 stale", "60 RUB (60%)",
                "covered value stays 100 RUB and all 100 RUB is unclassified",
                "Reconcile the exact workbook cutoff privately");
    }

    private void assertExampleContains(String metric, String... expected) throws IOException {
        String row = Files.readAllLines(MAP).stream()
                .filter(line -> line.startsWith("| " + metric + " |"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing example: " + metric));
        for (String fragment : expected) {
            assertTrue(row.contains(fragment), () -> metric + " should include: " + fragment);
        }
    }

    @Test
    void repositoryEvidenceLinksResolve() throws IOException {
        for (String line : Files.readAllLines(MAP)) {
            if (!line.startsWith("[") || !line.contains("]: ")) continue;
            String target = line.split("]: ", 2)[1].split("#", 2)[0];
            assertTrue(Files.isRegularFile(MAP.getParent().resolve(target)), line);
        }
    }
}
