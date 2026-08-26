package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Explicit productive entry point; invoking this class starts the controller. */
public final class RunMatsim2019ResidentModeChoiceCalibration {
    private RunMatsim2019ResidentModeChoiceCalibration() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateResidentModeChoiceCalibrationConfig.loadAndValidate();
        ResidentModeChoiceCalibrationRunSupport.run(config);
    }
}
