package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Thin server-only entry point using the shared productive resident runner. */
public final class RunMatsim2019FinalLegacyR1ResidentModeChoiceCandidate {
    private RunMatsim2019FinalLegacyR1ResidentModeChoiceCandidate() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "F1B accepts no arguments");
        Config config = ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .loadAndValidate();
        ResidentModeChoiceCalibrationRunSupport.run(config);
    }
}
