package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Safe entry point for the separate synthetic-2019 mode-choice diagnostic run. */
public final class RunMatsim2019ModeChoiceCalibration {
    private RunMatsim2019ModeChoiceCalibration() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateModeChoiceCalibrationConfig.loadAndValidate();
        ModeChoiceCalibrationRunSupport.run(config, false);
    }
}
