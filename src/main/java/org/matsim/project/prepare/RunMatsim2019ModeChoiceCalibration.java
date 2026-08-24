package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Safe entry point for the separate synthetic-2019 mode-choice diagnostic run. */
public final class RunMatsim2019ModeChoiceCalibration {
    private RunMatsim2019ModeChoiceCalibration() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateModeChoiceCalibrationConfig.loadAndValidate();
        Path output = Path.of(config.controller().getOutputDirectory());
        ValidateModeChoiceCalibrationConfig.require(!Files.exists(output),
                "Calibration output already exists; nothing was deleted: " + output);

        var scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.run();
    }
}
