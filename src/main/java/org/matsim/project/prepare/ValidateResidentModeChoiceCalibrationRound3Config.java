package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Read-only fail-closed validator for productive resident calibration Round 3. */
public final class ValidateResidentModeChoiceCalibrationRound3Config {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/"
                    + "config_resident_mode_choice_calibration_round_3.xml");
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/resident-mode-choice-round-3");
    public static final String RUN_ID = "munich-calibration-2019-resident-round-3";
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@outputDirectory",
            "module[controller]/@runId",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][1]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][2]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][3]/@constant");

    private ValidateResidentModeChoiceCalibrationRound3Config() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Round-3 validator accepts no arguments");
        Config config = loadAndValidate();
        ResidentModeChoiceRound3Specification.Validation round2 =
                ResidentModeChoiceRound3Specification.validate();
        System.out.printf(Locale.ROOT,
                "RESIDENT MODE-CHOICE ROUND-3 CONFIG VALIDATION PASS%n"
                        + "config=%s%noutput=%s%nrunId=%s iterations=0..%d%n"
                        + "constants=%s%nround2Iterations=%d residents=%d "
                        + "residentTrips=%d lateWindow=%d..%d%n"
                        + "innovationDisableAfterIteration=%d "
                        + "innovationWeightZeroFromIteration=%d%n"
                        + "No Controller or QSim was started.%n",
                CONFIG, OUTPUT, RUN_ID, config.controller().getLastIteration(),
                constants(config), round2.iterations(), round2.residents(),
                round2.residentTrips(), round2.lateFirstIteration(),
                round2.lateLastIteration(),
                ResidentModeChoiceRound3Specification
                        .EXPECTED_INNOVATION_DISABLE_AFTER_ITERATION,
                ResidentModeChoiceRound3Specification
                        .EXPECTED_INNOVATION_DISABLE_AFTER_ITERATION + 1);
    }

    static Config loadAndValidate() throws Exception {
        Config round3 = loadAndValidateStructure(true);
        ValidateResidentModeChoiceCalibrationConfig.validateAuthoritativeCohort(round3);
        return round3;
    }

    static Config loadAndValidateStructure(boolean requireOutputAbsent) throws Exception {
        ResidentModeChoiceRound3Specification.validate();
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing resident Round-3 config: " + CONFIG);
        Config round2 = ValidateResidentModeChoiceCalibrationRound2Config
                .loadAndValidateStructure(false);
        Config round3 = ConfigUtils.loadConfig(CONFIG.toString());
        Map<String, String> round2Snapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round2);
        Map<String, String> round3Snapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round3);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(round2Snapshot, round3Snapshot);
        ValidateModeChoiceCalibrationConfig.require(differences.equals(APPROVED_DIFFERENCES),
                "Round-3 config differs from Round 2 outside run ID, output "
                        + "directory and approved constants: " + differences);
        validateApprovedValues(round2, round3);
        if (requireOutputAbsent) {
            ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        }
        return round3;
    }

    private static void validateApprovedValues(Config round2, Config round3) {
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(
                        round3.controller().getRunId()),
                "Unexpected resident Round-3 runId");
        ValidateModeChoiceCalibrationConfig.require(normalize(OUTPUT).equals(normalize(
                        Path.of(round3.controller().getOutputDirectory()))),
                "Unexpected resident Round-3 output directory");
        ValidateModeChoiceCalibrationConfig.require(
                round3.controller().getFirstIteration() == 0
                        && round3.controller().getLastIteration() == 60,
                "Resident Round 3 must run exactly iterations 0..60");
        ValidateModeChoiceCalibrationConfig.require(
                round3.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Resident Round-3 output must remain fail-if-exists protected");
        ValidateModeChoiceCalibrationConfig.require(!normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationConfig.OUTPUT)),
                "Round 3 cannot write to a protected earlier calibration output");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double actual = constants(round3).get(mode);
            double expected = ResidentModeChoiceRound3Specification
                    .ROUND_3_CONSTANTS.get(mode);
            ValidateModeChoiceCalibrationConfig.require(
                    Math.abs(actual - expected) <= 1e-12,
                    "Unexpected Round-3 constant for " + mode + ": " + actual);
        }
        ValidateModeChoiceCalibrationConfig.require(
                round2.plans().getInputFile().equals(round3.plans().getInputFile()),
                "Round 3 must reload the unchanged original population");
        ValidateModeChoiceCalibrationConfig.require(
                round3.global().getRandomSeed() == 4711,
                "Round-3 random seed must remain 4711");
        ValidateModeChoiceCalibrationConfig.require(
                round3.qsim().getEndTime().isDefined()
                        && Math.abs(round3.qsim().getEndTime().seconds()
                        - 48 * 3600.0) < 1e-9,
                "Round-3 QSim horizon must remain 48:00:00");
        ValidateModeChoiceCalibrationConfig.require(Math.abs(
                        round3.replanning().getFractionOfIterationsToDisableInnovation()
                                - 0.8) < 1e-12,
                "Round-3 innovation-disable fraction must remain 0.8");
        ValidateModeChoiceCalibrationConfig.require(
                round3.replanning().getMaxAgentPlanMemorySize() == 4,
                "Round-3 plan memory must remain four plans");
        double tripTargetSum = ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT
                .values().stream().mapToDouble(Double::doubleValue).sum();
        double pkmTargetSum = ResidentModeChoiceCalibrationTargets
                .NORMALIZED_PKM_SHARE_PERCENT.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        ValidateModeChoiceCalibrationConfig.require(
                Math.abs(tripTargetSum - 100.0) < 1e-9
                        && Math.abs(pkmTargetSum - 100.0) < 1e-6,
                "Resident trip/Pkm target vectors must remain normalized");
    }

    static Map<String, Double> constants(Config config) {
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            var parameters = config.scoring().getModes().get(mode);
            ValidateModeChoiceCalibrationConfig.require(parameters != null,
                    "Missing scoring mode in Round 3: " + mode);
            result.put(mode, parameters.getConstant());
        }
        return Map.copyOf(result);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
