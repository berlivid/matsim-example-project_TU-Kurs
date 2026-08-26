package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

/** Server-only protected iteration-0 validation entry point. */
public final class RunMatsim2019ResidentModeChoiceIteration0Validation {
    public static final String RUN_ID =
            "munich-calibration-2019-resident-iteration-0-validation";
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/"
                    + "resident-mode-choice-iteration-0-validation");
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@lastIteration",
            "module[controller]/@outputDirectory",
            "module[controller]/@runId");

    private RunMatsim2019ResidentModeChoiceIteration0Validation() { }

    public static void main(String[] args) throws Exception {
        try {
            ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                    "The iteration-0 runner accepts no arguments");
            Map<Path, String> protectedBefore =
                    ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
            Config config = createValidationConfig();
            ResidentModeChoiceCalibrationRunSupport.run(config);
            var result = ValidateResidentModeChoiceIteration0Output
                    .validateAndWrite(protectedBefore);
            System.out.println(result.reviewRequired()
                    ? "ITERATION-0 VALIDATION PASS WITH REVIEW REQUIRED"
                    : "ITERATION-0 VALIDATION PASS");
        } catch (Throwable failure) {
            System.err.println("ITERATION-0 VALIDATION FAIL");
            if (failure instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    static Config createValidationConfig() throws Exception {
        Config production = ValidateResidentModeChoiceCalibrationConfig.loadAndValidate();
        Config validation = applyApprovedOverrides(production);
        requireOutputsAbsent();
        return validation;
    }

    static Config applyApprovedOverrides(Config production) {
        Map<String, String> before = snapshot(production);
        production.controller().setRunId(RUN_ID);
        production.controller().setOutputDirectory(OUTPUT.toString());
        production.controller().setLastIteration(0);
        validateApprovedOverrides(before, production);
        return production;
    }

    static void validateApprovedOverrides(Map<String, String> productionSnapshot,
                                          Config validation) {
        Map<String, String> after = snapshot(validation);
        Set<String> changed = differences(productionSnapshot, after);
        ValidateModeChoiceCalibrationConfig.require(changed.equals(APPROVED_DIFFERENCES),
                "Iteration-0 config differs in fields other than the three approved "
                        + "controller overrides: " + changed);
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(
                        validation.controller().getRunId()),
                "Unexpected iteration-0 validation runId");
        ValidateModeChoiceCalibrationConfig.require(normalize(OUTPUT).equals(normalize(
                        Path.of(validation.controller().getOutputDirectory()))),
                "Unexpected iteration-0 validation output");
        ValidateModeChoiceCalibrationConfig.require(
                validation.controller().getFirstIteration() == 0
                        && validation.controller().getLastIteration() == 0,
                "Iteration-0 validation must execute exactly iteration 0");
        ValidateModeChoiceCalibrationConfig.require(
                !normalize(ValidateResidentModeChoiceCalibrationConfig.OUTPUT).equals(
                        normalize(Path.of(validation.controller().getOutputDirectory()))),
                "Productive output directory cannot be used by iteration-0 validation");
    }

    static void requireOutputsAbsent() {
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(
                ValidateResidentModeChoiceCalibrationConfig.OUTPUT);
    }

    static Map<String, String> snapshot(Config config) {
        TreeMap<String, String> result = new TreeMap<>();
        config.getModules().forEach((name, group) -> flatten(
                "module[" + name + "]", group, result));
        return Map.copyOf(result);
    }

    static Set<String> differences(Map<String, String> before,
                                   Map<String, String> after) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return Set.copyOf(keys);
    }

    private static void flatten(String prefix, ConfigGroup group,
                                Map<String, String> result) {
        group.getParams().forEach((name, value) -> result.put(prefix + "/@" + name, value));
        TreeMap<String, Collection<? extends ConfigGroup>> sets =
                new TreeMap<>(group.getParameterSets());
        for (var entry : sets.entrySet()) {
            List<? extends ConfigGroup> ordered = new ArrayList<>(entry.getValue());
            for (int index = 0; index < ordered.size(); index++) {
                flatten(prefix + "/set[" + entry.getKey() + "][" + index + "]",
                        ordered.get(index), result);
            }
        }
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
