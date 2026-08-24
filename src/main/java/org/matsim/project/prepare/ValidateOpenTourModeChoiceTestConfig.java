package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.modules.SubtourModeChoice;

/** Read-only, fail-closed validation of the isolated open-tour mode-choice test. */
public final class ValidateOpenTourModeChoiceTestConfig {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_mode_choice_open_tour_test.xml");
    public static final String RUN_ID = "munich-calibration-2019-open-tour-test";
    public static final String OUTPUT_DIRECTORY =
            "scenarios/munich_calibration_2019/output/mode-choice-open-tour-test";
    public static final String BEHAVIOR = "betweenAllAndFewerConstraints";

    private ValidateOpenTourModeChoiceTestConfig() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only validator accepts no arguments");
        Config config = loadAndValidate(true);
        System.out.printf(Locale.ROOT,
                "OPEN-TOUR TEST CONFIG VALIDATION PASS%nconfig=%s%n"
                        + "approvedDifferences=runId,outputDirectory,lastIteration,behavior%n"
                        + "behavior=%s iterations=%d..%d output=%s%n",
                CONFIG, config.subtourModeChoice().getBehavior(),
                config.controller().getFirstIteration(),
                config.controller().getLastIteration(),
                config.controller().getOutputDirectory());
    }

    public static Config loadAndValidate(boolean requireUnusedOutput) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(CONFIG),
                "Missing open-tour test config: " + CONFIG);

        // Regression-check the production calibration config before comparing the test variant.
        Config baseline = ValidateModeChoiceCalibrationConfig.loadAndValidate();
        String baselineXml = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String testXml = Files.readString(CONFIG);
        requireExactlyFourApprovedDifferences(baselineXml, testXml);

        Config test = ConfigUtils.loadConfig(CONFIG.toString());
        ValidateModeChoiceCalibrationConfig.require(
                SubtourModeChoice.Behavior.valueOf(BEHAVIOR)
                        == test.subtourModeChoice().getBehavior(),
                "Installed MATSim does not expose the approved open-tour behavior");
        ValidateModeChoiceCalibrationConfig.require(
                test.controller().getFirstIteration() == 0
                        && test.controller().getLastIteration() == 5,
                "Open-tour test iterations must be exactly 0..5");
        ValidateModeChoiceCalibrationConfig.require(RUN_ID.equals(test.controller().getRunId()),
                "Unexpected open-tour test runId");
        ValidateModeChoiceCalibrationConfig.require(
                OUTPUT_DIRECTORY.equals(test.controller().getOutputDirectory()),
                "Unexpected open-tour test output directory");
        ValidateModeChoiceCalibrationConfig.require(
                test.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Open-tour output must use failIfDirectoryExists");

        // The byte-level XML normalization above is the primary equality proof. These checks make
        // the four intended values explicit after MATSim parsing as well.
        ValidateModeChoiceCalibrationConfig.require(
                baseline.controller().getFirstIteration() == test.controller().getFirstIteration(),
                "firstIteration changed unexpectedly");
        if (requireUnusedOutput) requireOutputAbsent(Path.of(OUTPUT_DIRECTORY));
        return test;
    }

    static void requireExactlyFourApprovedDifferences(String baselineXml, String testXml) {
        String normalized = testXml;
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"lastIteration\" value=\"5\" />",
                "<param name=\"lastIteration\" value=\"20\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"outputDirectory\" value=\"" + OUTPUT_DIRECTORY + "\" />",
                "<param name=\"outputDirectory\" value=\"scenarios/munich_calibration_2019/output/mode-choice-initial\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"runId\" value=\"" + RUN_ID + "\" />",
                "<param name=\"runId\" value=\"munich-calibration-2019-initial\" />");
        normalized = replaceExactlyOnce(normalized,
                "<param name=\"behavior\" value=\"" + BEHAVIOR + "\" />",
                "<param name=\"behavior\" value=\"fromSpecifiedModesToSpecifiedModes\" />");
        ValidateModeChoiceCalibrationConfig.require(baselineXml.equals(normalized),
                "Test config differs from production beyond the four approved values");
    }

    static void requireOutputAbsent(Path output) {
        ModeChoiceCalibrationRunSupport.requireOutputAbsent(output);
    }

    private static String replaceExactlyOnce(String text, String from, String to) {
        int first = text.indexOf(from);
        ValidateModeChoiceCalibrationConfig.require(first >= 0,
                "Approved test-config value is missing: " + from);
        ValidateModeChoiceCalibrationConfig.require(text.indexOf(from, first + from.length()) < 0,
                "Approved test-config value occurs more than once: " + from);
        return text.substring(0, first) + to + text.substring(first + from.length());
    }
}
