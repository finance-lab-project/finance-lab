package org.financelab.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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
        var diagrams = Pattern.compile("(?m)^```mermaid\\h*\\R((?:(?!^```\\h*$)[^\\r\\n]*\\R)*)^```\\h*$")
                .matcher(report);
        var captions = Pattern.compile("\\R(?:\\h*\\R)*\\*Figure ([0-9]+)\\. [^*\\r\\n]+\\* "
                + "\\[[^\\]\\r\\n]+\\]\\[[a-z][a-z0-9-]*\\]").matcher(report);
        var expectedDatabases = List.of("PostgreSQL", "SQLite");
        for (int index = 0; index < expectedDatabases.size(); index++) {
            int figure = index + 1;
            assertTrue(diagrams.find(), "Missing Mermaid diagram for Figure " + figure);
            captions.region(diagrams.end(), report.length());
            assertTrue(captions.lookingAt(), "Missing sourced caption immediately after diagram " + figure);
            assertEquals(Integer.toString(figure), captions.group(1), "Unexpected figure number");
            assertTrue(diagrams.group(1).contains(expectedDatabases.get(index)),
                    "Unexpected database in Figure " + figure);
        }
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
