package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Explicit server-only entry point for productive resident calibration Round 1. */
public final class RunMatsim2019ResidentModeChoiceCalibrationRound1 {
    private RunMatsim2019ResidentModeChoiceCalibrationRound1() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-1 runner accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound1Config.loadAndValidate();
        ResidentModeChoiceCalibrationRunSupport.run(config);
    }
}
