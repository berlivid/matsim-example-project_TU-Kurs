package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Safe server-only entry point for the first 2019 mode-choice calibration round. */
public final class RunMatsim2019ModeChoiceCalibrationRound1 {
    private RunMatsim2019ModeChoiceCalibrationRound1() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateModeChoiceCalibrationRound1Config.loadAndValidate(true);
        ModeChoiceCalibrationRunSupport.run(config, false);
    }
}
