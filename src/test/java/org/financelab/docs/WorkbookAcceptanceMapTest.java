package org.financelab.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbookAcceptanceMapTest {
    private static final Path MAP = Path.of("docs/workbook-acceptance-map.md");

    @Test
    void everySourceAreaHasCoverageAndACompleteRepeatableExample() throws IOException {
        var sourceAreas = Set.of("Portfolio risk map", "Annual spending/income", "Subscriptions",
                "FIRE", "Currency", "Deposits", "Bonds", "Equity transactions", "Equity overview",
                "Equity analysis", "Funds", "Asset classes", "Economic exposure",
                "Dated decision analysis", "Dividends and residual cash", "Capital map");
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
                assertTrue(covered.add(cells[0].strip()), line);
                assertTrue(Integer.parseInt(cells[1].strip()) > 0, line);
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
            String target = line.split("]: ", 2)[1];
            assertTrue(Files.isRegularFile(MAP.getParent().resolve(target)), line);
        }
    }
}
