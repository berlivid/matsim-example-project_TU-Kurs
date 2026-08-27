package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Thin read-only validator/postprocessor for a completed resident Round-3 run. */
public final class ValidateAndSummarizeResidentModeChoiceCalibrationRound3 {
    private ValidateAndSummarizeResidentModeChoiceCalibrationRound3() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-3 output validator accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound3Config
                .loadAndValidateStructure(false);
        AnalyzeInitial2019ResidentModeChoiceOutput.validateNormallyCompletedOutput(
                config, ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT);
        var output = AnalyzeInitial2019ResidentModeChoiceOutput.analyzeOutput(
                config, ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT);
        var review = ResidentModeChoiceRound2Review.validateAndWriteRound3(
                ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT,
                ResidentModeChoiceRound3Specification.ROUND_2_ANALYSIS);
        System.out.printf("RESIDENT MODE-CHOICE ROUND-3 OUTPUT VALIDATION %s%n"
                        + "normalShutdown=PASS semanticOutputConfig=PASS%n"
                        + "residentPersons=%d residentTrips=%d affectedResidentTrips=%d%n"
                        + "lateIterations=51..60 stuckStatus=%s output=%s%n",
                review.overallStatus(), output.residentPersons(), output.residentTrips(),
                output.sensitivity().affectedMainTrips(), review.stuckStatus(),
                ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT);
    }
}
