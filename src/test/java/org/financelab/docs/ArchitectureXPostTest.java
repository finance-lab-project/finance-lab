package org.financelab.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureXPostTest {

    @Test
    void draftFitsInOnePostWithTheReportLink() throws IOException {
        var lines = Files.readAllLines(Path.of("docs/architecture-evolution/x-post.md"));
        var draftLines = lines.stream().filter(line -> line.startsWith(">")).toList();
        assertEquals(1, draftLines.size(), "Keep the draft to one standalone post");

        String copy = draftLines.getFirst();
        var draft = Pattern.compile("^> ([ -~]+) (?:\\[REPORT LINK\\]|https://doi\\.org/10\\.5281/zenodo\\.\\d+)$")
                .matcher(copy);
        assertTrue(draft.matches(), "End the draft with a space and the report link or its placeholder");
        String prose = draft.group(1);
        assertTrue(!prose.contains("https://") && !prose.contains("http://"),
                "Keep only the report URL in the draft");

        // X counts the URL as 23 characters after shortening.
        assertTrue(prose.length() + 1 + 23 <= 280, "Include the separator and link in the X limit");
    }
}
