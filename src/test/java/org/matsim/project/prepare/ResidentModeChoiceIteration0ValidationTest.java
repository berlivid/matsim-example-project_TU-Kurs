package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.PtConstants;

class ResidentModeChoiceIteration0ValidationTest {

    @Test
    void exactlyThreeInMemoryOverridesLeaveProductiveConfigFileUnchanged() throws Exception {
        String beforeHash = ValidateResidentModeChoiceCalibrationConfig.sha256(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG);
        Config production = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(true);
        Map<String, String> before =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production);
        Config validation = RunMatsim2019ResidentModeChoiceIteration0Validation
                .applyApprovedOverrides(production);

        assertEquals(RunMatsim2019ResidentModeChoiceIteration0Validation.APPROVED_DIFFERENCES,
                RunMatsim2019ResidentModeChoiceIteration0Validation.differences(
                        before, RunMatsim2019ResidentModeChoiceIteration0Validation
                                .snapshot(validation)));
        assertEquals(0, validation.controller().getLastIteration());
        assertEquals(RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID,
                validation.controller().getRunId());
        assertEquals(RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT.toString(),
                validation.controller().getOutputDirectory());
        assertEquals(beforeHash, ValidateResidentModeChoiceCalibrationConfig.sha256(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG));
    }

    @Test
    void outputProtectionRejectsExistingAndProductiveTargets(@TempDir Path temp)
            throws Exception {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
        Config config = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(true);
        Map<String, String> before =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(config);
        config.controller().setRunId(
                RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID);
        config.controller().setLastIteration(0);
        assertThrows(IllegalStateException.class, () ->
                RunMatsim2019ResidentModeChoiceIteration0Validation
                        .validateApprovedOverrides(before, config));
        assertFalse(Files.exists(RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT));
        assertFalse(Files.exists(ValidateResidentModeChoiceCalibrationConfig.OUTPUT));
    }

    @Test
    void productiveStrategiesRemainExactlyScopedAndOpenTourAbsent() throws Exception {
        Config config = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(true);
        var strategies = ValidateResidentModeChoiceCalibrationConfig.strategyMap(config);
        assertEquals(Map.of("ChangeExpBeta", 0.8, "ReRoute", 0.1,
                        "SubtourModeChoice", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.MUNICH_RESIDENT));
        assertEquals(Map.of("ChangeExpBeta", 0.9, "ReRoute", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND));
        assertEquals(Map.of("ChangeExpBeta", 0.9, "ReRoute", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND));
        assertEquals("fromSpecifiedModesToSpecifiedModes",
                config.subtourModeChoice().getBehavior().toString());
        assertFalse(Files.readString(ValidateResidentModeChoiceCalibrationConfig.CONFIG)
                .toLowerCase().contains("open-tour"));
    }

    @Test
    void authoritativeRuntimeCohortAndResidentTripsRemainExact() throws Exception {
        Config config = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(true);
        var result = ValidateResidentModeChoiceCalibrationConfig
                .validateAuthoritativeCohort(config);
        assertEquals(324_043, result.persons());
        assertEquals(68_770, result.residents());
        assertEquals(147_655, result.nonResidents());
        assertEquals(107_618, result.unresolvedPersons());
        assertEquals(137_540, result.residentMainTrips());
        assertEquals(137_540, result.spatialCategorySum());
    }

    @Test
    void postValidatorAcceptsValidMinimalFactsAndRequiredFileFixture(
            @TempDir Path temp) throws Exception {
        createRequiredFiles(temp, "fixture");
        var files = ValidateResidentModeChoiceIteration0Output
                .locateRequiredFiles(temp, "fixture");
        assertTrue(Files.isRegularFile(files.events()));
        assertFalse(ValidateResidentModeChoiceIteration0Output.validateFacts(
                validFacts(0), expectations()));
    }

    @Test
    void missingIterationEventsOrPlansFailClosed(@TempDir Path temp) throws Exception {
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output
                        .locateRequiredFiles(temp, "fixture"));
        createRequiredFiles(temp, "fixture");
        Files.delete(temp.resolve("ITERS/it.0/fixture.0.events.xml.gz"));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output
                        .locateRequiredFiles(temp, "fixture"));
        Files.writeString(temp.resolve("ITERS/it.0/fixture.0.events.xml.gz"), "x");
        Files.delete(temp.resolve("ITERS/it.0/fixture.0.plans.xml.gz"));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output
                        .locateRequiredFiles(temp, "fixture"));
        Files.writeString(temp.resolve("ITERS/it.0/fixture.0.plans.xml.gz"), "x");
        Files.delete(temp.resolve("fixture.output_plans.xml.gz"));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output
                        .locateRequiredFiles(temp, "fixture"));
    }

    @Test
    void inconsistentPersonOrTripCountsFailClosed() {
        var valid = validFacts(0);
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output.validateFacts(
                        new ValidateResidentModeChoiceIteration0Output.Facts(
                                4, valid.cohorts(), valid.residentTrips(), valid.spatial(),
                                true, true, 10, 1, 1, 1, 1, 1, 0, 0, 0),
                        expectations()));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output.validateFacts(
                        new ValidateResidentModeChoiceIteration0Output.Facts(
                                valid.persons(), valid.cohorts(), 3, valid.spatial(),
                                true, true, 10, 1, 1, 1, 1, 1, 0, 0, 0),
                        expectations()));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output.validateFacts(
                        new ValidateResidentModeChoiceIteration0Output.Facts(
                                valid.persons(), valid.cohorts(), valid.residentTrips(),
                                valid.spatial(), true, false, 10,
                                1, 1, 1, 1, 1, 0, 0, 0), expectations()));
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Output.validateFacts(
                        new ValidateResidentModeChoiceIteration0Output.Facts(
                                valid.persons(), valid.cohorts(), valid.residentTrips(),
                                valid.spatial(), true, true, 10,
                                1, 1, 1, 1, 1, 0, 0, 1), expectations()));
    }

    @Test
    void nonzeroStuckEventsRequireReviewWithoutHardFailure() {
        assertTrue(ValidateResidentModeChoiceIteration0Output.validateFacts(
                validFacts(2), expectations()));
    }

    @Test
    void ptStageLegsDoNotBecomeMainModeChanges() {
        Plan input = PopulationUtils.createPlan();
        input.addActivity(PopulationUtils.createActivityFromCoord("home", new Coord(0, 0)));
        input.addLeg(PopulationUtils.createLeg(TransportMode.pt));
        input.addActivity(PopulationUtils.createActivityFromCoord("work", new Coord(1, 1)));

        Plan routed = PopulationUtils.createPlan();
        routed.addActivity(PopulationUtils.createActivityFromCoord("home", new Coord(0, 0)));
        routed.addLeg(PopulationUtils.createLeg(TransportMode.walk));
        routed.addActivity(PopulationUtils.createActivityFromCoord(
                PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(0.2, 0.2)));
        routed.addLeg(PopulationUtils.createLeg(TransportMode.pt));
        routed.addActivity(PopulationUtils.createActivityFromCoord(
                PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(0.8, 0.8)));
        routed.addLeg(PopulationUtils.createLeg(TransportMode.walk));
        routed.addActivity(PopulationUtils.createActivityFromCoord("work", new Coord(1, 1)));

        assertEquals(java.util.List.of("pt"),
                ValidateResidentModeChoiceIteration0Output.mainModes(input));
        assertEquals(ValidateResidentModeChoiceIteration0Output.mainModes(input),
                ValidateResidentModeChoiceIteration0Output.mainModes(routed));
    }

    @Test
    void protectedInputHashesRemainUnchanged() throws Exception {
        var before = ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        var after = ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        assertEquals(before, after);
        assertTrue(before.entrySet().stream().allMatch(entry ->
                Files.isRegularFile(entry.getKey())));
    }

    private static ValidateResidentModeChoiceIteration0Output.Expectations expectations() {
        return new ValidateResidentModeChoiceIteration0Output.Expectations(3, 1, 1, 1, 2,
                Map.of(
                        MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE, 2L,
                        MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY, 0L,
                        MunichTripBoundaryFilter.SpatialCategory.DESTINATION_ONLY, 0L,
                        MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE, 0L,
                        MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE, 0L));
    }

    private static ValidateResidentModeChoiceIteration0Output.Facts validFacts(long stuck) {
        return new ValidateResidentModeChoiceIteration0Output.Facts(3,
                Map.of(
                        ResidentCalibrationSubpopulations.MUNICH_RESIDENT, 1L,
                        ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND, 1L,
                        ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND, 1L),
                2, expectations().spatial(), true, true, 10,
                1, 1, 1, 1, 1, stuck, stuck == 0 ? 0 : 1, 0);
    }

    private static void createRequiredFiles(Path output, String runId) throws Exception {
        Path iteration = Files.createDirectories(output.resolve("ITERS/it.0"));
        Path analysis = Files.createDirectories(output.resolve("analysis"));
        for (Path file : java.util.List.of(
                iteration.resolve(runId + ".0.events.xml.gz"),
                iteration.resolve(runId + ".0.plans.xml.gz"),
                output.resolve(runId + ".output_plans.xml.gz"),
                output.resolve(runId + ".output_config.xml"),
                output.resolve(runId + ".output_network.xml.gz"),
                output.resolve(runId + ".output_transitSchedule.xml.gz"),
                output.resolve(runId + ".output_transitVehicles.xml.gz"),
                output.resolve(runId + ".logfile.log"),
                analysis.resolve("resident_mode_choice_iteration_metrics.csv"))) {
            Files.writeString(file, "fixture");
        }
    }
}
