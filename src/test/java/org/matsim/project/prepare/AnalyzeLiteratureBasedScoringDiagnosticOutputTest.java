package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.ConfigUtils;

class AnalyzeLiteratureBasedScoringDiagnosticOutputTest {
    private static MunichMunicipalBoundary boundary;
    private static MunichTripBoundaryFilter filter;
    private static Coord inside;
    private static Coord outside;

    @BeforeAll
    static void boundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        filter = new MunichTripBoundaryFilter(boundary);
        var point = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(point.x, point.y);
        outside = new Coord(boundary.envelope().getMinX() - 10_000,
                boundary.envelope().getMinY() - 10_000);
    }

    @Test
    void streamsTripsAndKeepsPtAsAnalysisMainMode(@TempDir Path temp) throws Exception {
        Path trips = trips(temp, List.of(
                row("p1", "pt", inside, inside, 10_000, "00:30:00"),
                row("p2", "car", inside, outside, 4_000, "600"),
                row("p3", "walk", inside, inside, 2_000, "1500"),
                row("p4", "bike", outside, outside, 8_000, "1200")));

        var result = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .analyzeTripsCsv(trips, filter);

        assertEquals(4, result.personCount());
        assertEquals(4, result.totalTrips());
        assertEquals(2, result.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE));
        assertEquals(1, result.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY));
        assertEquals(1, result.modeMetrics().get("pt").trips());
        assertEquals(10_000, result.modeMetrics().get("pt").distanceMetres());
        assertEquals(1800, result.modeMetrics().get("pt").travelTimeSeconds());
        assertEquals(Set.of("p1", "p3"), result.bothInsidePersons());
    }

    @Test
    void calculatesModalSharesPkmExpansionAndMeans(@TempDir Path temp) throws Exception {
        Path trips = trips(temp, List.of(
                row("p1", "car", inside, inside, 10_000, "600"),
                row("p2", "car", inside, inside, 20_000, "1200"),
                row("p3", "pt", inside, inside, 30_000, "1800"),
                row("p4", "walk", inside, inside, 2_000, "1200")));
        var result = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .analyzeTripsCsv(trips, filter);
        var reports = AnalyzeLiteratureBasedScoringDiagnosticOutput.buildReports(
                new AnalyzeLiteratureBasedScoringDiagnosticOutput.OutputFiles(
                        Path.of("config"), trips, null, Path.of("events"), null),
                result, List.of(), new AnalyzeLiteratureBasedScoringDiagnosticOutput.StuckSummary(
                        0, 0, Map.of(), Map.of(), Map.of(), 0, 0, 0));
        String csv = reports.get("literature_based_scoring_final_mode_summary.csv");
        assertTrue(csv.contains("car,2,50.000000000,34.000000000,16.000000000,"
                + "30.000000000,48.387096774,600.000000000,15.000000000,15.000000000"));
        assertTrue(csv.contains("pt,1,25.000000000,24.000000000,1.000000000,"
                + "30.000000000,48.387096774,600.000000000,30.000000000,30.000000000"));
    }

    @Test
    void reportsUnexpectedModesAndMissingCoordinates(@TempDir Path temp) throws Exception {
        Path trips = trips(temp, List.of(
                row("p1", "ride", inside, inside, 1000, "60"),
                "p2;car;;;" + inside.getX() + ";" + inside.getY() + ";1000;60"));
        var result = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .analyzeTripsCsv(trips, filter);
        assertEquals(1L, result.unexpectedModes().get("ride"));
        assertEquals(1, result.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE));
        assertThrows(IllegalStateException.class,
                () -> AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .validateStructuralTotals(result));
    }

    @Test
    void semanticallyMatchesReorderedParameterSetsAndRejectsChangedValues() {
        var expected = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.toString());
        var reordered = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.toString());
        var settings = new ArrayList<>(reordered.replanning().getStrategySettings());
        reordered.replanning().clearStrategySettings();
        for (int index = settings.size() - 1; index >= 0; index--) {
            reordered.replanning().addStrategySettings(settings.get(index));
        }
        assertTrue(AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(expected, reordered).isEmpty());
        reordered.global().setRandomSeed(99);
        List<String> differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(expected, reordered);
        assertTrue(differences.stream().anyMatch(value -> value.startsWith("global:")));
    }

    @Test
    void failsClosedOnMissingOrIncompleteOutputWithoutPublishing(@TempDir Path temp) {
        assertThrows(IllegalStateException.class,
                () -> AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .validateOutput(temp));
        assertFalse(Files.exists(temp.resolve("analysis")));
    }

    @Test
    void aggregatesStuckEventsByModeHourAndBoundary(@TempDir Path temp) throws Exception {
        Path events = temp.resolve("events.xml.gz");
        gzip(events, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<events version=\"1.0\">\n"
                + "<event time=\"171000.0\" type=\"stuckAndAbort\" person=\"p1\" "
                + "link=\"l1\" legMode=\"car\" />\n"
                + "<event time=\"172800.0\" type=\"stuckAndAbort\" person=\"p2\" "
                + "link=\"l2\" legMode=\"pt\" />\n</events>\n");
        var result = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .readStuckEvents(events, Set.of("p2"));
        assertEquals(2, result.totalEvents());
        assertEquals(2, result.uniquePersons());
        assertEquals(1, result.byMode().get("car"));
        assertEquals(1, result.byMode().get("pt"));
        assertEquals(1, result.exactlyAt48Hours());
        assertEquals(2, result.inFinalHour());
        assertEquals(1, result.uniquePersonsWithBothInsideTrip());
    }

    @Test
    void atomicPublicationCleansTemporaryFolderAfterFailure(@TempDir Path temp)
            throws Exception {
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("first.csv", "complete");
        invalid.put("second.csv", null);
        assertThrows(RuntimeException.class,
                () -> AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .publishAtomically(temp, invalid));
        assertFalse(Files.exists(temp.resolve("analysis")));
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".analysis-tmp-")));
        }
    }

    @Test
    void refusesToOverwritePublishedAnalysis(@TempDir Path temp) throws Exception {
        Files.createDirectory(temp.resolve("analysis"));
        Files.writeString(temp.resolve("analysis/original.txt"), "preserve");
        assertThrows(IllegalStateException.class,
                () -> AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .publishAtomically(temp, Map.of("new.csv", "new")));
        assertEquals("preserve", Files.readString(temp.resolve("analysis/original.txt")));
    }

    private static Path trips(Path temp, List<String> rows) throws IOException {
        Path file = temp.resolve("trips.csv.gz");
        StringBuilder csv = new StringBuilder(
                "person;main_mode;start_x;start_y;end_x;end_y;traveled_distance;trav_time\n");
        rows.forEach(row -> csv.append(row).append('\n'));
        gzip(file, csv.toString());
        return file;
    }

    private static String row(String person, String mode, Coord from, Coord to,
            double distance, String time) {
        return person + ';' + mode + ';' + from.getX() + ';' + from.getY() + ';'
                + to.getX() + ';' + to.getY() + ';' + distance + ';' + time;
    }

    private static void gzip(Path file, String content) throws IOException {
        try (var output = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            output.write(content);
        }
    }
}
