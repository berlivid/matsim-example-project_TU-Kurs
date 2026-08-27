package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Explicit server-only entry point for productive resident calibration Round 2. */
public final class RunMatsim2019ResidentModeChoiceCalibrationRound2 {
    private RunMatsim2019ResidentModeChoiceCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-2 runner accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound2Config.loadAndValidate();
        ResidentModeChoiceCalibrationRunSupport.run(config);
    }
}
