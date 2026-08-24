package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Shared safe controller construction for the two synthetic-2019 calibration runs. */
final class ModeChoiceCalibrationRunSupport {
    private ModeChoiceCalibrationRunSupport() { }

    static void run(Config config, boolean includeOpenTourDiagnostics) {
        requireOutputAbsent(Path.of(config.controller().getOutputDirectory()));
        var scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addControlerListenerBinding().to(ModeChoiceCalibrationIterationListener.class);
                if (includeOpenTourDiagnostics) {
                    bind(OpenTourModeChoiceTestDiagnostics.class).asEagerSingleton();
                    addEventHandlerBinding().to(OpenTourModeChoiceTestDiagnostics.class);
                    addControlerListenerBinding().to(OpenTourModeChoiceTestDiagnostics.class);
                }
            }
        });
        controler.run();
    }

    static void requireOutputAbsent(Path output) {
        ValidateModeChoiceCalibrationConfig.require(!Files.exists(output),
                "Mode-choice output already exists; nothing was deleted: " + output);
    }
}
