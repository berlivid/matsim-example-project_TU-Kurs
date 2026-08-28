package org.matsim.project.prepare;

/** Recovery-only entry point for an already completed Round-2 output. */
public final class AnalyzeLiteratureBasedScoringCalibrationRound2 {
    private AnalyzeLiteratureBasedScoringCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        ValidateLiteratureBasedScoringCalibrationRound2Config.require(
                args.length == 0, "The Round-2 recovery analyzer accepts no arguments");
        summarizeExistingOutput();
    }

    static void summarizeExistingOutput() throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(false);
        AnalyzeLiteratureBasedScoringCalibrationRound1.summarizeExistingOutput(
                ValidateLiteratureBasedScoringCalibrationRound2Config.definition(), config);
    }
}
