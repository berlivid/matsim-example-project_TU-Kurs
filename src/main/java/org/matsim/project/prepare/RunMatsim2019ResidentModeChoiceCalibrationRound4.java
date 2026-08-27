package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Thin server-only entry point using the shared productive resident runner. */
public final class RunMatsim2019ResidentModeChoiceCalibrationRound4 {
    private RunMatsim2019ResidentModeChoiceCalibrationRound4() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "Resident Round-4 runner accepts no arguments");
        Config config = ValidateResidentModeChoiceCalibrationRound4Config.loadAndValidate();
        ResidentModeChoiceCalibrationRunSupport.run(config);
    }
}
