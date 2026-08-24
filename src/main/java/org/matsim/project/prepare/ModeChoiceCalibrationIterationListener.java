package org.matsim.project.prepare;

import com.google.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

/** Analyzes the complete selected-plan snapshot after mobsim and before the next replanning step. */
public final class ModeChoiceCalibrationIterationListener implements AfterMobsimListener {
    static final long EXPECTED_PERSONS = 324_043;
    static final long EXPECTED_MAIN_TRIPS = 540_468;
    static final long EXPECTED_BOTH_INSIDE_TRIPS = 160_603;
    private final Scenario scenario;
    private final ModeChoiceCalibrationAnalysis analysis;
    private final ModeChoiceCalibrationAnalysisWriter writer;
    private final List<ModeChoiceCalibrationAnalysis.AnalysisResult> results = new ArrayList<>();

    @Inject
    public ModeChoiceCalibrationIterationListener(Scenario scenario, Config config) {
        this.scenario = scenario;
        try {
            this.analysis = new ModeChoiceCalibrationAnalysis(
                    scenario, MunichMunicipalBoundary.loadDefault());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load the unchanged Munich boundary", exception);
        }
        this.writer = new ModeChoiceCalibrationAnalysisWriter(
                Path.of(config.controller().getOutputDirectory()));
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        var plans = selectedPlanSnapshot(scenario);
        var result = analysis.analyze(event.getIteration(), plans);
        validateProductionStructure(result, plans.size(), event.getIteration());
        results.add(result);
        try {
            writer.write(results, event.isLastIteration());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write calibration analysis", exception);
        }
    }

    static Map<Id<Person>, Plan> selectedPlanSnapshot(Scenario scenario) {
        TreeMap<Id<Person>, Plan> plans = new TreeMap<>();
        scenario.getPopulation().getPersons().forEach((id, person) -> {
            ValidateModeChoiceCalibrationConfig.require(person.getSelectedPlan() != null,
                    "Person has no selected plan: " + id);
            plans.put(id, person.getSelectedPlan());
        });
        return Map.copyOf(plans);
    }

    static void validateProductionStructure(ModeChoiceCalibrationAnalysis.AnalysisResult result,
                                             long persons, int iteration) {
        var all = result.metrics(ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
        var inside = result.metrics(ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
        ValidateModeChoiceCalibrationConfig.require(persons == EXPECTED_PERSONS,
                "Selected-plan person count changed at iteration " + iteration + ": " + persons);
        ValidateModeChoiceCalibrationConfig.require(all.mainTrips() == EXPECTED_MAIN_TRIPS,
                "Selected-plan main-trip count changed at iteration " + iteration + ": "
                        + all.mainTrips());
        ValidateModeChoiceCalibrationConfig.require(inside.mainTrips() == EXPECTED_BOTH_INSIDE_TRIPS,
                "Selected-plan BOTH_INSIDE count changed at iteration " + iteration + ": "
                        + inside.mainTrips());
    }
}
