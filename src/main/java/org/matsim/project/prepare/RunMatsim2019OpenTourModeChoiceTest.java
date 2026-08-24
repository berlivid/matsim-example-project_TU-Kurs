package org.matsim.project.prepare;

import org.matsim.core.config.Config;

/** Safe entry point for the isolated five-iteration open-tour test. */
public final class RunMatsim2019OpenTourModeChoiceTest {
    private RunMatsim2019OpenTourModeChoiceTest() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateOpenTourModeChoiceTestConfig.loadAndValidate(true);
        ModeChoiceCalibrationRunSupport.run(config, true);
    }
}
