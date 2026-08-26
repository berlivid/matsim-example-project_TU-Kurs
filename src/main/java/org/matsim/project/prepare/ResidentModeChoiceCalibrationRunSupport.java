package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Path;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** One shared scenario and controller setup for productive resident calibration runs. */
final class ResidentModeChoiceCalibrationRunSupport {
    private ResidentModeChoiceCalibrationRunSupport() { }

    static void run(Config config) throws Exception {
        Scenario scenario = loadAndValidateScenario(config);
        Controler controler = createControler(scenario);
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(
                Path.of(config.controller().getOutputDirectory()));
        controler.run();
    }

    static Scenario loadAndValidateScenario(Config config) throws Exception {
        Path output = Path.of(config.controller().getOutputDirectory());
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(output);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        ResidentCalibrationSubpopulations.assignAndValidate(
                scenario.getPopulation(), MunichMunicipalBoundary.loadDefault());
        var residentPlans = ResidentModeChoiceCalibrationIterationListener
                .selectedResidentPlanSnapshot(scenario);
        var structuralAnalysis = new ModeChoiceCalibrationAnalysis(
                scenario, MunichMunicipalBoundary.loadDefault())
                .analyze(-1, residentPlans);
        ResidentModeChoiceCalibrationIterationListener.validateResidentStructure(
                structuralAnalysis, residentPlans.size(), -1);
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(output);
        return scenario;
    }

    static Controler createControler(Scenario scenario) {
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addControlerListenerBinding().to(
                        ResidentModeChoiceCalibrationIterationListener.class);
                bind(ResidentModeChoiceStuckEventListener.class).asEagerSingleton();
                addEventHandlerBinding().to(ResidentModeChoiceStuckEventListener.class);
                addControlerListenerBinding().to(ResidentModeChoiceStuckEventListener.class);
            }
        });
        return controler;
    }
}
