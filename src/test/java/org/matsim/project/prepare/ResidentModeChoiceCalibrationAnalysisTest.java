package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;

class ResidentModeChoiceCalibrationAnalysisTest {
    private Scenario scenario;
    private MunichMunicipalBoundary boundary;
    private PopulationFactory factory;
    private Coord inside;
    private Coord outside;

    @BeforeEach
    void setUp() throws Exception {
        scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        boundary = MunichMunicipalBoundary.loadDefault();
        factory = scenario.getPopulation().getFactory();
        var interior = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(interior.x, interior.y);
        var envelope = boundary.envelope();
        outside = new Coord(envelope.getMinX() - 10_000,
                envelope.getMinY() - 10_000);
    }

    @Test
    void selectedResidentBoundaryCrossingTripIsIncludedAndNonResidentInsideTripExcluded()
            throws Exception {
        Person resident = factory.createPerson(Id.createPersonId("resident"));
        Plan unselected = oneTrip("walk", activity("home", inside),
                activity("work", inside), 500);
        Plan selectedCrossing = oneTrip("car", activity("home", inside),
                activity("work", outside), 1_000);
        resident.addPlan(unselected);
        resident.addPlan(selectedCrossing);
        resident.setSelectedPlan(selectedCrossing);

        Person nonResident = factory.createPerson(Id.createPersonId("non-resident"));
        Plan nonResidentPlan = plan("pt", activity("home", outside),
                activity("work", inside), activity("shopping", inside));
        nonResident.addPlan(nonResidentPlan);
        nonResident.setSelectedPlan(nonResidentPlan);
        scenario.getPopulation().addPerson(resident);
        scenario.getPopulation().addPerson(nonResident);
        ResidentCalibrationSubpopulations.assign(scenario.getPopulation(), boundary);

        var snapshot = ResidentModeChoiceCalibrationIterationListener
                .selectedResidentPlanSnapshot(scenario);
        assertEquals(1, snapshot.size());
        assertSame(selectedCrossing, snapshot.get(resident.getId()));

        var result = new ModeChoiceCalibrationAnalysis(scenario, boundary)
                .analyze(0, snapshot);
        var primary = metrics(result, ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS);
        assertEquals(1, primary.mainTrips());
        assertEquals(1, primary.mainTripsByMode().get("car"));
        assertEquals(1.0, primary.mainModePkm("car"), 1e-12);
        assertEquals(1, metrics(result,
                ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY).mainTrips());
        assertEquals(0, metrics(result,
                ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE).mainTrips());
        long spatialSum = List.of(
                        ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                        ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                        ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                        ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_OUTSIDE,
                        ModeChoiceCalibrationAnalysis.SpatialScope.INVALID_OR_MISSING_COORDINATE)
                .stream().mapToLong(scope -> metrics(result, scope).mainTrips()).sum();
        assertEquals(primary.mainTrips(), spatialSum);
        assertFalse(snapshot.containsKey(nonResident.getId()));
    }

    @Test
    void writerReportsTargetsAnnualisationAndLateStatistics(@TempDir Path temp)
            throws Exception {
        Person resident = factory.createPerson(Id.createPersonId("resident"));
        Plan plan = oneTrip("car", activity("home", inside),
                activity("work", outside), 1_000);
        resident.addPlan(plan);
        resident.setSelectedPlan(plan);
        scenario.getPopulation().addPerson(resident);
        PopulationUtils.putSubpopulation(resident,
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT);
        var result = new ModeChoiceCalibrationAnalysis(scenario, boundary)
                .analyze(0, ResidentModeChoiceCalibrationIterationListener
                        .selectedResidentPlanSnapshot(scenario));
        ResidentModeChoiceCalibrationAnalysisWriter writer =
                new ResidentModeChoiceCalibrationAnalysisWriter(temp);
        writer.write(List.of(result), true);

        String metrics = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_iteration_metrics.csv"));
        assertTrue(metrics.contains("resident_trip_share,car,100.000000000,percent,34.000000000,66.000000000"));
        assertTrue(metrics.contains("raw_simulated_daily_sample_pkm,car,1.000000000"));
        assertTrue(metrics.contains("five_percent_annualised_pkm_diagnostic,car,0.007300000"));
        assertTrue(metrics.contains("resident_pkm_share,car,100.000000000,percent,62.945329000,37.054671000"));
        String late = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_late_iteration_statistics.csv"));
        assertTrue(late.contains("resident_trip_share,car,0,0,1"));
        assertTrue(Files.readString(temp.resolve(
                        "analysis/resident_mode_choice_report.md"))
                .contains("complete selected scenario-plan snapshot"));
    }

    @Test
    void physicalAndChoiceModalSplitsRemainSeparateAndTargetsUsePhysicalOnly(
            @TempDir Path temp) throws Exception {
        Person resident = factory.createPerson(Id.createPersonId("resident-choice"));
        Plan routedPtRequest = oneTrip("walk", activity("home", inside),
                activity("work", outside), 1_000);
        ((Leg) routedPtRequest.getPlanElements().get(1)).setRoutingMode("pt");
        resident.addPlan(routedPtRequest);
        resident.setSelectedPlan(routedPtRequest);
        scenario.getPopulation().addPerson(resident);
        PopulationUtils.putSubpopulation(resident,
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT);

        var result = new ModeChoiceCalibrationAnalysis(scenario, boundary)
                .analyze(0, ResidentModeChoiceCalibrationIterationListener
                        .selectedResidentPlanSnapshot(scenario));
        var primary = metrics(result, ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS);
        assertEquals(1, primary.mainTripsByMode().get("walk"));
        assertEquals(1, primary.choiceMainTripsByMode().get("pt"));
        assertEquals(100.0, primary.modalSharePercent("walk"), 1e-12);
        assertEquals(100.0, primary.choiceModalSharePercent("pt"), 1e-12);
        assertEquals(1, primary.ptRequestsWithWalkOnlyPhysicalRoute());
        assertEquals(1, primary.physicalChoiceTransitions().get(
                new ModeChoiceCalibrationAnalysis.PhysicalChoiceTransition("walk", "pt")));

        ResidentModeChoiceCalibrationAnalysisWriter writer =
                new ResidentModeChoiceCalibrationAnalysisWriter(temp);
        writer.write(List.of(result), true);
        String csv = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_iteration_metrics.csv"));
        assertTrue(csv.contains(
                "resident_physical_trip_share,walk,100.000000000,percent,24.000000000,76.000000000"));
        assertTrue(csv.contains(
                "resident_choice_trip_share,pt,100.000000000,percent,,"));
        assertTrue(csv.contains(
                "resident_physical_choice_transition,walk->pt,1.000000000,trips,,"));
        assertFalse(csv.contains("resident_choice_trip_share,pt,100.000000000,percent,24"));
    }

    private Plan oneTrip(String mode, Activity origin, Activity destination,
                         double distance) {
        Plan plan = factory.createPlan();
        plan.addActivity(origin);
        Leg leg = factory.createLeg(mode);
        var route = RouteUtils.createGenericRouteImpl(null, null);
        route.setDistance(distance);
        leg.setRoute(route);
        plan.addLeg(leg);
        plan.addActivity(destination);
        return plan;
    }

    private Plan plan(String mode, Activity... activities) {
        Plan plan = factory.createPlan();
        for (int index = 0; index < activities.length; index++) {
            if (index > 0) plan.addLeg(factory.createLeg(mode));
            plan.addActivity(activities[index]);
        }
        return plan;
    }

    private Activity activity(String type, Coord coordinate) {
        return factory.createActivityFromCoord(type, coordinate);
    }

    private static ModeChoiceCalibrationAnalysis.MetricSnapshot metrics(
            ModeChoiceCalibrationAnalysis.AnalysisResult result,
            ModeChoiceCalibrationAnalysis.SpatialScope scope) {
        return result.metrics(scope,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
    }
}
