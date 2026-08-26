package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Read-only fail-closed validator for productive resident calibration Round 1. */
public final class ValidateResidentModeChoiceCalibrationRound1Config {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/"
                    + "config_resident_mode_choice_calibration_round_1.xml");
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/resident-mode-choice-round-1");
    public static final String RUN_ID = "munich-calibration-2019-resident-round-1";
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@lastIteration",
            "module[controller]/@outputDirectory",
            "module[controller]/@runId",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][1]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][2]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][3]/@constant");

    private ValidateResidentModeChoiceCalibrationRound1Config() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Round-1 validator accepts no arguments");
        Config config = loadAndValidate();
        ResidentModeChoiceRound1Specification.Validation initial =
                ResidentModeChoiceRound1Specification.validate();
        System.out.printf(Locale.ROOT,
                "RESIDENT MODE-CHOICE ROUND-1 CONFIG VALIDATION PASS%n"
                        + "config=%s%noutput=%s%nrunId=%s iterations=0..%d%n"
                        + "constants=%s%ninitialIterations=%d initialResidentTrips=%d "
                        + "initialAffectedTrips=%d%nNo Controller or QSim was started.%n",
                CONFIG, OUTPUT, RUN_ID, config.controller().getLastIteration(),
                constants(config), initial.iterations(), initial.residentTrips(),
                initial.affectedTrips());
    }

    static Config loadAndValidate() throws Exception {
        Config round = loadAndValidateStructure(true);
        ValidateResidentModeChoiceCalibrationConfig.validateAuthoritativeCohort(round);
        return round;
    }

    static Config loadAndValidateStructure(boolean requireOutputAbsent) throws Exception {
        ResidentModeChoiceRound1Specification.validate();
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing resident Round-1 config: " + CONFIG);
        Config production = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        Config round = ConfigUtils.loadConfig(CONFIG.toString());
        Map<String, String> productionSnapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production);
        Map<String, String> roundSnapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(productionSnapshot, roundSnapshot);
        ValidateModeChoiceCalibrationConfig.require(differences.equals(APPROVED_DIFFERENCES),
                "Round-1 config differs from the productive initial config outside the "
                        + "approved controller fields and constants: " + differences);
        validateApprovedValues(round);
        if (requireOutputAbsent) {
            ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        }
        return round;
    }

    private static void validateApprovedValues(Config config) {
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(
                        config.controller().getRunId()),
                "Unexpected resident Round-1 runId");
        ValidateModeChoiceCalibrationConfig.require(normalize(OUTPUT).equals(normalize(
                        Path.of(config.controller().getOutputDirectory()))),
                "Unexpected resident Round-1 output directory");
        ValidateModeChoiceCalibrationConfig.require(
                config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == 40,
                "Resident Round 1 must run iterations 0..40");
        ValidateModeChoiceCalibrationConfig.require(
                config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Resident Round-1 output must remain fail-if-exists protected");
        ValidateModeChoiceCalibrationConfig.require(!normalize(OUTPUT).equals(
                        normalize(ValidateResidentModeChoiceCalibrationConfig.OUTPUT)),
                "Round 1 cannot write to the protected initial output");
        Map<String, Double> actual = constants(config);
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ValidateModeChoiceCalibrationConfig.require(Math.abs(actual.get(mode)
                            - ResidentModeChoiceRound1Specification.APPLIED_CONSTANTS.get(mode))
                            <= 1e-12,
                    "Unexpected approved Round-1 constant for " + mode + ": "
                            + actual.get(mode));
        }
        ValidateModeChoiceCalibrationConfig.require(
                config.qsim().getEndTime().isDefined()
                        && Math.abs(config.qsim().getEndTime().seconds() - 48 * 3600.0) < 1e-9,
                "Resident Round-1 QSim horizon must remain 48:00:00");
    }

    static Map<String, Double> constants(Config config) {
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            var parameters = config.scoring().getModes().get(mode);
            ValidateModeChoiceCalibrationConfig.require(parameters != null,
                    "Missing scoring mode in Round 1: " + mode);
            result.put(mode, parameters.getConstant());
        }
        return Map.copyOf(result);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
