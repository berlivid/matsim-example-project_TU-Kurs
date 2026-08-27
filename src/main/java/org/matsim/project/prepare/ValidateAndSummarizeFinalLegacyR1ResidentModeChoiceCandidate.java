package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import org.matsim.core.config.Config;

/** Thin read-only postprocessor for the completed final Legacy-R1 candidate. */
public final class ValidateAndSummarizeFinalLegacyR1ResidentModeChoiceCandidate {
    private ValidateAndSummarizeFinalLegacyR1ResidentModeChoiceCandidate() { }

    public static void main(String[] args) throws Exception {
        try {
            ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                    "F1C accepts no arguments");
            Config config = ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                    .loadAndValidateStructure(false);
            Path output = ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.OUTPUT;
            AnalyzeInitial2019ResidentModeChoiceOutput.validateNormallyCompletedOutput(
                    config, output);
            Path scoreStatistics = output.resolve(
                    ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.RUN_ID
                            + ".scorestats.csv");
            ValidateModeChoiceCalibrationConfig.require(
                    Files.isRegularFile(scoreStatistics),
                    "Final-candidate score statistics are missing: " + scoreStatistics);
            var analyzed = AnalyzeInitial2019ResidentModeChoiceOutput.analyzeOutput(
                    config, output);
            var review = ResidentModeChoiceRound2Review.validateAndWriteFinalCandidate(
                    output,
                    ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                            .LEGACY_REANALYSIS.resolve(
                            "legacy_resident_mode_choice_comparison.csv"));
            System.out.printf("FINAL LEGACY-R1 RESIDENT CANDIDATE %s%n"
                            + "normalShutdown=PASS semanticOutputConfig=PASS "
                            + "scoreStatistics=PASS%n"
                            + "residentPersons=%d residentTrips=%d "
                            + "affectedResidentTrips=%d%n"
                            + "lateIterations=51..60 stuckStatus=%s output=%s%n",
                    review.overallStatus(), analyzed.residentPersons(),
                    analyzed.residentTrips(),
                    analyzed.sensitivity().affectedMainTrips(),
                    review.stuckStatus(), output);
        } catch (Exception exception) {
            System.err.println("FINAL LEGACY-R1 RESIDENT CANDIDATE TECHNICAL_FAILURE: "
                    + exception.getMessage());
            throw exception;
        }
    }
}
