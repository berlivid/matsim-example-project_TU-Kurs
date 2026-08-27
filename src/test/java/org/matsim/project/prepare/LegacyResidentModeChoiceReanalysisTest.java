package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

class LegacyResidentModeChoiceReanalysisTest {
    private static MunichMunicipalBoundary boundary;
    private static PopulationFactory factory;
    private static Coord inside;
    private static Coord outside;

    @BeforeAll
    static void prepareBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        factory = PopulationUtils.getFactory();
        var point = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(point.x, point.y);
        outside = new Coord(boundary.envelope().getMinX() - 10_000,
                boundary.envelope().getMinY() - 10_000);
    }

    @Test
    void originalHomeDefinesCohortAndCrossBoundaryResidentTripsAreIncluded() {
        Person residentOriginal = person("resident", closedPlan(inside, outside,
                "walk", "pt"));
        Person nonResidentOriginal = person("non-resident", closedPlan(outside, inside,
                "car", "car"));
        var cohort = ReanalyzeLegacy2019ModeChoiceOutputsForResidents.readCohort(
                List.of(residentOriginal, nonResidentOriginal), boundary);

        assertEquals(1, cohort.residentIds().size());
        assertTrue(cohort.residentIds().contains(Id.createPersonId("resident")));
        assertFalse(cohort.residentIds().contains(Id.createPersonId("non-resident")));
        assertEquals(2, cohort.residentMainTrips());

        Scenario output = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        output.getPopulation().addPerson(person("resident",
                closedPlan(inside, outside, "walk", "pt")));
        // This non-resident travels across the boundary but must not enter the metrics.
        output.getPopulation().addPerson(person("non-resident",
                closedPlan(outside, inside, "car", "car")));
        var plans = ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                .selectedResidentPlans(output.getPopulation(), cohort.residentIds());
        var result = new ModeChoiceCalibrationAnalysis(output, boundary).analyze(0, plans);
        var all = result.metrics(ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);

        assertEquals(2, all.mainTrips());
        assertEquals(2, all.mainTripsByMode().get("walk"));
        assertEquals(2, all.choiceMainTripsByMode().get("pt"));
        assertEquals(2, all.ptRequestsWithWalkOnlyPhysicalRoute());
        assertEquals(1, result.metrics(
                ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS).mainTrips());
        assertEquals(1, result.metrics(
                ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS).mainTrips());
    }

    @Test
    void stageActivitiesDoNotCreateAdditionalResidentMainTrips() {
        Plan plan = factory.createPlan();
        plan.addActivity(activity("home", inside));
        plan.addLeg(leg("walk", "pt"));
        plan.addActivity(activity(TripStructureUtils.createStageActivityType("pt"), inside));
        plan.addLeg(leg("walk", "pt"));
        plan.addActivity(activity("work", outside));
        var cohort = ReanalyzeLegacy2019ModeChoiceOutputsForResidents.readCohort(
                List.of(person("staged", plan)), boundary);
        assertEquals(1, cohort.residentIds().size());
        assertEquals(1, cohort.residentMainTrips());
    }

    @Test
    void targetComparisonUsesAbsoluteFourModeDifferences() {
        assertEquals(2.0,
                ReanalyzeLegacy2019ModeChoiceOutputsForResidents.absoluteDeviation(Map.of(
                        "car", 35.0, "pt", 23.0, "bike", 18.0, "walk", 24.0)),
                1e-12);
    }

    @Test
    void protectedOutputRefusesAnExistingDirectory(@TempDir Path temp) throws Exception {
        Path output = Files.createDirectory(temp.resolve("protected"));
        assertThrows(IllegalStateException.class,
                () -> ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                        .requireOutputAbsent(output));
    }

    @Test
    void requiredLegacyEvidenceFailsClosedWhenMissing(@TempDir Path temp)
            throws Exception {
        Path output = Files.createDirectory(temp.resolve("legacy"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                        .locateRequiredEvidence(output, "fixture"));
        assertTrue(failure.getMessage().contains("output config is missing"));
    }

    @Test
    void outputInspectionHelpersDoNotModifySourceOutput(@TempDir Path temp)
            throws Exception {
        Path output = Files.createDirectory(temp.resolve("legacy"));
        Files.writeString(output.resolve("fixture.output_config.xml"), "config");
        Files.writeString(output.resolve("fixture.logfile.log"), "log");
        Files.writeString(output.resolve("fixture.output_plans.xml.gz"), "plans");
        Files.writeString(output.resolve("fixture.scorestats.txt"), "scores");
        var before = ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                .captureSnapshot(output);

        var evidence = ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                .locateRequiredEvidence(output, "fixture");
        assertEquals(output.resolve("fixture.output_plans.xml.gz"),
                evidence.finalPlans());
        assertEquals(List.of(Path.of("fixture.scorestats.txt")),
                ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                        .locateScoreStatistics(output));
        assertTrue(ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                .optionalFinalEvents(output, 20).isEmpty());

        assertEquals(before, ReanalyzeLegacy2019ModeChoiceOutputsForResidents
                .captureSnapshot(output));
    }

    private static Person person(String id, Plan plan) {
        Person person = factory.createPerson(Id.createPersonId(id));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Plan closedPlan(Coord home, Coord destination,
                                   String physicalMode, String routingMode) {
        Plan plan = factory.createPlan();
        plan.addActivity(activity("home", home));
        plan.addLeg(leg(physicalMode, routingMode));
        plan.addActivity(activity("work", destination));
        plan.addLeg(leg(physicalMode, routingMode));
        plan.addActivity(activity("home", home));
        return plan;
    }

    private static Leg leg(String physicalMode, String routingMode) {
        Leg leg = factory.createLeg(physicalMode);
        TripStructureUtils.setRoutingMode(leg, routingMode);
        var route = RouteUtils.createGenericRouteImpl(null, null);
        route.setDistance(1_000.0);
        leg.setRoute(route);
        return leg;
    }

    private static Activity activity(String type, Coord coord) {
        return factory.createActivityFromCoord(type, coord);
    }
}
