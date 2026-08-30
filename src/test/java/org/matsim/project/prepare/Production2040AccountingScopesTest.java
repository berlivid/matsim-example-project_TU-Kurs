package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;

class Production2040AccountingScopesTest {
    private static final double X = 4_400_000;
    private static final double Y = 5_300_000;
    private static final Id<Person> RESIDENT = Id.createPersonId("resident");

    @TempDir Path temporary;

    @Test
    void pairedProductionConfigsPassReadOnlyValidationWithExistingOutputs() {
        ValidateProduction2040Configs.validateFiles(Production2040Contract.BAU.configPath(),
                Production2040Contract.FAST_TRACK.configPath(), false);
    }

    @Test
    void endpointScopesDistinguishBothOriginDestinationAndOutside() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        Coord inside = new Coord(X + 50, Y + 50);
        Coord outsideWest = new Coord(X - 50, Y + 50);
        Coord outsideEast = new Coord(X + 150, Y + 50);

        assertEquals(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE,
                filter.classify(inside, inside));
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY,
                filter.classify(inside, outsideEast));
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.DESTINATION_ONLY,
                filter.classify(outsideWest, inside));
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE,
                filter.classify(outsideWest, outsideEast));
    }

    @Test
    void residentClassificationUsesDocumentedHomeAndReportsUnresolved() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person resident = person("resident", "home", new Coord(X + 10, Y + 10),
                "work", new Coord(X + 150, Y + 10), TransportMode.car);
        Person nonResident = person("non-resident", "home", new Coord(X - 20, Y + 10),
                "work", new Coord(X + 10, Y + 10), TransportMode.bike);
        Person unresolved = person("unresolved", "work", new Coord(X + 10, Y + 10),
                "other", new Coord(X + 20, Y + 20), TransportMode.walk);
        var index = Production2040AccountingScopes.classify(
                List.of(resident, nonResident, unresolved), boundary);

        assertEquals(1, index.personCounts().get(
                Production2040AccountingScopes.ResidentStatus.RESIDENT));
        assertEquals(1, index.personCounts().get(
                Production2040AccountingScopes.ResidentStatus.NON_RESIDENT));
        assertEquals(1, index.personCounts().get(
                Production2040AccountingScopes.ResidentStatus.UNRESOLVED));
        assertTrue(index.trip(RESIDENT, 0).included(
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS));
        assertFalse(index.trip(Id.createPersonId("non-resident"), 0).included(
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS));
        assertFalse(index.trip(Id.createPersonId("unresolved"), 0).included(
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS));
    }

    @Test
    void stageActivitiesDoNotCreateAdditionalMainTrips() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person person = PopulationUtils.getFactory().createPerson(RESIDENT);
        Plan plan = PopulationUtils.createPlan();
        plan.addActivity(PopulationUtils.createActivityFromCoord("home",
                new Coord(X + 10, Y + 10)));
        plan.addLeg(PopulationUtils.createLeg(TransportMode.walk));
        plan.addActivity(PopulationUtils.createActivityFromCoord("pt interaction",
                new Coord(X + 20, Y + 20)));
        plan.addLeg(PopulationUtils.createLeg(TransportMode.pt));
        plan.addActivity(PopulationUtils.createActivityFromCoord("work",
                new Coord(X + 30, Y + 30)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);

        var index = Production2040AccountingScopes.classify(List.of(person), boundary);
        assertEquals(1, index.trips().size());
        assertEquals("pt", index.trip(RESIDENT, 0).mainMode());
    }

    @Test
    void carSegmentsUseCorrectTripAndFirstLastLinkConvention() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person person = twoTripResident();
        var index = Production2040AccountingScopes.classify(List.of(person), boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Id<Link> first = link(scenario.getNetwork(), "first", X + 10, X + 30, 100);
        Id<Link> last = link(scenario.getNetwork(), "last", X + 30, X + 70, 200);
        var observer = new Production2040AccountingEventMetrics(scenario.getNetwork(), boundary,
                index);
        var metrics = new Production2040VehicleMetrics(scenario.getNetwork(),
                scenario.getTransitSchedule(), scenario.getTransitVehicles(), Map.of(), observer);
        Id<Vehicle> car = Id.createVehicleId("car");
        metrics.handleEvent(new ActivityEndEvent(0, RESIDENT, first, null, "home"));
        metrics.handleEvent(new VehicleEntersTrafficEvent(1, RESIDENT, first, car, "car", 0.5));
        metrics.handleEvent(new LinkEnterEvent(2, car, last));
        metrics.handleEvent(new VehicleLeavesTrafficEvent(3, RESIDENT, last, car, "car", 0.25));

        var result = observer.result();
        assertEquals(100.0, result.carByScope().get(
                Production2040AccountingScopes.Scope.BOTH_INSIDE).metres(), 1e-9);
        assertEquals(100.0, result.carByScope().get(
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS).metres(), 1e-9);
        assertEquals(1, result.carByScope().get(
                Production2040AccountingScopes.Scope.BOTH_INSIDE).trips());
        assertEquals(0, result.incompleteCarSegments());
    }

    @Test
    void transitMovementsAreExcludedFromPrivateCarAndRemainUnscaled() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        var index = Production2040AccountingScopes.classify(List.of(twoTripResident()), boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Id<Link> inside = link(scenario.getNetwork(), "inside", X + 10, X + 90, 100);
        var observer = new Production2040AccountingEventMetrics(scenario.getNetwork(), boundary,
                index);
        Id<Vehicle> bus = Id.createVehicleId("bus");
        observer.trafficEnter(bus, RESIDENT, "car", 0, true);
        observer.movement(bus, RESIDENT, inside, 100, true, "bus");
        observer.trafficLeave(bus, RESIDENT);

        var result = observer.result();
        assertEquals(0.0, result.carByScope().get(
                Production2040AccountingScopes.Scope.BOTH_INSIDE).metres());
        assertEquals(100.0, result.ptByRouteMode().get("bus").uncutMetres());
        assertEquals(100.0, result.ptByRouteMode().get("bus").territorialMetres());
    }

    @Test
    void territorialClipHandlesInsideOutsideAndCrossingGeometry() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Network network = NetworkUtils.createNetwork();
        Link inside = network.getLinks().get(link(network, "inside", X + 10, X + 90, 160));
        Link outside = network.getLinks().get(link(network, "outside", X - 90, X - 10, 160));
        Link crossing = network.getLinks().get(link(network, "crossing", X - 50, X + 50, 200));

        assertEquals(1.0, Production2040AccountingEventMetrics.clip(inside, boundary)
                .insideFraction(), 1e-12);
        assertEquals(0.0, Production2040AccountingEventMetrics.clip(outside, boundary)
                .insideFraction(), 1e-12);
        var clipped = Production2040AccountingEventMetrics.clip(crossing, boundary);
        assertEquals(0.5, clipped.insideFraction(), 1e-12);
        assertEquals(Production2040AccountingEventMetrics.LinkLocation.CROSSING,
                clipped.category());
        assertEquals(100.0, clipped.modelLinkMetres() * clipped.insideFraction(), 1e-12);
    }

    @Test
    void reconciliationAcceptsExactRegionalTotalsAndRejectsMismatch() {
        var carScope = new Production2040AccountingEventMetrics.CarScope(100, 1, 1, 0);
        Map<Production2040AccountingScopes.Scope,
                Production2040AccountingEventMetrics.CarScope> cars = Map.of(
                Production2040AccountingScopes.Scope.BOTH_INSIDE, carScope,
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS, carScope);
        Map<MunichTripBoundaryFilter.SpatialCategory, Double> endpoints = new LinkedHashMap<>();
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            endpoints.put(category, category == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE
                    ? 100.0 : 0.0);
        }
        var pt = new Production2040AccountingEventMetrics.PtService(200, 100, 1, 50, 200);
        var accounting = new Production2040AccountingEventMetrics.Result(cars, endpoints,
                Map.of("bus", pt), 1, 50, 0, 0, 0, 0, 0, 0);
        var regional = new Production2040VehicleMetrics.Result(100, 1, 0, 0, 0, 0, 0,
                Map.of("bus", new Production2040VehicleMetrics.PtMetric(
                        200, 0, 0, 0, 0, 0, 0)));
        var references = new AnalyzeProduction2040AccountingScopes.RegionalReferences(100,
                Map.of("bus", 200.0));

        AnalyzeProduction2040AccountingScopes.validateEventMetrics(regional, accounting,
                references);
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040AccountingScopes.validateEventMetrics(regional, accounting,
                        new AnalyzeProduction2040AccountingScopes.RegionalReferences(101,
                                Map.of("bus", 200.0))));
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040AccountingScopes.validateEventMetrics(regional, accounting,
                        new AnalyzeProduction2040AccountingScopes.RegionalReferences(100,
                                Map.of("bus", 201.0))));
    }

    @Test
    void reportsApplyFactor20OnlyToPrivateDemandAndLabelActiveModes() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person person = person("resident", "home", new Coord(X + 10, Y + 10),
                "work", new Coord(X + 20, Y + 20), TransportMode.bike);
        var index = Production2040AccountingScopes.classify(List.of(person), boundary);
        Path trips = temporary.resolve("trips.csv");
        Files.writeString(trips, tripHeader() + "resident;1;resident_1;00:10:00;1000;bike;"
                + (X + 10) + ';' + (Y + 10) + ';' + (X + 20) + ';' + (Y + 20) + "\n",
                StandardCharsets.UTF_8);
        var measurements = AnalyzeProduction2040AccountingScopes.readTripMeasurements(trips,
                boundary, index);
        var carZero = new Production2040AccountingEventMetrics.CarScope(0, 0, 0, 0);
        var accounting = new Production2040AccountingEventMetrics.Result(Map.of(
                Production2040AccountingScopes.Scope.BOTH_INSIDE, carZero,
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS, carZero),
                zeroEndpoints(), Map.of("bus",
                new Production2040AccountingEventMetrics.PtService(100, 50, 1, 100, 100)),
                1, 100, 0, 0, 0, 0, 0, 0);
        var regional = new Production2040VehicleMetrics.Result(0, 0, 0, 0, 0, 0, 0,
                Map.of("bus", new Production2040VehicleMetrics.PtMetric(
                        100, 0, 0, 0, 0, 0, 0)));
        var definition = Production2040AnalysisSpec.scenario("BAU");
        Map<String, String> reports = AnalyzeProduction2040AccountingScopes.buildReports(
                definition, index, measurements, regional, accounting,
                new AnalyzeProduction2040AccountingScopes.RegionalReferences(0,
                        Map.of("bus", 100.0)));

        ValidateProduction2040AccountingScopes.validateBundle(definition, reports);
        String active = reports.get("final_active_mode_distance_by_scope.csv");
        assertTrue(active.contains(",bike,derived_bike_km,"));
        assertTrue(active.contains(",walk,walk_person_km,"));
        assertTrue(active.contains(",NOT_APPLICABLE,"));
        String pt = reports.get("final_territorial_pt_fkm_by_route_mode.csv");
        assertTrue(pt.contains(",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,bus,"));
        assertTrue(pt.contains(",NOT_APPLICABLE,"));
        assertFalse(pt.contains("factor_20_daily_vehicle_km,20"));
        assertTrue(reports.get("final_modal_split_by_scope.csv")
                .contains("expanded_daily_trip_count_factor_20"));
    }

    @Test
    void bundleIsScenarioParameterizedAndRejectsMixedPartialOrStaleReports() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person person = person("resident", "home", new Coord(X + 10, Y + 10),
                "work", new Coord(X + 20, Y + 20), TransportMode.walk);
        var index = Production2040AccountingScopes.classify(List.of(person), boundary);
        Path trips = temporary.resolve("walk-trips.csv");
        Files.writeString(trips, tripHeader() + "resident;1;resident_1;00:10:00;1000;walk;"
                + (X + 10) + ';' + (Y + 10) + ';' + (X + 20) + ';' + (Y + 20) + "\n");
        var measurements = AnalyzeProduction2040AccountingScopes.readTripMeasurements(trips,
                boundary, index);
        var zeroCar = new Production2040AccountingEventMetrics.CarScope(0, 0, 0, 0);
        var accounting = new Production2040AccountingEventMetrics.Result(Map.of(
                Production2040AccountingScopes.Scope.BOTH_INSIDE, zeroCar,
                Production2040AccountingScopes.Scope.MUNICH_RESIDENTS, zeroCar),
                zeroEndpoints(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0);
        var regional = new Production2040VehicleMetrics.Result(0, 0, 0, 0, 0, 0, 0,
                Map.of());
        var references = new AnalyzeProduction2040AccountingScopes.RegionalReferences(0,
                Map.of());
        var bau = Production2040AnalysisSpec.scenario("BAU");
        var fast = Production2040AnalysisSpec.scenario("FAST_TRACK");
        Map<String, String> bauReports = new LinkedHashMap<>(
                AnalyzeProduction2040AccountingScopes.buildReports(bau, index, measurements,
                        regional, accounting, references));
        Map<String, String> fastReports = AnalyzeProduction2040AccountingScopes.buildReports(fast,
                index, measurements, regional, accounting, references);

        ValidateProduction2040AccountingScopes.validateBundle(bau, bauReports);
        ValidateProduction2040AccountingScopes.validateBundle(fast, fastReports);
        bauReports.put("accounting_scope_report.md",
                fastReports.get("accounting_scope_report.md"));
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AccountingScopes.validateBundle(bau, bauReports));
        bauReports.remove("final_private_car_fkm_by_scope.csv");
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AccountingScopes.validateBundle(bau, bauReports));
    }

    @Test
    void missingStandardOutputTripFailsClosedAsPartialOutput() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Person person = person("resident", "home", new Coord(X + 10, Y + 10),
                "work", new Coord(X + 20, Y + 20), TransportMode.car);
        var index = Production2040AccountingScopes.classify(List.of(person), boundary);
        Path trips = temporary.resolve("partial-trips.csv");
        Files.writeString(trips, tripHeader());
        var measurements = AnalyzeProduction2040AccountingScopes.readTripMeasurements(trips,
                boundary, index);

        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040AccountingScopes.validateMeasurements(index, measurements));
    }

    private MunichMunicipalBoundary boundary() throws Exception {
        Path file = temporary.resolve("boundary-" + System.nanoTime() + ".json");
        Files.writeString(file, """
                {"type":"Polygon","coordinates":[[[4400000,5300000],[4400100,5300000],[4400100,5300100],[4400000,5300100],[4400000,5300000]]]}
                """);
        return MunichMunicipalBoundary.load(file);
    }

    private static Person person(String id, String originType, Coord origin,
            String destinationType, Coord destination, String mode) {
        Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId(id));
        Plan plan = PopulationUtils.createPlan();
        plan.addActivity(PopulationUtils.createActivityFromCoord(originType, origin));
        plan.addLeg(PopulationUtils.createLeg(mode));
        plan.addActivity(PopulationUtils.createActivityFromCoord(destinationType, destination));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Person twoTripResident() {
        Person person = PopulationUtils.getFactory().createPerson(RESIDENT);
        Plan plan = PopulationUtils.createPlan();
        plan.addActivity(PopulationUtils.createActivityFromCoord("home",
                new Coord(X + 10, Y + 10)));
        plan.addLeg(PopulationUtils.createLeg(TransportMode.car));
        plan.addActivity(PopulationUtils.createActivityFromCoord("work",
                new Coord(X + 80, Y + 80)));
        plan.addLeg(PopulationUtils.createLeg(TransportMode.car));
        plan.addActivity(PopulationUtils.createActivityFromCoord("home",
                new Coord(X + 10, Y + 10)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Id<Link> link(Network network, String id, double fromX, double toX,
            double modelLength) {
        Node from = NetworkUtils.createAndAddNode(network, Id.createNodeId(id + "-from"),
                new Coord(fromX, Y + 50));
        Node to = NetworkUtils.createAndAddNode(network, Id.createNodeId(id + "-to"),
                new Coord(toX, Y + 50));
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), from, to,
                modelLength, 10, 1000, 1);
        return link.getId();
    }

    private static String tripHeader() {
        return "person;trip_number;trip_id;trav_time;traveled_distance;main_mode;"
                + "start_x;start_y;end_x;end_y\n";
    }

    private static Map<MunichTripBoundaryFilter.SpatialCategory, Double> zeroEndpoints() {
        Map<MunichTripBoundaryFilter.SpatialCategory, Double> result = new LinkedHashMap<>();
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            result.put(category, 0.0);
        }
        return Map.copyOf(result);
    }
}
