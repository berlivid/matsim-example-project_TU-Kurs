package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import ch.sbb.matsim.routing.pt.raptor.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Focused, read-only validation of the activated 2040 public-transport configurations. */
public final class ValidateMatsim2040Activation {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path BAU = ROOT.resolve("scenarios/munich_bau_2040/config_bau.xml");
    private static final Path FAST = ROOT.resolve("scenarios/munich_fast_track_2040/config_fast_track.xml");

    private ValidateMatsim2040Activation() { }

    public static void main(String[] args) {
        if (args.length == 0 || "--bau".equals(args[0])) validate("BAU", BAU, false);
        if (args.length == 0 || "--fast-track".equals(args[0])) validate("Fast Track", FAST, true);
    }

    private static void validate(String label, Path configFile, boolean fastTrack) {
        Config config = ConfigUtils.loadConfig(configFile.toString());
        require(config.transit().isUseTransit(), label + ": transit is disabled");
        require(config.transit().getTransitModes().contains("pt"), label + ": pt routing mode is absent");
        require(config.qsim().getMainModes().contains("car"), label + ": car is absent from QSim main modes");
        require("EPSG:31468".equals(config.global().getCoordinateSystem()), label + ": wrong CRS");
        require(config.network().getInputFile().contains("input_transit/network-with-pt.xml.gz"), label + ": old network selected");
        require(config.transit().getTransitScheduleFile().contains("input_transit/transitSchedule.xml.gz"), label + ": old schedule selected");
        require(config.transit().getVehiclesFile().contains("input_transit/transitVehicles.xml.gz"), label + ": old vehicles selected");

        Scenario scenario = ScenarioUtils.loadScenario(config);
        long departures = scenario.getTransitSchedule().getTransitLines().values().stream()
                .flatMap(line -> line.getRoutes().values().stream()).mapToLong(route -> route.getDepartures().size()).sum();
        require(scenario.getPopulation().getPersons().size() > 0, label + ": population not loaded");
        require(!scenario.getNetwork().getLinks().isEmpty(), label + ": network not loaded");
        require(!scenario.getTransitVehicles().getVehicles().isEmpty(), label + ": vehicles not loaded");
        Set<Id<?>> vehicleIds = new HashSet<>(scenario.getTransitVehicles().getVehicles().keySet());
        scenario.getTransitSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> {
            route.getStops().forEach(stop -> require(stop.getStopFacility().getLinkId() != null
                    && scenario.getNetwork().getLinks().containsKey(stop.getStopFacility().getLinkId()),
                    label + ": missing schedule link for " + stop.getStopFacility().getId()));
            route.getDepartures().values().forEach(departure -> require(vehicleIds.contains(departure.getVehicleId()),
                    label + ": missing vehicle " + departure.getVehicleId()));
        }));

        boolean hasU9 = findLine(scenario, "FT_U9") != null;
        boolean hasA = findLine(scenario, "FT_NR_A") != null;
        boolean hasB = findLine(scenario, "FT_NR_B") != null;
        require(hasU9 == fastTrack && hasA == fastTrack && hasB == fastTrack, label + ": scenario-specific lines mismatch");
        require(departuresServing(scenario, "BAU_POCCISTRASSE_RAIL_") == 234,
                label + ": Poccistraße must be served by 234 regional trips");
        require(departuresServing(scenario, "BAU_BERDUXSTRASSE_S2_") == 203,
                label + ": Berduxstraße must be served by 203 regular S2 trips");

        System.out.printf("CONFIG PASS %s: persons=%d nodes=%d links=%d stops=%d lines=%d routes=%d departures=%d vehicles=%d%n",
                label, scenario.getPopulation().getPersons().size(), scenario.getNetwork().getNodes().size(),
                scenario.getNetwork().getLinks().size(), scenario.getTransitSchedule().getFacilities().size(),
                scenario.getTransitSchedule().getTransitLines().size(),
                scenario.getTransitSchedule().getTransitLines().values().stream().mapToInt(l -> l.getRoutes().size()).sum(),
                departures, scenario.getTransitVehicles().getVehicles().size());

        if (fastTrack) {
            dumpLine(scenario, "FT_U9");
            dumpLine(scenario, "FT_NR_A");
            dumpLine(scenario, "FT_NR_B");
            dumpLine(scenario, "MUC_U4_neu Prognose");
            runRoutingTests(scenario, true);
        } else {
            runRoutingTests(scenario, false);
        }
    }

    private static void runRoutingTests(Scenario scenario, boolean fastTrack) {
        OccupancyData occupancy = new OccupancyData();
        SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(), scenario.getTransitVehicles(),
                RaptorUtils.createStaticConfig(scenario.getConfig()), scenario.getNetwork(), occupancy);
        SwissRailRaptor router = new SwissRailRaptor(data, new DefaultRaptorParametersForPerson(scenario.getConfig()),
                new LeastCostRaptorRouteSelector(),
                new DefaultRaptorStopFinder(new DefaultRaptorIntermodalAccessEgress(), java.util.Map.of()),
                new DefaultRaptorInVehicleCostCalculator(), new DefaultRaptorTransferCostCalculator());
        RoutingAnchor pocc = precedingServiceAnchor(scenario, "BAU_POCCISTRASSE_RAIL_");
        RoutingAnchor berdux = precedingServiceAnchor(scenario, "BAU_BERDUXSTRASSE_S2_");
        route(router, scenario, "Poccistrasse-regional-reachable", pocc.fromStopId(),
                pocc.insertedStopId(), pocc.departureTime(), Set.of());
        route(router, scenario, "Berduxstrasse-S2-reachable", berdux.fromStopId(),
                berdux.insertedStopId(), berdux.departureTime(), Set.of("S2_Prognose_Petershausen/Altomünster-Holzkirchen"));
        route(router, scenario, "Poccistrasse-regional-to-U3-U6", pocc.fromStopId(),
                "106233", pocc.departureTime(), Set.of());
        if (fastTrack) {
            TransitLine u6 = findLine(scenario, "MUC_U6_neu Prognose");
            TransitRoute u6South = u6.getRoutes().values().stream().filter(r -> hasStop(r, "Münchner Freiheit")
                    && hasStop(r, "Dietlindenstraße")).findFirst().orElseThrow();
            route(router, scenario, "U6-to-U9-at-Muenchner-Freiheit", namedStop(u6South, "Dietlindenstraße").getId().toString(),
                    "FT_U9_PINAKOTHEKEN_D0", 10 * 3600, Set.of("MUC_U6_neu Prognose", "FT_U9"));
            route(router, scenario, "U9-at-Hauptbahnhof", "FT_U9_PINAKOTHEKEN_D0", "106810.1", 10 * 3600, Set.of("FT_U9", "MUC_U5_neu Prognose"));
            TransitLine u3 = findLine(scenario, "MUC_U3_neu Prognose");
            TransitRoute u3Pattern = u3.getRoutes().values().stream().filter(r -> hasStop(r, "Fürstenried West")).findFirst().orElseThrow();
            route(router, scenario, "Impler-Pocci-U9-to-U3-U6", "FT_U9_PINAKOTHEKEN_D0",
                    namedStop(u3Pattern, "Fürstenried West").getId().toString(), 10 * 3600, Set.of("FT_U9", "MUC_U3_neu Prognose"));
            route(router, scenario, "U4-at-Englschalking", "FT_U4_COSIMAPARK_D0", "107730.2", 10 * 3600, Set.of("MUC_U4_neu Prognose"));
            route(router, scenario, "FT-NR-A-complete", "12799.4", "5518.2", 10 * 3600, Set.of("FT_NR_A"));
            route(router, scenario, "FT-NR-B-complete", "106947.2", "107842.3", 10 * 3600, Set.of("FT_NR_B"));
            route(router, scenario, "U4-extension", "106322.1", "108854.1", 10 * 3600, Set.of("MUC_U4_neu Prognose"));
        } else {
            TransitLine u6 = findLine(scenario, "MUC_U6_neu Prognose");
            require(u6 != null, "BAU: U6 line missing");
            TransitRoute pattern = u6.getRoutes().values().stream().filter(r -> hasStop(r, "Harras") && hasStop(r, "Münchner Freiheit"))
                    .findFirst().orElseThrow();
            TransitStopFacility harras = namedStop(pattern, "Harras");
            TransitStopFacility freiheit = namedStop(pattern, "Münchner Freiheit");
            int h = pattern.getStops().stream().map(TransitRouteStop::getStopFacility).toList().indexOf(harras);
            int f = pattern.getStops().stream().map(TransitRouteStop::getStopFacility).toList().indexOf(freiheit);
            TransitStopFacility first = h < f ? harras : freiheit;
            TransitStopFacility last = h < f ? freiheit : harras;
            route(router, scenario, "BAU-existing-PT", first.getId().toString(), last.getId().toString(),
                    10 * 3600, Set.of());
        }
    }

    private static boolean hasStop(TransitRoute route, String name) {
        return route.getStops().stream().anyMatch(s -> name.equals(s.getStopFacility().getName()));
    }

    private static TransitStopFacility namedStop(TransitRoute route, String name) {
        return route.getStops().stream().map(TransitRouteStop::getStopFacility).filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
    }

    private record RoutingAnchor(String fromStopId, String insertedStopId, double departureTime) {}

    private static RoutingAnchor precedingServiceAnchor(Scenario scenario, String insertedPrefix) {
        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                List<TransitRouteStop> stops = route.getStops();
                for (int i = 1; i < stops.size(); i++) {
                    String inserted = stops.get(i).getStopFacility().getId().toString();
                    if (!inserted.startsWith(insertedPrefix)) continue;
                    for (Departure departure : route.getDepartures().values()) {
                        double time = departure.getDepartureTime()
                                + stops.get(i - 1).getDepartureOffset().orElse(stops.get(i - 1).getArrivalOffset().orElse(0));
                        if (time >= 9.5 * 3600 && time <= 10.5 * 3600) {
                            return new RoutingAnchor(stops.get(i - 1).getStopFacility().getId().toString(), inserted, time);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No daytime service anchor for " + insertedPrefix);
    }

    private static void route(SwissRailRaptor router, Scenario scenario, String test, String fromId, String toId,
                              double departure, Set<String> expectedLines) {
        TransitStopFacility fromStop = facility(scenario, fromId);
        TransitStopFacility toStop = facility(scenario, toId);
        Facility from = FacilitiesUtils.wrapLinkAndCoord(scenario.getNetwork().getLinks().get(fromStop.getLinkId()), fromStop.getCoord());
        Facility to = FacilitiesUtils.wrapLinkAndCoord(scenario.getNetwork().getLinks().get(toStop.getLinkId()), toStop.getCoord());
        Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("routing-test"));
        List<? extends PlanElement> elements = null;
        double usedDeparture = departure;
        for (int step = 0; step <= 120 && elements == null; step++) {
            usedDeparture = departure + step * 30;
            List<? extends PlanElement> candidate = router.calcRoute(from, to, usedDeparture, usedDeparture, usedDeparture, person, person.getAttributes());
            if (candidate != null && expectedLines.stream().allMatch(expected -> candidate.stream()
                    .filter(Leg.class::isInstance).map(Leg.class::cast)
                    .filter(leg -> leg.getRoute() instanceof TransitPassengerRoute)
                    .map(leg -> (TransitPassengerRoute) leg.getRoute())
                    .map(pt -> sourceRouteId(scenario.getTransitSchedule().getTransitLines().get(pt.getLineId())))
                    .anyMatch(source -> source.contains(expected)))) elements = candidate;
        }
        require(elements != null, test + ": router returned no connection between 10:00 and 11:00");
        List<String> lines = new ArrayList<>();
        double inVehicle = 0;
        double other = 0;
        for (PlanElement element : elements) {
            if (element instanceof Leg leg) {
                double time = leg.getTravelTime().orElse(0);
                if (leg.getRoute() instanceof TransitPassengerRoute pt) {
                    TransitLine line = scenario.getTransitSchedule().getTransitLines().get(pt.getLineId());
                    String source = line == null ? pt.getLineId().toString() : sourceRouteId(line);
                    lines.add(source + "[" + pt.getAccessStopId() + "->" + pt.getEgressStopId() + "]");
                    inVehicle += time;
                } else {
                    other += time;
                }
            }
        }
        require(!lines.isEmpty(), test + ": no PT leg returned");
        for (String expected : expectedLines) require(lines.stream().anyMatch(x -> x.contains(expected)),
                test + ": expected line not used: " + expected + "; actual=" + lines);
        if (test.startsWith("Impler-Pocci")) {
            require(lines.stream().anyMatch(x -> x.contains("FT_U9_IMPLER_POCCI")), test + ": U9 was not left at Impler-/Poccistrasse");
            require(other >= 300, test + ": documented 300-second transfer was not represented; other=" + other);
        }
        if (test.startsWith("U9-at-Hauptbahnhof")) {
            require(lines.stream().anyMatch(x -> x.contains("FT_U9_HAUPTBAHNHOF")), test + ": U9 was not left at Hauptbahnhof");
        }
        if (test.startsWith("U6-to-U9")) {
            require(lines.stream().anyMatch(x -> x.contains("FT_U9_MUENCHNER_FREIHEIT")), test + ": U9 was not boarded at Muenchner Freiheit");
        }
        if (test.startsWith("Poccistrasse-regional-reachable")) {
            require(lines.stream().anyMatch(x -> x.contains("BAU_POCCISTRASSE_RAIL_")),
                    test + ": no regional trip reached the new platform; actual=" + lines);
        }
        if (test.startsWith("Berduxstrasse-S2-reachable")) {
            require(lines.stream().anyMatch(x -> x.contains("BAU_BERDUXSTRASSE_S2_")),
                    test + ": S2 did not reach the new platform; actual=" + lines);
        }
        if (test.startsWith("Poccistrasse-regional-to-U3-U6")) {
            require(lines.stream().anyMatch(x -> x.contains("BAU_POCCISTRASSE_RAIL_")),
                    test + ": regional leg did not alight at Poccistraße; actual=" + lines);
            require(lines.stream().anyMatch(x -> x.contains("106211") || x.contains("106212")),
                    test + ": subway leg did not board at Poccistraße; actual=" + lines);
            require(other >= 180, test + ": 180-second minimum transfer was not represented; other=" + other);
        }
        double total = elements.stream().filter(Leg.class::isInstance).map(Leg.class::cast)
                .mapToDouble(leg -> leg.getTravelTime().orElse(0)).sum();
        System.out.printf("ROUTE PASS %s: %s -> %s depart=%s lines=%s transfers=%d inVehicle=%.0f other=%.0f total=%.0f%n",
                test, fromStop.getName(), toStop.getName(), time(usedDeparture), lines, Math.max(0, lines.size() - 1), inVehicle, other, total);
    }

    private static TransitStopFacility facility(Scenario scenario, String id) {
        TransitStopFacility direct = scenario.getTransitSchedule().getFacilities().get(Id.create(id, TransitStopFacility.class));
        if (direct != null) return direct;
        return scenario.getTransitSchedule().getFacilities().values().stream()
                .filter(f -> f.getId().toString().equals(id) || f.getId().toString().startsWith(id + "."))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing test stop " + id));
    }

    private static String sourceRouteId(TransitLine line) {
        if (line.getName() != null && !line.getName().isBlank()) return line.getName();
        Object shortName = line.getAttributes().getAttribute("gtfs_route_short_name");
        return shortName == null ? line.getId().toString() : shortName.toString();
    }

    private static String time(double seconds) {
        return "%02d:%02d:%02d".formatted((int) seconds / 3600, (int) seconds / 60 % 60, (int) seconds % 60);
    }

    private static void dumpLine(Scenario scenario, String lineId) {
        TransitLine line = findLine(scenario, lineId);
        line.getRoutes().values().stream().sorted(Comparator.comparing(r -> r.getId().toString())).forEach(route -> {
            List<String> stops = new ArrayList<>();
            for (TransitRouteStop stop : route.getStops()) stops.add(stop.getStopFacility().getId() + "=" + stop.getStopFacility().getName());
            System.out.println("PATTERN " + lineId + " " + route.getId() + ": " + String.join(" | ", stops));
        });
    }

    private static TransitLine findLine(Scenario scenario, String gtfsRouteId) {
        List<TransitLine> matches = scenario.getTransitSchedule().getTransitLines().values().stream()
                .filter(line -> line.getId().toString().equals(gtfsRouteId)
                        || line.getId().toString().endsWith(gtfsRouteId)
                        || gtfsRouteId.equals(line.getName())
                        || gtfsRouteId.equals(line.getAttributes().getAttribute("gtfs_route_short_name")))
                .toList();
        require(matches.size() <= 1, "Ambiguous MATSim line for " + gtfsRouteId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static long departuresServing(Scenario scenario, String stopPrefix) {
        return scenario.getTransitSchedule().getTransitLines().values().stream()
                .flatMap(line -> line.getRoutes().values().stream())
                .filter(route -> route.getStops().stream().anyMatch(stop ->
                        stop.getStopFacility().getId().toString().startsWith(stopPrefix)))
                .mapToLong(route -> route.getDepartures().size()).sum();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
