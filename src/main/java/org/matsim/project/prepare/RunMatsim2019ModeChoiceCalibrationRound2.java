package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Safe server-only entry point for the second 2019 mode-choice calibration round. */
public final class RunMatsim2019ModeChoiceCalibrationRound2 {
    private RunMatsim2019ModeChoiceCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateModeChoiceCalibrationRound2Config.loadAndValidate(true);
        ModeChoiceCalibrationRunSupport.run(config, false);
    }
}
