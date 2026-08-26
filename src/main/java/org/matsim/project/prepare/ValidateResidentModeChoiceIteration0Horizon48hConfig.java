package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.core.config.Config;

/** Read-only pre-run validation for the isolated 48-hour iteration-0 horizon test. */
public final class ValidateResidentModeChoiceIteration0Horizon48hConfig {
    public static final String RUN_ID =
            "munich-calibration-2019-resident-iteration-0-horizon-48h";
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/"
                    + "resident-mode-choice-iteration-0-horizon-48h");
    public static final double HORIZON_SECONDS = 48 * 3_600.0;
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@lastIteration",
            "module[controller]/@outputDirectory",
            "module[controller]/@runId",
            "module[qsim]/@endTime");

    private ValidateResidentModeChoiceIteration0Horizon48hConfig() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The 48-hour pre-run validator accepts no arguments");
        Validation validation = validateForServerRun();
        System.out.printf(Locale.ROOT,
                "RESIDENT ITERATION-0 HORIZON-48H PRE-RUN VALIDATION PASS%n"
                        + "runId=%s%noutput=%s%nqsimEnd=%s%n"
                        + "latestScheduleDeparture=%s%nlatestVehicleArrival=%s%n"
                        + "derivedScheduleHorizon=%s%n"
                        + "Only runId, outputDirectory, lastIteration and qsim.endTime differ. "
                        + "No Controller or QSim was started.%n",
                RUN_ID, OUTPUT, Gtfs2019ScheduleTimePolicy.formatTime(HORIZON_SECONDS),
                Gtfs2019ScheduleTimePolicy.formatTime(
                        validation.schedule().latestDeparture()),
                Gtfs2019ScheduleTimePolicy.formatTime(
                        validation.schedule().latestVehicleArrival()),
                Gtfs2019ScheduleTimePolicy.formatTime(
                        validation.schedule().qsimEndTime()));
    }

    static Validation validateForServerRun() throws Exception {
        Config production = ValidateResidentModeChoiceCalibrationConfig.loadAndValidate();
        Config horizon = applyApprovedOverrides(production);
        requireOutputsProtected();
        ValidateResidentModeChoiceIteration0Output.locateRequiredFiles(
                RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT,
                RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID);
        Gtfs2019ScheduleTimePolicy.Audit schedule = auditProtectedSchedule();
        return new Validation(horizon, schedule);
    }

    static Config expectedConfigWithoutOutputChecks() throws Exception {
        return applyApprovedOverrides(ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false));
    }

    static Config applyApprovedOverrides(Config production) {
        Map<String, String> before =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production);
        production.controller().setRunId(RUN_ID);
        production.controller().setOutputDirectory(OUTPUT.toString());
        production.controller().setLastIteration(0);
        production.qsim().setEndTime(HORIZON_SECONDS);
        validateApprovedOverrides(before, production);
        return production;
    }

    static void validateApprovedOverrides(Map<String, String> productionSnapshot,
                                          Config horizon) {
        Map<String, String> after =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(horizon);
        Set<String> changed = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(productionSnapshot, after);
        require(changed.equals(APPROVED_DIFFERENCES),
                "48-hour horizon config differs outside the four approved fields: "
                        + changed);
        require(RUN_ID.equals(horizon.controller().getRunId()),
                "Unexpected 48-hour horizon-test runId");
        require(normalize(OUTPUT).equals(normalize(
                        Path.of(horizon.controller().getOutputDirectory()))),
                "Unexpected 48-hour horizon-test output directory");
        require(horizon.controller().getFirstIteration() == 0
                        && horizon.controller().getLastIteration() == 0,
                "The 48-hour horizon test must execute exactly iteration 0");
        require(horizon.qsim().getEndTime().isDefined()
                        && horizon.qsim().getEndTime().seconds() == HORIZON_SECONDS,
                "The horizon-test QSim end time must be exactly 48:00:00");
        require(!normalize(OUTPUT).equals(normalize(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT)),
                "The preserved 43-hour output cannot be reused");
        require(!normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationConfig.OUTPUT)),
                "The productive output cannot be reused by the horizon test");
    }

    static void requireOutputsProtected() {
        require(Files.isDirectory(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT),
                "Preserved 43-hour output is missing");
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(
                ValidateResidentModeChoiceCalibrationConfig.OUTPUT);
    }

    static Gtfs2019ScheduleTimePolicy.Audit auditProtectedSchedule() {
        Gtfs2019ScheduleTimePolicy.Audit audit = Gtfs2019ScheduleTimePolicy.audit(
                CreateGtfs2019CalibrationTransit.loadPublished().getTransitSchedule());
        validateScheduleEvidence(audit);
        return audit;
    }

    static void validateScheduleEvidence(Gtfs2019ScheduleTimePolicy.Audit audit) {
        require(audit.latestDeparture() == 29 * 3_600 + 40 * 60,
                "Protected schedule latest departure changed");
        require(audit.largestArrivalOffset() == 32 * 3_600 + 35 * 60,
                "Protected schedule largest arrival offset changed");
        require(audit.latestVehicleArrival() == 42 * 3_600 + 30 * 60,
                "Protected schedule latest vehicle arrival changed");
        require(audit.qsimEndTime() == 43 * 3_600,
                "Protected schedule-derived horizon changed");
        require(audit.latestVehicleArrival() < HORIZON_SECONDS,
                "The protected transit schedule exceeds the 48-hour test horizon");
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    record Validation(Config config, Gtfs2019ScheduleTimePolicy.Audit schedule) { }
}
