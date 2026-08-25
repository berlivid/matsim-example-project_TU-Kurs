package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Fail-closed validation of the second separate 2019 mode-choice calibration round. */
public final class ValidateModeChoiceCalibrationRound2Config {
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_mode_choice_calibration_round_2.xml");
    public static final String RUN_ID = "munich-calibration-2019-round-2";
    public static final String OUTPUT_DIRECTORY =
            "scenarios/munich_calibration_2019/output/mode-choice-round-2";
    public static final Map<String, Double> CONSTANTS = Map.of(
            "car", 0.0, "pt", 1.27, "walk", 1.27, "bike", -0.34);
    public static final Map<String, Double> TARGETS = Map.of(
            "car", 34.0, "pt", 24.0, "bike", 18.0, "walk", 24.0);

    private ValidateModeChoiceCalibrationRound2Config() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only validator accepts no arguments");
        Config config = loadAndValidate(true);
        System.out.printf(Locale.ROOT,
                "MODE-CHOICE ROUND-2 CONFIG VALIDATION PASS%nconfig=%s%n"
                        + "approvedDifferences=runId,outputDirectory,lastIteration,"
                        + "innovationDisableFraction,ptConstant,walkConstant,bikeConstant%n"
                        + "constants=%s targets=%s iterations=%d..%d innovationDisableFraction=%.3f output=%s%n",
                CONFIG, CONSTANTS, TARGETS, config.controller().getFirstIteration(),
                config.controller().getLastIteration(),
                config.replanning().getFractionOfIterationsToDisableInnovation(),
                config.controller().getOutputDirectory());
    }

    public static Config loadAndValidate(boolean requireUnusedOutput) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing round-2 config: " + CONFIG);
        ValidateModeChoiceCalibrationRound1Config.loadAndValidate(false);
        String round1Xml = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG);
        String round2Xml = Files.readString(CONFIG);
        requireOnlyApprovedDifferences(round1Xml, round2Xml);

        Config round = ConfigUtils.loadConfig(CONFIG.toString());
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(round.controller().getRunId()),
                "Unexpected round-2 runId");
        ValidateModeChoiceCalibrationConfig.require(
                OUTPUT_DIRECTORY.equals(round.controller().getOutputDirectory()),
                "Unexpected round-2 output directory");
        ValidateModeChoiceCalibrationConfig.require(
                round.controller().getFirstIteration() == 0
                        && round.controller().getLastIteration() == 40,
                "Round 2 must contain exactly iterations 0..40");
        ValidateModeChoiceCalibrationConfig.require(close(
                        round.replanning().getFractionOfIterationsToDisableInnovation(), 0.6),
                "Round-2 innovation-disable fraction must be 0.6");
        ValidateModeChoiceCalibrationConfig.require(
                round.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Round-2 output must use failIfDirectoryExists");
        for (var entry : CONSTANTS.entrySet()) {
            var mode = round.scoring().getModes().get(entry.getKey());
            ValidateModeChoiceCalibrationConfig.require(mode != null
                            && close(mode.getConstant(), entry.getValue()),
                    "Unexpected round-2 constant for " + entry.getKey());
        }
        ValidateModeChoiceCalibrationConfig.require(
                "fromSpecifiedModesToSpecifiedModes".equals(
                        round.subtourModeChoice().getBehavior().toString()),
                "Open-tour behavior must not be active in round 2");
        validateTargets();
        if (requireUnusedOutput) ModeChoiceCalibrationRunSupport.requireOutputAbsent(
                Path.of(OUTPUT_DIRECTORY));
        return round;
    }

    static void requireOnlyApprovedDifferences(String round1Xml, String round2Xml) {
        String normalized = semanticXml(round2Xml);
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"outputDirectory\" value=\"" + OUTPUT_DIRECTORY + "\" />",
                "<param name=\"outputDirectory\" value=\""
                        + ValidateModeChoiceCalibrationRound1Config.OUTPUT_DIRECTORY + "\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"runId\" value=\"" + RUN_ID + "\" />",
                "<param name=\"runId\" value=\""
                        + ValidateModeChoiceCalibrationRound1Config.RUN_ID + "\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"lastIteration\" value=\"40\" />",
                "<param name=\"lastIteration\" value=\"20\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"fractionOfIterationsToDisableInnovation\" value=\"0.6\" />",
                "<param name=\"fractionOfIterationsToDisableInnovation\" value=\"0.8\" />");
        normalized = replaceModeConstant(normalized, "pt", "1.27", "0.89");
        normalized = replaceModeConstant(normalized, "walk", "1.27", "0.78");
        normalized = replaceModeConstant(normalized, "bike", "-0.34", "-0.21");
        ValidateModeChoiceCalibrationConfig.require(semanticXml(round1Xml).equals(normalized),
                "Round-2 config differs from round 1 beyond the approved values");
    }

    private static String replaceModeConstant(String xml, String mode,
                                              String fromValue, String toValue) {
        return replaceExactlyOnce(xml,
                "<param name=\"mode\" value=\"" + mode + "\" />"
                        + "<param name=\"constant\" value=\"" + fromValue + "\" />",
                "<param name=\"mode\" value=\"" + mode + "\" />"
                        + "<param name=\"constant\" value=\"" + toValue + "\" />");
    }

    private static String replaceExactlyOnce(String text, String from, String to) {
        int first = text.indexOf(from);
        ValidateModeChoiceCalibrationConfig.require(first >= 0,
                "Approved round-2 value is missing: " + from);
        ValidateModeChoiceCalibrationConfig.require(
                text.indexOf(from, first + from.length()) < 0,
                "Approved round-2 value occurs more than once: " + from);
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
                        "Round-2 trip target has an incompatible scope: " + target.mode());
            }
        }
        ValidateModeChoiceCalibrationConfig.require(actual.equals(TARGETS),
                "Round-2 modal-share targets changed: " + actual);
    }

    private static String semanticXml(String text) {
        String normalized = text.replace("\r\n", "\n");
        return XML_COMMENT.matcher(normalized).replaceAll("")
                .replaceAll(">\\s+<", "><").trim();
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 1e-12;
    }
}
