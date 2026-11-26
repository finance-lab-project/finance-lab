package org.financelab.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureEvolutionReportTest {

    private static final Path REPORT = Path.of("docs/architecture-evolution/report.md");

    @Test
    void reportRetainsEditableDiagramsWithSourcedCaptions() throws IOException {
        String report = Files.readString(REPORT);
        var diagrams = Pattern.compile("(?ms)^```mermaid\\R(.+?)^```\\R\\R"
                + "\\*Figure [12]\\. [^*]+\\* \\[[^\\]]+\\]\\[[a-z][a-z0-9-]*\\]")
                .matcher(report);
        assertTrue(diagrams.find(), "Keep the editable before diagram and sourced caption");
        assertTrue(diagrams.group(1).contains("PostgreSQL"));
        assertTrue(diagrams.find(), "Keep the editable after diagram and sourced caption");
        assertTrue(diagrams.group(1).contains("SQLite"));
    }

    @Test
    void evidenceCitationsResolveToUniqueReferences() throws IOException {
        String report = Files.readString(REPORT);
        var definitions = Pattern.compile("(?m)^\\[([a-z][a-z0-9-]*)\\]: (\\S+)$").matcher(report);
        var referenceIds = new HashSet<String>();
        while (definitions.find()) {
            assertTrue(referenceIds.add(definitions.group(1)), "Duplicate citation " + definitions.group(1));
            String target = definitions.group(2);
            assertTrue(target.startsWith("https://github.com/")
                    || Files.isRegularFile(REPORT.resolveSibling(target)), "Invalid evidence target " + target);
        }

        var citations = Pattern.compile("\\]\\[([a-z][a-z0-9-]*)\\]").matcher(report);
        var citedIds = new HashSet<String>();
        while (citations.find()) {
            citedIds.add(citations.group(1));
        }
        assertFalse(citedIds.isEmpty(), "Keep evidence citations in the report");
        assertEquals(referenceIds, citedIds, "Every evidence citation must resolve to a reference");
    }
}
