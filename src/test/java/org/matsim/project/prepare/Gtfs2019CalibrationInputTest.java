package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.matsim.core.config.ConfigUtils;

class Gtfs2019CalibrationInputTest {
    @Test
    void approvedSubsetIsClosedAndUsesExpectedSelection() throws Exception {
        assertEquals(BuildSyntheticGtfs2019Reference.EXPECTED_SOURCE_SHA,
                AnalyzeGtfs2019CalibrationInput.sha256(BuildSyntheticGtfs2019Reference.SOURCE));
        var result = BuildSyntheticGtfs2019Reference.analyze();
        assertEquals(5_948, result.analysisRoutes());
        assertEquals(185_663, result.analysisTrips());
        assertEquals(1_610, result.modelRoutes());
        assertEquals(59_103, result.modelTrips());
        assertEquals(Map.of("bus", 1_486L, "tram", 24L, "subway", 7L,
                "rail", 88L, "ferry", 5L), result.routesByMode());
        var subset = AnalyzeGtfs2019CalibrationInput.analyze(BuildSyntheticGtfs2019Reference.OUTPUT);
        assertTrue(subset.blockers().isEmpty(), subset.blockers().toString());
        assertEquals(subset.routes(), subset.analysis2019FlaggedRoutes());
        assertEquals(0, subset.missingCoordinates());
    }

    @Test
    void publishedInputsAndIterationZeroConfigAreIsolated() {
        assertTrue(Files.isRegularFile(CreateGtfs2019CalibrationTransit.OUTPUT_DIR.resolve("network-with-pt.xml.gz")));
        assertTrue(Files.isRegularFile(CreateGtfs2019CalibrationTransit.OUTPUT_DIR.resolve("transitSchedule.xml.gz")));
        assertTrue(Files.isRegularFile(CreateGtfs2019CalibrationTransit.OUTPUT_DIR.resolve("transitVehicles.xml.gz")));
        var config = ConfigUtils.loadConfig(CreateGtfs2019CalibrationTransit.VALIDATION_CONFIG.toString());
        assertTrue(config.transit().isUseTransit());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(0, config.controller().getLastIteration());
        assertEquals(2, config.qsim().getNumberOfThreads());
        assertEquals("munich-calibration-2019", config.controller().getRunId());
        assertEquals(org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                config.controller().getOverwriteFileSetting());
        assertFalse(config.replanning().getStrategySettings().stream().anyMatch(s ->
                s.getStrategyName() != null && s.getStrategyName().toLowerCase().contains("modechoice")));
        assertTrue(config.network().getInputFile().endsWith("input_transit/network-with-pt.xml.gz"));
        assertTrue(config.plans().getInputFile().contains("munich-v1.0-5pct.plans.xml"));
    }

    @Test
    void publishedScheduleRoutesStructurallyWithoutStartingQsim() {
        ValidateGtfs2019CalibrationInput.validateStructureOnly();
    }
}
