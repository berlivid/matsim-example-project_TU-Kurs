package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Read-only fail-closed validator for productive resident calibration Round 2. */
public final class ValidateResidentModeChoiceCalibrationRound2Config {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/"
                    + "config_resident_mode_choice_calibration_round_2.xml");
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/resident-mode-choice-round-2");
    public static final String RUN_ID = "munich-calibration-2019-resident-round-2";
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@lastIteration",
            "module[controller]/@outputDirectory",
            "module[controller]/@runId",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][1]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][2]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][3]/@constant");

    private ValidateResidentModeChoiceCalibrationRound2Config() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Round-2 validator accepts no arguments");
        Config config = loadAndValidate();
        ResidentModeChoiceRound2Specification.Validation round1 =
                ResidentModeChoiceRound2Specification.validate();
        System.out.printf(Locale.ROOT,
                "RESIDENT MODE-CHOICE ROUND-2 CONFIG VALIDATION PASS%n"
                        + "config=%s%noutput=%s%nrunId=%s iterations=0..%d%n"
                        + "constants=%s%nround1Iterations=%d residents=%d "
                        + "residentTrips=%d round1FinalAffectedTrips=%d%n"
                        + "innovationDisableFraction=%.9f "
                        + "expectedDisableAfterIteration=%d "
                        + "innovationWeightZeroFromIteration=%d%n"
                        + "No Controller or QSim was started.%n",
                CONFIG, OUTPUT, RUN_ID, config.controller().getLastIteration(),
                constants(config), round1.iterations(), round1.residents(),
                round1.residentTrips(), round1.affectedTrips(),
                config.replanning().getFractionOfIterationsToDisableInnovation(),
                ResidentModeChoiceRound2Specification.EXPECTED_INNOVATION_DISABLE_ITERATION,
                ResidentModeChoiceRound2Specification
                        .EXPECTED_INNOVATION_DISABLE_ITERATION + 1);
    }

    static Config loadAndValidate() throws Exception {
        Config round = loadAndValidateStructure(true);
        ValidateResidentModeChoiceCalibrationConfig.validateAuthoritativeCohort(round);
        return round;
    }

    static Config loadAndValidateStructure(boolean requireOutputAbsent) throws Exception {
        ResidentModeChoiceRound2Specification.validate();
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing resident Round-2 config: " + CONFIG);
        Config round1 = ValidateResidentModeChoiceCalibrationRound1Config
                .loadAndValidateStructure(false);
        Config round2 = ConfigUtils.loadConfig(CONFIG.toString());
        Map<String, String> round1Snapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round1);
        Map<String, String> round2Snapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round2);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(round1Snapshot, round2Snapshot);
        ValidateModeChoiceCalibrationConfig.require(differences.equals(APPROVED_DIFFERENCES),
                "Round-2 config differs from Round 1 outside the approved controller "
                        + "fields and constants: " + differences);
        validateApprovedValues(round1, round2);
        if (requireOutputAbsent) {
            ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        }
        return round2;
    }

    private static void validateApprovedValues(Config round1, Config round2) {
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(
                        round2.controller().getRunId()),
                "Unexpected resident Round-2 runId");
        ValidateModeChoiceCalibrationConfig.require(normalize(OUTPUT).equals(normalize(
                        Path.of(round2.controller().getOutputDirectory()))),
                "Unexpected resident Round-2 output directory");
        ValidateModeChoiceCalibrationConfig.require(
                round2.controller().getFirstIteration() == 0
                        && round2.controller().getLastIteration() == 60,
                "Resident Round 2 must run iterations 0..60");
        ValidateModeChoiceCalibrationConfig.require(
                round2.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Resident Round-2 output must remain fail-if-exists protected");
        ValidateModeChoiceCalibrationConfig.require(!normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationConfig.OUTPUT)),
                "Round 2 cannot write to a protected earlier calibration output");
        Map<String, Double> actual = constants(round2);
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ValidateModeChoiceCalibrationConfig.require(Math.abs(actual.get(mode)
                            - ResidentModeChoiceRound2Specification
                            .ROUND_2_CONSTANTS.get(mode)) <= 1e-12,
                    "Unexpected approved Round-2 constant for " + mode + ": "
                            + actual.get(mode));
        }
        ValidateModeChoiceCalibrationConfig.require(
                round2.qsim().getEndTime().isDefined()
                        && Math.abs(round2.qsim().getEndTime().seconds()
                        - 48 * 3600.0) < 1e-9,
                "Resident Round-2 QSim horizon must remain 48:00:00");
        ValidateModeChoiceCalibrationConfig.require(Math.abs(
                        round2.replanning().getFractionOfIterationsToDisableInnovation()
                                - 0.8) < 1e-12,
                "Round-2 innovation-disable fraction must remain 0.8");
        ValidateModeChoiceCalibrationConfig.require(
                round1.plans().getInputFile().equals(round2.plans().getInputFile()),
                "Round 2 must start from the same original input population as Round 1");
    }

    static Map<String, Double> constants(Config config) {
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            var parameters = config.scoring().getModes().get(mode);
            ValidateModeChoiceCalibrationConfig.require(parameters != null,
                    "Missing scoring mode in Round 2: " + mode);
            result.put(mode, parameters.getConstant());
        }
        return Map.copyOf(result);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
