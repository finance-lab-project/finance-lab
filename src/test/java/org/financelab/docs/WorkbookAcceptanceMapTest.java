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
    void repositoryEvidenceLinksResolve() throws IOException {
        for (String line : Files.readAllLines(MAP)) {
            if (!line.startsWith("[") || !line.contains("]: ")) continue;
            String target = line.split("]: ", 2)[1].split("#", 2)[0];
            assertTrue(Files.isRegularFile(MAP.getParent().resolve(target)), line);
        }
    }
}
