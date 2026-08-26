package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;

class ResidentIteration0Horizon48hTest {

    @Test
    void exactlyFourOverridesLeaveProductiveConfigFileUnchanged() throws Exception {
        String beforeHash = ValidateResidentModeChoiceCalibrationConfig.sha256(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG);
        Config production = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        Map<String, String> before =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production);

        Config horizon = ValidateResidentModeChoiceIteration0Horizon48hConfig
                .applyApprovedOverrides(production);

        assertEquals(ValidateResidentModeChoiceIteration0Horizon48hConfig
                        .APPROVED_DIFFERENCES,
                RunMatsim2019ResidentModeChoiceIteration0Validation.differences(
                        before, RunMatsim2019ResidentModeChoiceIteration0Validation
                                .snapshot(horizon)));
        assertEquals(0, horizon.controller().getLastIteration());
        assertEquals(48 * 3_600.0, horizon.qsim().getEndTime().seconds());
        assertEquals(ValidateResidentModeChoiceIteration0Horizon48hConfig.RUN_ID,
                horizon.controller().getRunId());
        assertEquals(ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT.toString(),
                horizon.controller().getOutputDirectory());
        assertEquals(beforeHash, ValidateResidentModeChoiceCalibrationConfig.sha256(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG));
    }

    @Test
    void fifthOverrideFailsClosed() throws Exception {
        Config production = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        Map<String, String> before =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production);
        production.controller().setRunId(
                ValidateResidentModeChoiceIteration0Horizon48hConfig.RUN_ID);
        production.controller().setOutputDirectory(
                ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT.toString());
        production.controller().setLastIteration(0);
        production.qsim().setEndTime(48 * 3_600.0);
        production.global().setRandomSeed(9_999);

        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Horizon48hConfig
                        .validateApprovedOverrides(before, production));
    }

    @Test
    void outputProtectionRefusesExistingTarget(@TempDir Path temp) {
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
        assertFalse(ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT.equals(
                RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT));
        assertFalse(ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT.equals(
                ValidateResidentModeChoiceCalibrationConfig.OUTPUT));
    }

    @Test
    void protectedScheduleEvidenceIsExactly4230AndDerived43h() {
        var audit = new Gtfs2019ScheduleTimePolicy.Audit(
                29 * 3_600 + 40 * 60,
                32 * 3_600 + 35 * 60,
                32 * 3_600 + 35 * 60,
                42 * 3_600 + 30 * 60,
                43 * 3_600,
                1, 1, List.of());
        ValidateResidentModeChoiceIteration0Horizon48hConfig
                .validateScheduleEvidence(audit);
        assertThrows(IllegalStateException.class, () ->
                ValidateResidentModeChoiceIteration0Horizon48hConfig
                        .validateScheduleEvidence(new Gtfs2019ScheduleTimePolicy.Audit(
                                audit.latestDeparture(), audit.largestArrivalOffset(),
                                audit.largestDepartureOffset(), 43 * 3_600,
                                44 * 3_600, 1, 1, List.of())));
    }

    @Test
    void disappearingOldCutoffEventsSupportHorizonCause() {
        var comparison = CompareResidentModeChoiceIteration0HorizonStuckEvents.compare(
                List.of(stuck("old-1", "munich_resident", "car", 43 * 3_600.0),
                        stuck("old-2", "regional_background", "pt", 43 * 3_600.0)),
                List.of());

        assertEquals(CompareResidentModeChoiceIteration0HorizonStuckEvents
                        .CauseAssessment.SUPPORTED_ALL_OLD_EVENTS_DISAPPEARED,
                comparison.cause());
        assertFalse(comparison.reviewRequired());
        assertEquals(0, comparison.persistingOldPersons());
    }

    @Test
    void declineWithPersistingEventsRequiresReview() {
        var comparison = CompareResidentModeChoiceIteration0HorizonStuckEvents.compare(
                List.of(stuck("old-1", "munich_resident", "car", 43 * 3_600.0),
                        stuck("old-2", "regional_background", "pt", 43 * 3_600.0)),
                List.of(stuck("old-1", "munich_resident", "car", 47 * 3_600.0)));

        assertEquals(CompareResidentModeChoiceIteration0HorizonStuckEvents
                .CauseAssessment.PARTIALLY_SUPPORTED, comparison.cause());
        assertTrue(comparison.reviewRequired());
        assertEquals(1, comparison.persistingOldPersons());
    }

    @Test
    void eventsAt48AreReportedAsMovedToNewCutoff() {
        var comparison = CompareResidentModeChoiceIteration0HorizonStuckEvents.compare(
                List.of(stuck("old", "munich_resident", "car", 43 * 3_600.0)),
                List.of(stuck("new", "munich_resident", "car", 48 * 3_600.0)));

        assertEquals(1, comparison.newEventsInHour48());
        assertEquals(1, comparison.newEventsInFinalHour());
        assertTrue(comparison.reviewRequired());
    }

    @Test
    void comparisonCsvGroupsByCohortModeAndExactTime() {
        String csv = CompareResidentModeChoiceIteration0HorizonStuckEvents.comparisonCsv(
                List.of(stuck("a", "munich_resident", "car", 154_800.0),
                        stuck("b", "munich_resident", "car", 154_800.0),
                        stuck("c", "regional_background", "pt", 154_799.5)),
                List.of());

        assertTrue(csv.contains("43h,munich_resident,car,154800.000,43:00:00,2,2"));
        assertTrue(csv.contains(
                "43h,regional_background,pt,154799.500,43:00:00,1,1"));
        assertTrue(csv.contains("48h,ALL,ALL,,,0,0"));
    }

    @Test
    void protectedInputHashesAreReadOnly() throws Exception {
        var before = ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        var after = ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        assertEquals(before, after);
        assertTrue(before.keySet().stream().allMatch(Files::isRegularFile));
    }

    private static CompareResidentModeChoiceIteration0HorizonStuckEvents.StuckRecord
    stuck(String person, String cohort, String mode, double time) {
        return new CompareResidentModeChoiceIteration0HorizonStuckEvents.StuckRecord(
                person, cohort, mode, time);
    }
}
