package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Read-only postprocessor for a completed productive resident Round-2 output. */
public final class AnalyzeResidentModeChoiceCalibrationRound2 {
    private AnalyzeResidentModeChoiceCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-2 analyzer accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound2Config
                .loadAndValidateStructure(false);
        var output = AnalyzeInitial2019ResidentModeChoiceOutput.analyzeOutput(
                config, ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT);
        var review = ResidentModeChoiceRound2Review.validateAndWrite(
                ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT);
        System.out.printf("RESIDENT MODE-CHOICE ROUND-2 ANALYSIS %s%n"
                        + "residentPersons=%d residentTrips=%d affectedResidentTrips=%d%n"
                        + "lateIterations=51..60 stuckStatus=%s output=%s%n",
                review.overallStatus(), output.residentPersons(), output.residentTrips(),
                output.sensitivity().affectedMainTrips(), review.stuckStatus(),
                ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT);
    }
}
