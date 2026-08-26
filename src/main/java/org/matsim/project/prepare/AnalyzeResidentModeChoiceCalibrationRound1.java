package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Read-only postprocessor for a completed productive resident Round-1 output. */
public final class AnalyzeResidentModeChoiceCalibrationRound1 {
    private AnalyzeResidentModeChoiceCalibrationRound1() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-1 analyzer accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound1Config
                .loadAndValidateStructure(false);
        var output = AnalyzeInitial2019ResidentModeChoiceOutput.analyzeOutput(
                config, ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT);
        var review = ResidentModeChoiceRound1Review.validateAndWrite(
                ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT);
        System.out.printf("RESIDENT MODE-CHOICE ROUND-1 ANALYSIS %s%n"
                        + "residentPersons=%d residentTrips=%d affectedResidentTrips=%d%n"
                        + "lateIterations=31..40 output=%s%n",
                review.status(), output.residentPersons(), output.residentTrips(),
                output.sensitivity().affectedMainTrips(),
                ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT);
    }
}
