package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Thin read-only validator/postprocessor for a completed resident Round-4 run. */
public final class ValidateAndSummarizeResidentModeChoiceCalibrationRound4 {
    private ValidateAndSummarizeResidentModeChoiceCalibrationRound4() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-4 output validator accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound4Config
                .loadAndValidateStructure(false);
        AnalyzeInitial2019ResidentModeChoiceOutput.validateNormallyCompletedOutput(
                config, ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT);
        var output = AnalyzeInitial2019ResidentModeChoiceOutput.analyzeOutput(
                config, ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT);
        var review = ResidentModeChoiceRound2Review.validateAndWriteRound4(
                ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT,
                ResidentModeChoiceRound4Specification.ROUND_3_ANALYSIS);
        System.out.printf("RESIDENT MODE-CHOICE ROUND-4 OUTPUT VALIDATION %s%n"
                        + "normalShutdown=PASS semanticOutputConfig=PASS%n"
                        + "residentPersons=%d residentTrips=%d affectedResidentTrips=%d%n"
                        + "lateIterations=51..60 stuckStatus=%s output=%s%n",
                review.overallStatus(), output.residentPersons(), output.residentTrips(),
                output.sensitivity().affectedMainTrips(), review.stuckStatus(),
                ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT);
    }
}
