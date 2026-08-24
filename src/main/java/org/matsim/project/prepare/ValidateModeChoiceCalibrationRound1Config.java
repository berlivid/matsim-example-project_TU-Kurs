package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Fail-closed validation of the first separate 2019 mode-choice calibration round. */
public final class ValidateModeChoiceCalibrationRound1Config {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_mode_choice_calibration_round_1.xml");
    public static final String RUN_ID = "munich-calibration-2019-round-1";
    public static final String OUTPUT_DIRECTORY =
            "scenarios/munich_calibration_2019/output/mode-choice-round-1";
    public static final Map<String, Double> CONSTANTS = Map.of(
            "car", 0.0, "pt", 0.89, "walk", 0.78, "bike", -0.21);
    public static final Map<String, Double> TARGETS = Map.of(
            "car", 34.0, "pt", 24.0, "bike", 18.0, "walk", 24.0);

    private ValidateModeChoiceCalibrationRound1Config() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only validator accepts no arguments");
        Config config = loadAndValidate(true);
        System.out.printf(Locale.ROOT,
                "MODE-CHOICE ROUND-1 CONFIG VALIDATION PASS%nconfig=%s%n"
                        + "approvedDifferences=runId,outputDirectory,ptConstant,walkConstant,bikeConstant%n"
                        + "constants=%s targets=%s iterations=%d..%d output=%s%n",
                CONFIG, CONSTANTS, TARGETS, config.controller().getFirstIteration(),
                config.controller().getLastIteration(), config.controller().getOutputDirectory());
    }

    public static Config loadAndValidate(boolean requireUnusedOutput) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing round-1 config: " + CONFIG);
        Config baseline = ValidateModeChoiceCalibrationConfig.loadAndValidate();
        String baselineXml = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String roundXml = Files.readString(CONFIG);
        requireOnlyApprovedDifferences(baselineXml, roundXml);

        Config round = ConfigUtils.loadConfig(CONFIG.toString());
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(round.controller().getRunId()),
                "Unexpected round-1 runId");
        ValidateModeChoiceCalibrationConfig.require(
                OUTPUT_DIRECTORY.equals(round.controller().getOutputDirectory()),
                "Unexpected round-1 output directory");
        ValidateModeChoiceCalibrationConfig.require(
                round.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Round-1 output must use failIfDirectoryExists");
        for (var entry : CONSTANTS.entrySet()) {
            var mode = round.scoring().getModes().get(entry.getKey());
            ValidateModeChoiceCalibrationConfig.require(mode != null
                            && close(mode.getConstant(), entry.getValue()),
                    "Unexpected round-1 constant for " + entry.getKey());
        }
        ValidateModeChoiceCalibrationConfig.require(
                "fromSpecifiedModesToSpecifiedModes".equals(
                        round.subtourModeChoice().getBehavior().toString()),
                "Open-tour behavior must not be active in round 1");
        validateTargets();
        if (requireUnusedOutput) ModeChoiceCalibrationRunSupport.requireOutputAbsent(
                Path.of(OUTPUT_DIRECTORY));
        return round;
    }

    static void requireOnlyApprovedDifferences(String baselineXml, String roundXml) {
        String normalized = normalizeNewlines(roundXml);
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"outputDirectory\" value=\"" + OUTPUT_DIRECTORY + "\" />",
                "<param name=\"outputDirectory\" value=\"scenarios/munich_calibration_2019/output/mode-choice-initial\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"runId\" value=\"" + RUN_ID + "\" />",
                "<param name=\"runId\" value=\"munich-calibration-2019-initial\" />");
        normalized = replaceModeConstant(normalized, "pt", "0.89", "0.0");
        normalized = replaceModeConstant(normalized, "walk", "0.78", "0.0");
        normalized = replaceModeConstant(normalized, "bike", "-0.21", "0.0");
        ValidateModeChoiceCalibrationConfig.require(
                normalizeNewlines(baselineXml).equals(normalized),
                "Round-1 config differs from production beyond the five approved values");
    }

    private static String replaceModeConstant(String xml, String mode,
                                              String fromValue, String toValue) {
        String from = "<param name=\"mode\" value=\"" + mode + "\" />\n"
                + "                <param name=\"constant\" value=\"" + fromValue + "\" />";
        String to = "<param name=\"mode\" value=\"" + mode + "\" />\n"
                + "                <param name=\"constant\" value=\"" + toValue + "\" />";
        return replaceExactlyOnce(xml, from, to);
    }

    private static String replaceExactlyOnce(String text, String from, String to) {
        int first = text.indexOf(from);
        ValidateModeChoiceCalibrationConfig.require(first >= 0,
                "Approved round-1 value is missing: " + from);
        ValidateModeChoiceCalibrationConfig.require(
                text.indexOf(from, first + from.length()) < 0,
                "Approved round-1 value occurs more than once: " + from);
        return text.substring(0, first) + to + text.substring(first + from.length());
    }

    private static void validateTargets() throws IOException {
        Map<String, Double> actual = new LinkedHashMap<>();
        for (var target : ModeChoiceCalibrationTargets.read(
                ModeChoiceCalibrationTargets.DEFAULT_FILE)) {
            if ("trip_modal_share".equals(target.metric())) {
                actual.put(target.mode(), target.numericValue());
                ValidateModeChoiceCalibrationConfig.require(
                        "BOTH_INSIDE".equals(target.spatialScope())
                                && "ALL_PLANS".equals(target.planEligibility()),
                        "Round-1 trip target has an incompatible scope: " + target.mode());
            }
        }
        ValidateModeChoiceCalibrationConfig.require(actual.equals(TARGETS),
                "Round-1 modal-share targets changed: " + actual);
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n");
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 1e-12;
    }
}
