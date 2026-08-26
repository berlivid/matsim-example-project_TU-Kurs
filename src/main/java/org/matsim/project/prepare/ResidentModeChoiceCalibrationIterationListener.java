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
import org.matsim.core.population.PopulationUtils;

/** Uses the complete selected-plan snapshot of the runtime Munich-resident cohort. */
public final class ResidentModeChoiceCalibrationIterationListener
        implements AfterMobsimListener {
    public static final long EXPECTED_RESIDENT_MAIN_TRIPS = 137_540;
    public static final long EXPECTED_BOTH_INSIDE = 123_186;
    public static final long EXPECTED_ORIGIN_ONLY = 7_177;
    public static final long EXPECTED_DESTINATION_ONLY = 7_177;

    private final Scenario scenario;
    private final ModeChoiceCalibrationAnalysis analysis;
    private final ResidentModeChoiceCalibrationAnalysisWriter writer;
    private final List<ModeChoiceCalibrationAnalysis.AnalysisResult> results = new ArrayList<>();

    @Inject
    public ResidentModeChoiceCalibrationIterationListener(Scenario scenario, Config config) {
        this.scenario = scenario;
        try {
            analysis = new ModeChoiceCalibrationAnalysis(
                    scenario, MunichMunicipalBoundary.loadDefault());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load the unchanged Munich boundary", exception);
        }
        writer = new ResidentModeChoiceCalibrationAnalysisWriter(
                Path.of(config.controller().getOutputDirectory()));
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        Map<Id<Person>, Plan> residentPlans = selectedResidentPlanSnapshot(scenario);
        ModeChoiceCalibrationAnalysis.AnalysisResult result =
                analysis.analyze(event.getIteration(), residentPlans);
        validateResidentStructure(result, residentPlans.size(), event.getIteration());
        results.add(result);
        try {
            writer.write(results, event.isLastIteration());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write resident calibration analysis", exception);
        }
    }

    static Map<Id<Person>, Plan> selectedResidentPlanSnapshot(Scenario scenario) {
        TreeMap<Id<Person>, Plan> residents = new TreeMap<>();
        long regional = 0;
        long unresolved = 0;
        for (var entry : scenario.getPopulation().getPersons().entrySet()) {
            Person person = entry.getValue();
            String subpopulation = PopulationUtils.getSubpopulation(person);
            ValidateModeChoiceCalibrationConfig.require(subpopulation != null,
                    "Runtime subpopulation is missing for person " + entry.getKey());
            switch (subpopulation) {
                case ResidentCalibrationSubpopulations.MUNICH_RESIDENT -> {
                    ValidateModeChoiceCalibrationConfig.require(person.getSelectedPlan() != null,
                            "Munich resident has no selected plan: " + entry.getKey());
                    residents.put(entry.getKey(), person.getSelectedPlan());
                }
                case ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND -> regional++;
                case ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND -> unresolved++;
                default -> throw new IllegalStateException(
                        "Unexpected runtime subpopulation: " + subpopulation);
            }
        }
        if (scenario.getPopulation().getPersons().size()
                == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS) {
            ValidateModeChoiceCalibrationConfig.require(
                    residents.size() == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                    "Runtime Munich-resident cohort changed: " + residents.size());
            ValidateModeChoiceCalibrationConfig.require(
                    regional == ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                    "Runtime regional background changed: " + regional);
            ValidateModeChoiceCalibrationConfig.require(
                    unresolved == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                    "Runtime unresolved background changed: " + unresolved);
        }
        return Map.copyOf(residents);
    }

    static void validateResidentStructure(ModeChoiceCalibrationAnalysis.AnalysisResult result,
                                          long residents, int iteration) {
        ValidateModeChoiceCalibrationConfig.require(
                residents == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                "Resident selected-plan count changed at iteration " + iteration + ": "
                        + residents);
        ValidateModeChoiceCalibrationConfig.require(
                result.unknownMainModes().isEmpty(),
                "Unknown resident main modes at iteration " + iteration + ": "
                        + result.unknownMainModes());
        ValidateModeChoiceCalibrationConfig.require(
                metrics(result, ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS)
                        .validPersons()
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                "Not every Munich resident enters the primary metrics at iteration "
                        + iteration);
        ValidateModeChoiceCalibrationConfig.require(
                result.plansWithClosedSubtour()
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS
                        && result.plansWithoutClosedSubtour() == 0,
                "Resident closed-subtour eligibility changed at iteration " + iteration);
        requireTrips(result, ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                EXPECTED_RESIDENT_MAIN_TRIPS, iteration);
        requireTrips(result, ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                EXPECTED_BOTH_INSIDE, iteration);
        requireTrips(result, ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                EXPECTED_ORIGIN_ONLY, iteration);
        requireTrips(result, ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                EXPECTED_DESTINATION_ONLY, iteration);
        requireTrips(result, ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_OUTSIDE,
                0, iteration);
        requireTrips(result,
                ModeChoiceCalibrationAnalysis.SpatialScope.INVALID_OR_MISSING_COORDINATE,
                0, iteration);
        long spatialSum = List.of(
                        ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                        ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                        ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                        ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_OUTSIDE,
                        ModeChoiceCalibrationAnalysis.SpatialScope.INVALID_OR_MISSING_COORDINATE)
                .stream().mapToLong(scope -> metrics(result, scope).mainTrips()).sum();
        ValidateModeChoiceCalibrationConfig.require(
                spatialSum == EXPECTED_RESIDENT_MAIN_TRIPS,
                "Resident spatial categories do not cover all resident trips at iteration "
                        + iteration + ": " + spatialSum);
    }

    private static void requireTrips(ModeChoiceCalibrationAnalysis.AnalysisResult result,
                                     ModeChoiceCalibrationAnalysis.SpatialScope scope,
                                     long expected, int iteration) {
        long actual = metrics(result, scope).mainTrips();
        ValidateModeChoiceCalibrationConfig.require(actual == expected,
                "Resident " + scope + " trip count changed at iteration " + iteration
                        + ": " + actual + " != " + expected);
    }

    private static ModeChoiceCalibrationAnalysis.MetricSnapshot metrics(
            ModeChoiceCalibrationAnalysis.AnalysisResult result,
            ModeChoiceCalibrationAnalysis.SpatialScope scope) {
        return result.metrics(scope, ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
    }
}
