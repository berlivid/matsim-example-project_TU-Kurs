package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Path;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Explicit productive entry point; invoking this class starts the controller. */
public final class RunMatsim2019ResidentModeChoiceCalibration {
    private RunMatsim2019ResidentModeChoiceCalibration() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This runner accepts no arguments and cannot select another scenario");
        Config config = ValidateResidentModeChoiceCalibrationConfig.loadAndValidate();
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(
                Path.of(config.controller().getOutputDirectory()));
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
        ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(
                Path.of(config.controller().getOutputDirectory()));

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
        controler.run();
    }
}
