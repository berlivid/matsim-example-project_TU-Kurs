package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
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
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

class ModeChoiceCalibrationAnalysisTest {
    private static MunichMunicipalBoundary boundary;
    private static Coord inside;
    private static Coord outside;

    private Scenario scenario;
    private PopulationFactory populationFactory;
    private ModeChoiceCalibrationAnalysis analysis;
    private final List<Id<Link>> linkIds = List.of(
            Id.createLinkId("l0"), Id.createLinkId("l1"), Id.createLinkId("l2"));
    private TransitStopFacility stop0;
    private TransitStopFacility stop1;
    private TransitStopFacility stop2;
    private TransitLine busLine;
    private TransitRoute busRoute;
    private TransitLine tramLine;
    private TransitRoute tramRoute;

    @BeforeAll
    static void loadBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        var point = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(point.x, point.y);
        outside = new Coord(boundary.envelope().getMinX() - 10_000,
                boundary.envelope().getMinY() - 10_000);
    }

    @BeforeEach
    void setUp() {
        scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        populationFactory = PopulationUtils.getFactory();
        buildNetwork();
        buildTransit();
        analysis = new ModeChoiceCalibrationAnalysis(scenario, boundary);
    }

    @Test
    void boundaryFilterIsReusedAndCrossingTripsAreSeparate() {
        Plan plan = oneLegPlan("car", inside, outside, reportedRoute(1_000));
        var result = analyze(Map.of(personId("crossing"), plan));
        assertEquals(1, result.metrics(scope("ALL_TRIPS"), eligibility("ALL_PLANS")).mainTrips());
        assertEquals(1, result.metrics(scope("BOUNDARY_CROSSING"),
                eligibility("ALL_PLANS")).mainTrips());
        assertEquals(0, result.metrics(scope("BOTH_INSIDE"),
                eligibility("ALL_PLANS")).mainTrips());
    }

    @Test
    void eachMainTripCountsOnceAndKnownSharesSumToOneHundred() {
        Map<Id<Person>, Plan> plans = Map.of(
                personId("car"), oneLegPlan("car", inside, inside, reportedRoute(1_000)),
                personId("pt"), ptTransferPlan(),
                personId("walk"), oneLegPlan("walk", inside, inside, reportedRoute(1_000)),
                personId("bike"), oneLegPlan("bike", inside, inside, reportedRoute(1_000)));
        var metrics = analyze(plans).metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(4, metrics.mainTrips());
        assertEquals(1, metrics.mainTripsByMode().get("pt"));
        double sum = List.of("car", "pt", "walk", "bike").stream()
                .mapToDouble(metrics::modalSharePercent).sum();
        assertEquals(100.0, sum, 1e-9);
    }

    @Test
    void unknownMainModeIsReportedInTheSameModalShareDenominator() {
        var metrics = analyze(Map.of(personId("unknown"),
                        oneLegPlan("hovercraft", inside, inside, reportedRoute(1_000))))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(1, metrics.mainTripsByMode().get("unknown"));
        assertEquals(100.0, metrics.modalSharePercent("unknown"), 1e-12);
        assertEquals(0.0, List.of("car", "pt", "walk", "bike").stream()
                .mapToDouble(metrics::modalSharePercent).sum(), 1e-12);
    }

    @Test
    void ptAccessTransferAndEgressCountOnceButPhysicalSubmodesRemainSeparate() {
        var metrics = analyze(Map.of(personId("pt"), ptTransferPlan()))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(1, metrics.mainTripsByMode().get("pt"));
        assertTrue(metrics.physicalStagePkm("walk") > 0);
        assertTrue(metrics.physicalStagePkm("bus") > 0);
        assertTrue(metrics.physicalStagePkm("tram") > 0);
        assertEquals(0.0, metrics.physicalStagePkm("unknown_pt"), 1e-12);
        assertEquals(1, metrics.validDistanceTripsByMode().get("pt"));
    }

    @Test
    void networkRouteDistanceFallsBackToDeterministicLinkSum() {
        var route = RouteUtils.createLinkNetworkRouteImpl(
                linkIds.get(0), List.of(linkIds.get(1)), linkIds.get(2));
        route.setDistance(Double.NaN);
        var metrics = analyze(Map.of(personId("car"),
                        oneLegPlan("car", inside, inside, route)))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(0.6, metrics.mainModePkm("car"), 1e-9);
        assertEquals(1L, metrics.distanceSources().get(
                ModeChoiceCalibrationAnalysis.DistanceSource.NETWORK_LINK_SUM));
        assertEquals(metrics.physicalStagePkm("car"), metrics.mainModePkm("car"), 1e-12);
    }

    @Test
    void teleportedDistanceAndBeelineFallbackAreTransparent() {
        var reported = analyze(Map.of(personId("walk"),
                        oneLegPlan("walk", inside, inside, reportedRoute(1_300))))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(1.3, reported.mainModePkm("walk"), 1e-9);
        assertEquals(1L, reported.distanceSources().get(
                ModeChoiceCalibrationAnalysis.DistanceSource.ROUTE_REPORTED));

        Coord destination = new Coord(inside.getX() + 1_000, inside.getY());
        var fallback = analyze(Map.of(personId("bike"),
                        oneLegPlan("bike", inside, destination, null)))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(1.3, fallback.mainModePkm("bike"), 1e-9);
        assertEquals(1L, fallback.distanceSources().get(
                ModeChoiceCalibrationAnalysis.DistanceSource.BEELINE_FALLBACK));
    }

    @Test
    void transitDistanceUsesActualTransitRouteAndTransportMode() {
        var passengerRoute = new DefaultTransitPassengerRoute(
                stop0, busLine, busRoute, stop1);
        passengerRoute.setDistance(Double.NaN);
        Plan plan = oneLegPlan("pt", inside, inside, passengerRoute);
        var metrics = analyze(Map.of(personId("bus"), plan))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertTrue(metrics.mainModePkm("pt") > 0);
        assertEquals(metrics.mainModePkm("pt"), metrics.physicalStagePkm("bus"), 1e-12);
        assertEquals(1L, metrics.distanceSources().get(
                ModeChoiceCalibrationAnalysis.DistanceSource.TRANSIT_ROUTE_PATH));
    }

    @Test
    void missingDistanceIsCountedAndNotConvertedToZero() {
        Activity origin = populationFactory.createActivityFromLinkId("home", linkIds.get(0));
        Activity destination = populationFactory.createActivityFromLinkId("work", linkIds.get(1));
        Plan plan = populationFactory.createPlan();
        plan.addActivity(origin);
        plan.addLeg(populationFactory.createLeg("car"));
        plan.addActivity(destination);
        var metrics = analyze(Map.of(personId("missing"), plan))
                .metrics(scope("ALL_TRIPS"), eligibility("ALL_PLANS"));
        assertEquals(1, metrics.invalidStageDistances());
        assertEquals(1, metrics.invalidMainTripDistances());
        assertEquals(0.0, metrics.mainModePkm("car"), 1e-12);
    }

    @Test
    void capableAndNonCapablePlansAreReportedSeparately() {
        Plan closed = closedWalkPlan();
        Plan open = oneLegPlan("walk", inside, inside, reportedRoute(1_000));
        var result = analyze(Map.of(personId("closed"), closed, personId("open"), open));
        assertEquals(1, result.plansWithClosedSubtour());
        assertEquals(1, result.plansWithoutClosedSubtour());
        assertEquals(2, result.metrics(scope("BOTH_INSIDE"),
                eligibility("MODE_CHOICE_CAPABLE")).mainTrips());
        assertEquals(1, result.metrics(scope("BOTH_INSIDE"),
                eligibility("NOT_MODE_CHOICE_CAPABLE")).mainTrips());
    }

    @Test
    void scalingChangesOnlyPkmSumsAndAnalysisDoesNotMutatePlan() {
        Plan plan = oneLegPlan("car", inside, inside, reportedRoute(2_000));
        List<String> before = signature(plan);
        var metrics = analyze(Map.of(personId("car"), plan))
                .metrics(scope("BOTH_INSIDE"), eligibility("ALL_PLANS"));
        assertEquals(2.0, metrics.mainModePkm("car"), 1e-12);
        assertEquals(40.0, metrics.mainModePkm("car")
                * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR, 1e-12);
        assertEquals(100.0, metrics.modalSharePercent("car"), 1e-12);
        assertEquals(2.0, metrics.meanTripLengthKm("car"), 1e-12);
        assertEquals(before, signature(plan));
    }

    @Test
    void csvIsDeterministicAndBlankOrNumericTargetsAreHandled(@TempDir Path temp)
            throws Exception {
        var result = analyze(Map.of(personId("car"),
                oneLegPlan("car", inside, inside, reportedRoute(1_000))));
        String first = ModeChoiceCalibrationAnalysisWriter.iterationMetrics(List.of(result));
        String second = ModeChoiceCalibrationAnalysisWriter.iterationMetrics(List.of(result));
        assertEquals(first, second);

        Path blankTargets = temp.resolve("blank.csv");
        Files.copy(ModeChoiceCalibrationTargets.DEFAULT_FILE, blankTargets);
        Path blankOutput = temp.resolve("blank-output");
        new ModeChoiceCalibrationAnalysisWriter(blankOutput, blankTargets)
                .write(List.of(result), true);
        String blankComparison = Files.readString(blankOutput.resolve(
                "analysis/calibration_target_comparison.csv"));
        assertTrue(blankComparison.contains("target_value is empty; no comparison made"));

        Path numericTargets = temp.resolve("numeric.csv");
        Files.writeString(numericTargets,
                "metric,mode,target_value,unit,spatial_definition,trip_definition,reference_year,source,notes\n"
                        + "trip_modal_share,car,90,percent,"
                        + ModeChoiceCalibrationAnalysis.PRIMARY_SPATIAL_DEFINITION + ","
                        + ModeChoiceCalibrationAnalysis.MAIN_TRIP_DEFINITION
                        + ",2019,test,synthetic test target\n");
        Path numericOutput = temp.resolve("numeric-output");
        new ModeChoiceCalibrationAnalysisWriter(numericOutput, numericTargets)
                .write(List.of(result), true);
        String numericComparison = Files.readString(numericOutput.resolve(
                "analysis/calibration_target_comparison.csv"));
        assertTrue(numericComparison.contains(",10.000000000,10.000000000,true,"));
    }

    private ModeChoiceCalibrationAnalysis.AnalysisResult analyze(Map<Id<Person>, Plan> plans) {
        return analysis.analyze(0, plans);
    }

    private Plan oneLegPlan(String mode, Coord originCoord, Coord destinationCoord,
                            org.matsim.api.core.v01.population.Route route) {
        Plan plan = populationFactory.createPlan();
        Activity origin = activity("home", originCoord, linkIds.get(0));
        Activity destination = activity("work", destinationCoord, linkIds.get(2));
        Leg leg = populationFactory.createLeg(mode);
        leg.setRoute(route);
        plan.addActivity(origin);
        plan.addLeg(leg);
        plan.addActivity(destination);
        return plan;
    }

    private Plan closedWalkPlan() {
        Plan plan = populationFactory.createPlan();
        plan.addActivity(activity("home", inside, linkIds.get(0)));
        Leg outward = populationFactory.createLeg("walk");
        outward.setRoute(reportedRoute(1_000));
        plan.addLeg(outward);
        plan.addActivity(activity("work", inside, linkIds.get(1)));
        Leg inward = populationFactory.createLeg("walk");
        inward.setRoute(reportedRoute(1_000));
        plan.addLeg(inward);
        plan.addActivity(activity("home", inside, linkIds.get(0)));
        return plan;
    }

    private Plan ptTransferPlan() {
        Plan plan = populationFactory.createPlan();
        plan.addActivity(activity("home", inside, linkIds.get(0)));
        plan.addLeg(leg("walk", reportedRoute(100)));
        plan.addActivity(activity(TripStructureUtils.createStageActivityType("pt"),
                inside, linkIds.get(0)));
        var busPassenger = new DefaultTransitPassengerRoute(stop0, busLine, busRoute, stop1);
        busPassenger.setDistance(Double.NaN);
        plan.addLeg(leg("pt", busPassenger));
        plan.addActivity(activity(TripStructureUtils.createStageActivityType("pt"),
                inside, linkIds.get(1)));
        plan.addLeg(leg("transit_walk", reportedRoute(50)));
        plan.addActivity(activity(TripStructureUtils.createStageActivityType("pt"),
                inside, linkIds.get(1)));
        var tramPassenger = new DefaultTransitPassengerRoute(stop1, tramLine, tramRoute, stop2);
        tramPassenger.setDistance(Double.NaN);
        plan.addLeg(leg("pt", tramPassenger));
        plan.addActivity(activity(TripStructureUtils.createStageActivityType("pt"),
                inside, linkIds.get(2)));
        plan.addLeg(leg("walk", reportedRoute(120)));
        plan.addActivity(activity("work", inside, linkIds.get(2)));
        return plan;
    }

    private Leg leg(String mode, org.matsim.api.core.v01.population.Route route) {
        Leg leg = populationFactory.createLeg(mode);
        leg.setRoute(route);
        return leg;
    }

    private org.matsim.api.core.v01.population.Route reportedRoute(double distance) {
        var route = RouteUtils.createGenericRouteImpl(linkIds.get(0), linkIds.get(2));
        route.setDistance(distance);
        return route;
    }

    private Activity activity(String type, Coord coord, Id<Link> linkId) {
        Activity activity = populationFactory.createActivityFromCoord(type, coord);
        activity.setLinkId(linkId);
        return activity;
    }

    private void buildNetwork() {
        var network = scenario.getNetwork();
        var factory = network.getFactory();
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Node node = factory.createNode(Id.createNodeId("n" + index),
                    new Coord(inside.getX() + index, inside.getY()));
            network.addNode(node);
            nodes.add(node);
        }
        double[] lengths = {100, 200, 300};
        for (int index = 0; index < linkIds.size(); index++) {
            Link link = factory.createLink(linkIds.get(index), nodes.get(index), nodes.get(index + 1));
            link.setLength(lengths[index]);
            link.setFreespeed(10);
            link.setCapacity(1_000);
            link.setNumberOfLanes(1);
            network.addLink(link);
        }
    }

    private void buildTransit() {
        var schedule = scenario.getTransitSchedule();
        var factory = schedule.getFactory();
        stop0 = factory.createTransitStopFacility(Id.create("s0", TransitStopFacility.class),
                inside, false);
        stop1 = factory.createTransitStopFacility(Id.create("s1", TransitStopFacility.class),
                inside, false);
        stop2 = factory.createTransitStopFacility(Id.create("s2", TransitStopFacility.class),
                inside, false);
        stop0.setLinkId(linkIds.get(0));
        stop1.setLinkId(linkIds.get(1));
        stop2.setLinkId(linkIds.get(2));
        schedule.addStopFacility(stop0);
        schedule.addStopFacility(stop1);
        schedule.addStopFacility(stop2);

        busLine = factory.createTransitLine(Id.create("bus-line", TransitLine.class));
        busRoute = factory.createTransitRoute(Id.create("bus-route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(linkIds.get(0), linkIds.get(1)),
                List.of(factory.createTransitRouteStop(stop0, 0, 0),
                        factory.createTransitRouteStop(stop1, 100, 100)), "bus");
        busLine.addRoute(busRoute);
        schedule.addTransitLine(busLine);

        tramLine = factory.createTransitLine(Id.create("tram-line", TransitLine.class));
        tramRoute = factory.createTransitRoute(Id.create("tram-route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(linkIds.get(1), linkIds.get(2)),
                List.of(factory.createTransitRouteStop(stop1, 0, 0),
                        factory.createTransitRouteStop(stop2, 100, 100)), "tram");
        tramLine.addRoute(tramRoute);
        schedule.addTransitLine(tramLine);
    }

    private static Id<Person> personId(String value) {
        return Id.createPersonId(value);
    }

    private static ModeChoiceCalibrationAnalysis.SpatialScope scope(String value) {
        return ModeChoiceCalibrationAnalysis.SpatialScope.valueOf(value);
    }

    private static ModeChoiceCalibrationAnalysis.PlanEligibility eligibility(String value) {
        return ModeChoiceCalibrationAnalysis.PlanEligibility.valueOf(value);
    }

    private static List<String> signature(Plan plan) {
        return plan.getPlanElements().stream().map(Object::toString).toList();
    }
}
