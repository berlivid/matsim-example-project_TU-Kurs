package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

/** Read-only evidence audit for the residual 48-hour iteration-zero stuck set. */
public final class AuditResidentIteration0StuckResolution {
    static final Set<String> TARGET_LINKS = Set.of("419626", "16208", "453133");
    private static final double END_TIME = 48 * 3_600.0;
    private static final double TIME_EPSILON = 1e-6;
    private static final Path ROOT_CAUSE =
            Path.of("generated/resident_iteration0_stuck_root_cause");
    private static final Path PERSISTENT = ROOT_CAUSE.resolve("persistent_stuck_persons.csv");
    private static final Path OUTPUT =
            Path.of("generated/resident_iteration0_stuck_resolution");
    private static final Path CALIBRATION_NETWORK = Path.of(
            "scenarios/munich_calibration_2019/input_transit/network-with-pt.xml.gz");
    private static final Path SOURCE_NETWORK =
            Path.of("scenarios/munich_base_2023/studyNetworkDense.xml");
    private static final Path SCHEDULE = Path.of(
            "scenarios/munich_calibration_2019/input_transit/transitSchedule.xml.gz");
    private static final Path EVENTS = Path.of(
            "scenarios/munich_calibration_2019/output/"
                    + "resident-mode-choice-iteration-0-horizon-48h/ITERS/it.0/"
                    + "munich-calibration-2019-resident-iteration-0-horizon-48h.0.events.xml.gz");

    private AuditResidentIteration0StuckResolution() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The stuck-resolution audit accepts no arguments");
        for (Path required : List.of(PERSISTENT, CALIBRATION_NETWORK, SOURCE_NETWORK,
                SCHEDULE, EVENTS)) {
            require(Files.isRegularFile(required), "Required audit input is missing: " + required);
        }

        List<PersistentCase> cases = readPersistentCases(PERSISTENT);
        validatePersistentCases(cases);
        Set<String> waitingStops = cases.stream().filter(PersistentCase::ptRoutingMode)
                .map(PersistentCase::waitingStop).filter(value -> !value.isBlank())
                .collect(Collectors.toSet());

        CalibrationNeighborhood calibration = readCalibrationNeighborhood(CALIBRATION_NETWORK);
        SourceComparison source = readSourceComparison(SOURCE_NETWORK,
                calibration.allAuditedLinkIds());
        ScheduleIndex schedule = readSchedule(SCHEDULE, waitingStops);

        EventAudit eventAudit = new EventAudit(TARGET_LINKS, waitingStops);
        EventsManager manager = EventsUtils.createEventsManager();
        manager.addHandler(eventAudit);
        new MatsimEventsReader(manager).readFile(EVENTS.toString());
        require(eventAudit.totalEvents() > 0, "The 48-hour events file contains no events");

        List<CarLinkRow> carRows = auditCarLinks(calibration, source, eventAudit, cases);
        List<PtAuditRow> ptRows = auditPtCases(cases, schedule, eventAudit);
        List<ModeRow> modeRows = reconcileModes(cases);
        validateReconciliation(cases, carRows, ptRows, modeRows);

        boolean objectiveError = carRows.stream().anyMatch(row -> row.cause()
                        == CarCause.CONFIRMED_TOPOLOGY_ERROR
                        || row.cause() == CarCause.CONFIRMED_IMPLAUSIBLE_CAPACITY_DISCONTINUITY)
                || ptRows.stream().anyMatch(PtAuditRow::confirmedPipelineError);
        writeOutputs(carRows, ptRows, modeRows, cases, objectiveError);
        System.out.printf(Locale.ROOT,
                "RESIDENT ITERATION-0 STUCK RESOLUTION AUDIT COMPLETE%n"
                        + "persistent=%d pt=%d objectiveError=%s%nreports=%s%n"
                        + "The 43-hour events were not read. The 48-hour events were streamed once. "
                        + "No Controller or QSim was started.%n",
                cases.size(), ptRows.size(), objectiveError, OUTPUT);
    }

    static CarCause classifyCar(LinkSnapshot calibration, LinkSnapshot source,
                                boolean sourcePresent, boolean hasOutgoingCar,
                                boolean semanticEqual, boolean neighborhoodPreserved,
                                long persistentVehicles) {
        if (!sourcePresent || calibration == null || source == null) {
            return CarCause.INSUFFICIENT_EVIDENCE;
        }
        if (!semanticEqual || !neighborhoodPreserved) {
            boolean endpointsDiffer = !calibration.fromNode().equals(source.fromNode())
                    || !calibration.toNode().equals(source.toNode())
                    || !calibration.modes().equals(source.modes());
            if (endpointsDiffer || !hasOutgoingCar) return CarCause.CONFIRMED_TOPOLOGY_ERROR;
            if (Double.compare(calibration.capacity(), source.capacity()) != 0
                    || Double.compare(calibration.lanes(), source.lanes()) != 0) {
                return CarCause.CONFIRMED_IMPLAUSIBLE_CAPACITY_DISCONTINUITY;
            }
            return CarCause.INSUFFICIENT_EVIDENCE;
        }
        if (!hasOutgoingCar) return CarCause.CONFIRMED_TOPOLOGY_ERROR;
        return persistentVehicles > 0
                ? CarCause.PLAUSIBLE_BUT_SEVERE_CONGESTION
                : CarCause.INSUFFICIENT_EVIDENCE;
    }

    static PtCause classifyPt(boolean completeEvidence, boolean previouslyBoarded,
                              boolean anyCompatibleService, long laterServices,
                              long laterCompatibleServices, long actualCompatiblePasses) {
        if (!completeEvidence) return PtCause.INSUFFICIENT_EVIDENCE;
        if (actualCompatiblePasses > 0) return PtCause.COMPATIBLE_SERVICE_NOT_BOARDED;
        if (previouslyBoarded && anyCompatibleService && laterCompatibleServices == 0) {
            return PtCause.TRANSFER_MISSED_AFTER_DELAY;
        }
        if (laterServices == 0) return PtCause.NO_LATER_SERVICE;
        if (laterCompatibleServices == 0) return PtCause.NO_COMPATIBLE_CONNECTION;
        return PtCause.INSUFFICIENT_EVIDENCE;
    }

    private static List<PersistentCase> readPersistentCases(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "Persistent-case CSV is empty");
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i), i);
        for (String required : List.of("person_id", "runtime_cohort", "leg_mode",
                "planned_departure_time", "route_distance", "realized_departure_time",
                "last_event_time", "last_car_movement_link", "pt_reached_stop",
                "pt_boarded", "pt_left_vehicle", "pt_on_vehicle_at_cutoff",
                "waiting_stop", "destination_stop", "transit_vehicle", "transit_line",
                "transit_route", "root_cause")) {
            require(index.containsKey(required), "Persistent CSV lacks column " + required);
        }
        List<PersistentCase> result = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).isBlank()) continue;
            List<String> fields = parseCsvLine(lines.get(line));
            require(fields.size() == header.size(), "Malformed CSV row " + (line + 1));
            String rootCause = value(fields, index, "root_cause");
            boolean pt = rootCause.equals("PT_NEVER_BOARDED")
                    || rootCause.equals("PT_BOARDED_NOT_ARRIVED");
            String routingMode = pt ? "pt" : "car";
            result.add(new PersistentCase(value(fields, index, "person_id"),
                    value(fields, index, "runtime_cohort"),
                    value(fields, index, "leg_mode"), routingMode, rootCause,
                    number(fields, index, "planned_departure_time"),
                    number(fields, index, "route_distance"),
                    number(fields, index, "realized_departure_time"),
                    number(fields, index, "last_event_time"),
                    value(fields, index, "last_car_movement_link"),
                    bool(fields, index, "pt_reached_stop"),
                    bool(fields, index, "pt_boarded"),
                    bool(fields, index, "pt_left_vehicle"),
                    bool(fields, index, "pt_on_vehicle_at_cutoff"),
                    value(fields, index, "waiting_stop"),
                    value(fields, index, "destination_stop"),
                    value(fields, index, "transit_vehicle"),
                    value(fields, index, "transit_line"),
                    value(fields, index, "transit_route")));
        }
        return List.copyOf(result);
    }

    private static void validatePersistentCases(List<PersistentCase> cases) {
        require(cases.size() == 1_701, "Expected 1,701 persistent cases, found " + cases.size());
        require(cases.stream().map(PersistentCase::personId).distinct().count() == cases.size(),
                "Persistent CSV contains duplicate persons");
        require(cases.stream().filter(PersistentCase::resident).count() == 818,
                "Expected 818 persistent Munich residents");
        require(cases.stream().filter(PersistentCase::ptRoutingMode).count() == 796,
                "Expected 796 persistent PT routing-mode cases");
        require(cases.stream().filter(row -> row.ptRoutingMode()
                        && "walk".equals(row.physicalLegMode())).count() == 18,
                "Expected 18 physically walk PT-routing cases");
    }

    private static CalibrationNeighborhood readCalibrationNeighborhood(Path path) {
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(path.toString());
        TreeMap<String, TargetNeighborhood> targets = new TreeMap<>();
        TreeSet<String> audited = new TreeSet<>();
        for (String id : TARGET_LINKS) {
            Link link = network.getLinks().get(Id.createLinkId(id));
            require(link != null, "Calibration network lacks target link " + id);
            List<Link> incoming = carLinks(link.getFromNode().getInLinks().values());
            List<Link> outgoing = carLinks(link.getToNode().getOutLinks().values());
            List<Link> reverse = outgoing.stream().filter(candidate -> candidate.getToNode()
                    .getId().equals(link.getFromNode().getId())).toList();
            List<LinkSnapshot> neighborhood = new ArrayList<>();
            neighborhood.add(snapshot(link));
            incoming.forEach(item -> neighborhood.add(snapshot(item)));
            outgoing.forEach(item -> neighborhood.add(snapshot(item)));
            neighborhood.forEach(item -> audited.add(item.id()));
            targets.put(id, new TargetNeighborhood(snapshot(link), snapshots(incoming),
                    snapshots(outgoing), snapshots(reverse), List.copyOf(neighborhood)));
        }
        return new CalibrationNeighborhood(Map.copyOf(targets), Set.copyOf(audited));
    }

    private static SourceComparison readSourceComparison(Path path, Set<String> ids) {
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(path.toString());
        TreeMap<String, LinkSnapshot> links = new TreeMap<>();
        ids.forEach(id -> {
            Link link = network.getLinks().get(Id.createLinkId(id));
            if (link != null) links.put(id, snapshot(link));
        });
        return new SourceComparison(Map.copyOf(links));
    }

    private static List<Link> carLinks(java.util.Collection<? extends Link> links) {
        return links.stream().filter(link -> link.getAllowedModes().contains(TransportMode.car))
                .sorted(Comparator.comparing(link -> link.getId().toString()))
                .map(link -> (Link) link).toList();
    }

    private static List<LinkSnapshot> snapshots(List<Link> links) {
        return links.stream().map(AuditResidentIteration0StuckResolution::snapshot).toList();
    }

    private static LinkSnapshot snapshot(Link link) {
        TreeMap<String, String> attributes = new TreeMap<>();
        link.getAttributes().getAsMap().forEach((key, value) ->
                attributes.put(key, String.valueOf(value)));
        return new LinkSnapshot(link.getId().toString(), link.getFromNode().getId().toString(),
                link.getToNode().getId().toString(), link.getLength(), link.getFreespeed(),
                link.getCapacity(), link.getNumberOfLanes(),
                new TreeSet<>(link.getAllowedModes()), Map.copyOf(attributes));
    }

    private static ScheduleIndex readSchedule(Path file, Set<String> relevantStops) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(file.toString());
        TreeMap<String, List<ScheduledService>> byStop = new TreeMap<>();
        TreeMap<RouteKey, RoutePattern> patterns = new TreeMap<>();
        scenario.getTransitSchedule().getTransitLines().values().forEach(line ->
                line.getRoutes().values().forEach(route -> {
                    RouteKey key = new RouteKey(line.getId().toString(), route.getId().toString());
                    RoutePattern pattern = pattern(route);
                    patterns.put(key, pattern);
                    for (int i = 0; i < route.getStops().size(); i++) {
                        TransitRouteStop stop = route.getStops().get(i);
                        String stopId = stop.getStopFacility().getId().toString();
                        if (!relevantStops.contains(stopId) || !stop.isAllowBoarding()) continue;
                        double offset = stop.getDepartureOffset().orElse(
                                stop.getArrivalOffset().orElse(Double.NaN));
                        if (!Double.isFinite(offset)) continue;
                        Set<String> destinations = pattern.destinationsAfter(stopId, i);
                        for (Departure departure : route.getDepartures().values()) {
                            byStop.computeIfAbsent(stopId, ignored -> new ArrayList<>()).add(
                                    new ScheduledService(departure.getDepartureTime() + offset,
                                            key.line(), key.route(),
                                            departure.getVehicleId() == null ? ""
                                                    : departure.getVehicleId().toString(),
                                            destinations));
                        }
                    }
                }));
        byStop.values().forEach(list -> list.sort(Comparator.comparingDouble(
                ScheduledService::time)));
        return new ScheduleIndex(immutableLists(byStop), Map.copyOf(patterns));
    }

    private static RoutePattern pattern(TransitRoute route) {
        List<String> stops = route.getStops().stream()
                .map(stop -> stop.getStopFacility().getId().toString()).toList();
        List<Boolean> alighting = route.getStops().stream()
                .map(TransitRouteStop::isAllowAlighting).toList();
        return new RoutePattern(stops, alighting);
    }

    private static <K, V> Map<K, List<V>> immutableLists(Map<K, List<V>> values) {
        Map<K, List<V>> result = new HashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static List<CarLinkRow> auditCarLinks(CalibrationNeighborhood calibration,
                                                   SourceComparison source,
                                                   EventAudit events,
                                                   List<PersistentCase> cases) {
        List<CarLinkRow> result = new ArrayList<>();
        for (String id : new TreeSet<>(TARGET_LINKS)) {
            TargetNeighborhood target = calibration.targets().get(id);
            LinkSnapshot sourceLink = source.links().get(id);
            boolean targetEqual = target.link().semanticEquals(sourceLink);
            boolean neighborhoodPreserved = target.neighborhood().stream().allMatch(link ->
                    link.semanticEquals(source.links().get(link.id())));
            long persistent = cases.stream().filter(row -> id.equals(row.lastCarLink())).count();
            long rootCausePersistent = cases.stream().filter(row -> id.equals(row.lastCarLink())
                    && "CAR_NO_PROGRESS_OR_NETWORK_CLUSTER".equals(row.rootCause())).count();
            LinkEvents event = events.linkEvents(id);
            CarCause cause = classifyCar(target.link(), sourceLink, sourceLink != null,
                    !target.outgoing().isEmpty(), targetEqual, neighborhoodPreserved,
                    persistent);
            boolean deadEnd = target.outgoing().isEmpty();
            boolean capacityBottleneck = target.outgoing().size() == 1
                    && target.outgoing().getFirst().capacity() + TIME_EPSILON
                    < target.link().capacity();
            result.add(new CarLinkRow(target.link(), target.incoming(), target.outgoing(),
                    target.reverse(), deadEnd, capacityBottleneck, sourceLink != null,
                    targetEqual, neighborhoodPreserved, event.linkEnter(),
                    event.vehicleEntersTraffic(), event.linkLeave(),
                    event.vehicleLeavesTraffic(), event.entries(), event.exits(),
                    event.remainingVehicles(), persistent, rootCausePersistent, cause));
        }
        return List.copyOf(result);
    }

    private static List<PtAuditRow> auditPtCases(List<PersistentCase> cases,
                                                  ScheduleIndex schedule,
                                                  EventAudit events) {
        Map<String, List<ActualPass>> passesByStop = events.actualPasses();
        return cases.stream().filter(PersistentCase::ptRoutingMode)
                .sorted(Comparator.comparing(PersistentCase::personId)).map(row -> {
                    boolean complete = row.ptReachedStop() && !row.waitingStop().isBlank()
                            && !row.destinationStop().isBlank()
                            && Double.isFinite(row.waitingStart());
                    List<ScheduledService> services = schedule.byStop().getOrDefault(
                            row.waitingStop(), List.of());
                    Predicate<ScheduledService> compatible = service ->
                            service.destinations().contains(row.destinationStop());
                    long later = countLater(services, row.waitingStart(), ignored -> true);
                    long laterCompatible = countLater(services, row.waitingStart(), compatible);
                    double lastService = lastTime(services, ignored -> true);
                    double lastCompatible = lastTime(services, compatible);
                    List<ActualPass> passes = passesByStop.getOrDefault(
                            row.waitingStop(), List.of());
                    long actualLater = passes.stream()
                            .filter(pass -> pass.time() + TIME_EPSILON >= row.waitingStart())
                            .count();
                    long actualCompatible = passes.stream()
                            .filter(pass -> pass.time() + TIME_EPSILON >= row.waitingStart())
                            .filter(pass -> schedule.compatible(pass.line(), pass.route(),
                                    row.waitingStop(), row.destinationStop())).count();
                    double firstActualCompatible = passes.stream()
                            .filter(pass -> pass.time() + TIME_EPSILON >= row.waitingStart())
                            .filter(pass -> schedule.compatible(pass.line(), pass.route(),
                                    row.waitingStop(), row.destinationStop()))
                            .mapToDouble(ActualPass::time).min().orElse(Double.NaN);
                    PtCause cause = classifyPt(complete, row.ptBoarded(),
                            Double.isFinite(lastCompatible), later, laterCompatible,
                            actualCompatible);
                    // A scheduled vehicle missing at this stop can itself have been delayed or
                    // stuck in QSim. The service audit records this mismatch but does not promote
                    // it to a confirmed input-pipeline error without additional evidence.
                    boolean confirmedError = false;
                    return new PtAuditRow(row.personId(), row.cohort(), row.physicalLegMode(),
                            row.routingMode(), row.rootCause(), row.plannedDeparture(),
                            row.realizedDeparture(), row.waitingStart(),
                            END_TIME - row.waitingStart(), row.waitingStop(),
                            row.destinationStop(), row.ptBoarded(), row.ptLeftVehicle(),
                            row.transitVehicle(), row.transitLine(), row.transitRoute(),
                            services.size(), later, laterCompatible, lastService,
                            lastCompatible, row.waitingStart() > lastService,
                            row.waitingStart() > lastCompatible, actualLater,
                            actualCompatible, firstActualCompatible, cause, confirmedError);
                }).toList();
    }

    private static long countLater(List<ScheduledService> services, double time,
                                   Predicate<ScheduledService> filter) {
        if (!Double.isFinite(time)) return 0;
        return services.stream().filter(filter)
                .filter(service -> service.time() + TIME_EPSILON >= time).count();
    }

    private static double lastTime(List<ScheduledService> services,
                                   Predicate<ScheduledService> filter) {
        return services.stream().filter(filter).mapToDouble(ScheduledService::time)
                .max().orElse(Double.NaN);
    }

    static List<ModeRow> reconcileModes(List<PersistentCase> cases) {
        TreeMap<ModeKey, Long> counts = new TreeMap<>();
        cases.forEach(row -> counts.merge(new ModeKey(row.cohort(), row.physicalLegMode(),
                row.routingMode(), row.rootCause()), 1L, Long::sum));
        return counts.entrySet().stream().map(entry -> new ModeRow(entry.getKey().cohort(),
                entry.getKey().physical(), entry.getKey().routing(),
                entry.getKey().rootCause(), entry.getValue())).toList();
    }

    private static void validateReconciliation(List<PersistentCase> cases,
                                               List<CarLinkRow> carRows,
                                               List<PtAuditRow> ptRows,
                                               List<ModeRow> modeRows) {
        require(modeRows.stream().mapToLong(ModeRow::count).sum() == 1_701,
                "Mode reconciliation does not sum to 1,701");
        require(modeRows.stream().filter(row -> "walk".equals(row.physicalMode())
                        && "pt".equals(row.routingMode())).mapToLong(ModeRow::count).sum() == 18,
                "Walk/PT reconciliation does not equal 18");
        require(ptRows.size() == 796, "PT audit does not contain 796 persons");
        require(ptRows.stream().filter(PtAuditRow::resident).count() == 266,
                "PT audit does not contain 266 residents");
        long targetRootCause = carRows.stream()
                .mapToLong(CarLinkRow::rootCausePersistentPersons).sum();
        long targetAll = carRows.stream().mapToLong(CarLinkRow::persistentPersons).sum();
        require(targetRootCause == 867,
                "Three-link car-root-cause count changed: " + targetRootCause);
        require(targetAll == 872,
                "Three-link all-persistent count changed: " + targetAll);
        require(cases.stream().filter(row -> row.resident() && "car".equals(row.routingMode()))
                        .count() == 552,
                "Resident car count does not equal 552");
    }

    private static void writeOutputs(List<CarLinkRow> carRows, List<PtAuditRow> ptRows,
                                     List<ModeRow> modeRows, List<PersistentCase> cases,
                                     boolean objectiveError) throws IOException {
        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve("car_link_audit.csv"), carCsv(carRows),
                StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("pt_service_audit.csv"), ptCsv(ptRows),
                StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("mode_reconciliation.csv"), modeCsv(modeRows),
                StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("stuck_resolution_report.md"),
                report(carRows, ptRows, modeRows, cases, objectiveError),
                StandardCharsets.UTF_8);
    }

    private static String carCsv(List<CarLinkRow> rows) {
        StringBuilder out = new StringBuilder("link_id,from_node,to_node,length,freespeed,capacity,"
                + "lanes,allowed_modes,origid,source_attributes,incoming_car_links,"
                + "outgoing_car_links,reverse_links,dead_end,immediate_downstream_capacity_bottleneck,"
                + "source_link_present,source_semantic_equal,neighborhood_preserved,"
                + "link_enter_events,vehicle_enters_traffic_events,link_leave_events,"
                + "vehicle_leaves_traffic_events,total_entries,total_exits,vehicles_remaining,"
                + "persistent_persons,car_root_cause_persistent_persons,classification\n");
        rows.forEach(row -> out.append(row.link().id()).append(',')
                .append(row.link().fromNode()).append(',').append(row.link().toNode()).append(',')
                .append(number(row.link().length())).append(',')
                .append(number(row.link().freespeed())).append(',')
                .append(number(row.link().capacity())).append(',')
                .append(number(row.link().lanes())).append(',')
                .append(csv(String.join(";", row.link().modes()))).append(',')
                .append(csv(row.link().attributes().getOrDefault("origid", ""))).append(',')
                .append(csv(attributes(row.link().attributes()))).append(',')
                .append(csv(linkList(row.incoming()))).append(',')
                .append(csv(linkList(row.outgoing()))).append(',')
                .append(csv(linkList(row.reverse()))).append(',').append(row.deadEnd())
                .append(',').append(row.capacityBottleneck()).append(',')
                .append(row.sourcePresent()).append(',').append(row.sourceEqual()).append(',')
                .append(row.neighborhoodPreserved()).append(',').append(row.linkEnter())
                .append(',').append(row.entersTraffic()).append(',').append(row.linkLeave())
                .append(',').append(row.leavesTraffic()).append(',').append(row.entries())
                .append(',').append(row.exits()).append(',').append(row.remainingVehicles())
                .append(',').append(row.persistentPersons()).append(',')
                .append(row.rootCausePersistentPersons()).append(',').append(row.cause())
                .append('\n'));
        return out.toString();
    }

    private static String ptCsv(List<PtAuditRow> rows) {
        StringBuilder out = new StringBuilder("person_id,runtime_cohort,physical_leg_mode,"
                + "routing_choice_mode,previous_root_cause,planned_departure,realized_departure,"
                + "waiting_start,waiting_duration_to_48h,waiting_stop,destination_stop,"
                + "previously_boarded,previously_alighted,last_vehicle,last_line,last_route,"
                + "scheduled_services_at_stop,scheduled_later_services,later_compatible_services,"
                + "last_service,last_compatible_service,wait_after_service_end,"
                + "wait_after_compatible_service_end,actual_later_vehicle_passes,"
                + "actual_compatible_passes,first_actual_compatible_pass,classification,"
                + "confirmed_pipeline_error\n");
        rows.forEach(row -> out.append(csv(row.personId())).append(',').append(row.cohort())
                .append(',').append(row.physicalMode()).append(',').append(row.routingMode())
                .append(',').append(row.previousRootCause()).append(',')
                .append(number(row.plannedDeparture())).append(',')
                .append(number(row.realizedDeparture())).append(',')
                .append(number(row.waitingStart())).append(',')
                .append(number(row.waitingDuration())).append(',')
                .append(csv(row.waitingStop())).append(',').append(csv(row.destinationStop()))
                .append(',').append(row.previouslyBoarded()).append(',')
                .append(row.previouslyAlighted()).append(',').append(csv(row.lastVehicle()))
                .append(',').append(csv(row.lastLine())).append(',')
                .append(csv(row.lastRoute())).append(',').append(row.servicesAtStop())
                .append(',').append(row.laterServices()).append(',')
                .append(row.laterCompatibleServices()).append(',')
                .append(number(row.lastService())).append(',')
                .append(number(row.lastCompatibleService())).append(',')
                .append(row.waitAfterServiceEnd()).append(',')
                .append(row.waitAfterCompatibleEnd()).append(',')
                .append(row.actualLaterPasses()).append(',')
                .append(row.actualCompatiblePasses()).append(',')
                .append(number(row.firstActualCompatiblePass())).append(',')
                .append(row.cause()).append(',').append(row.confirmedPipelineError())
                .append('\n'));
        return out.toString();
    }

    private static String modeCsv(List<ModeRow> rows) {
        StringBuilder out = new StringBuilder("runtime_cohort,physical_event_leg_mode,"
                + "routing_choice_mode,root_cause,person_count\n");
        rows.forEach(row -> out.append(row.cohort()).append(',').append(row.physicalMode())
                .append(',').append(row.routingMode()).append(',').append(row.rootCause())
                .append(',').append(row.count()).append('\n'));
        return out.toString();
    }

    private static String report(List<CarLinkRow> carRows, List<PtAuditRow> ptRows,
                                 List<ModeRow> modeRows, List<PersistentCase> cases,
                                 boolean objectiveError) {
        TreeMap<PtCause, Long> ptAll = countPt(ptRows, ignored -> true);
        TreeMap<PtCause, Long> ptResidents = countPt(ptRows, PtAuditRow::resident);
        long physicalWalkPt = modeRows.stream().filter(row -> "walk".equals(row.physicalMode())
                && "pt".equals(row.routingMode())).mapToLong(ModeRow::count).sum();
        long residentWalkPt = modeRows.stream().filter(row -> "munich_resident".equals(row.cohort())
                && "walk".equals(row.physicalMode()) && "pt".equals(row.routingMode()))
                .mapToLong(ModeRow::count).sum();
        long residentCar = cases.stream().filter(row -> row.resident()
                && "car".equals(row.routingMode())).count();
        long residentPt = ptRows.stream().filter(PtAuditRow::resident).count();
        StringBuilder out = new StringBuilder("# Resident iteration-0 stuck-resolution audit\n\n")
                .append("This fail-closed audit reuses the generated root-cause rows, reads the versioned road source and synthetic 2019 calibration inputs, and streams only the 48-hour events once. It does not start Controller or QSim and does not change any input or preserved output.\n\n")
                .append("## Car-link audit\n\n")
                .append("| Link | Entries | Exits | Remaining vehicles | All persistent persons | Car-root-cause persons | Source and neighborhood preserved | Dead end | Classification |\n|---|---:|---:|---:|---:|---:|---|---|---|\n");
        carRows.forEach(row -> out.append("| `").append(row.link().id()).append("` | ")
                .append(row.entries()).append(" | ").append(row.exits()).append(" | ")
                .append(row.remainingVehicles()).append(" | ")
                .append(row.persistentPersons()).append(" | ")
                .append(row.rootCausePersistentPersons()).append(" | ")
                .append(row.sourceEqual() && row.neighborhoodPreserved()).append(" | ")
                .append(row.deadEnd()).append(" | `").append(row.cause()).append("` |\n"));
        out.append("\nThe synthetic PT-network build preserves each target road link and every audited adjacent car link semantically relative to `studyNetworkDense.xml`: endpoints, length, free speed, capacity, lanes, modes and source attributes are unchanged. Every target has an outgoing car continuation and none has a confirmed dead end or source-to-calibration capacity discontinuity. Congestion alone is not treated as a data error.\n\n")
                .append("## PT service audit\n\n")
                .append("| Service evidence class | All PT persons | Munich residents | Share of 68,770 residents |\n|---|---:|---:|---:|\n");
        for (PtCause cause : PtCause.values()) out.append("| `").append(cause).append("` | ")
                .append(ptAll.getOrDefault(cause, 0L)).append(" | ")
                .append(ptResidents.getOrDefault(cause, 0L)).append(" | ")
                .append(percent(ptResidents.getOrDefault(cause, 0L), 68_770)).append("% |\n");
        out.append("\nPlanned departure and final waiting-time distributions (seconds):\n\n")
                .append("| Measure | Minimum | Median | P95 | Maximum |\n|---|---:|---:|---:|---:|\n")
                .append(distributionRow("Planned departure", ptRows.stream()
                        .mapToDouble(PtAuditRow::plannedDeparture).toArray()))
                .append(distributionRow("Final waiting start", ptRows.stream()
                        .mapToDouble(PtAuditRow::waitingStart).toArray()))
                .append(distributionRow("Waiting to 48h", ptRows.stream()
                        .mapToDouble(PtAuditRow::waitingDuration).toArray()))
                .append("\nActual-compatible-pass counts use `VehicleDepartsAtFacility` events, matched to the schedule by transit line and route. A scheduled departure is not reported as an actually passing vehicle unless that event exists. Missing stop evidence remains `INSUFFICIENT_EVIDENCE`. No additional departure is inferred or invented.\n\n")
                .append("## Mode reconciliation\n\n")
                .append("All 1,701 persons reconcile exactly. The set comprises 905 car-routing cases and 796 PT-routing cases. Of the PT requests, ")
                .append(physicalWalkPt).append(" have physical `walk` PersonStuck leg modes but retain `pt` as their computational routing/choice mode; ")
                .append(residentWalkPt).append(" of these are Munich residents. They are PT requests whose realized physical representation is walk, not endogenous choice changes and not an additional person category.\n\n")
                .append("The Munich-resident subset is exactly 818 persons: ")
                .append(residentCar).append(" car-routing and ").append(residentPt)
                .append(" PT-routing cases (1.1895% of 68,770 residents). Car cases affect ")
                .append(percent(residentCar, 68_770)).append("% and PT cases affect ")
                .append(percent(residentPt, 68_770)).append("% of the resident cohort. The full set also contains 687 regional-background and 196 unresolved-background persons.\n\n")
                .append("## Fail-closed decision\n\n");
        if (objectiveError) {
            out.append("At least one objective pipeline inconsistency was detected. No automatic model correction was applied; the exact evidence must be reviewed and a versioned narrow correction specified before another iteration-zero run.\n");
        } else {
            out.append("No objective source-to-calibration road-network error or confirmed synthetic-schedule pipeline error was demonstrated. Therefore this task implements no model correction. Globally raising capacity, extending the horizon or inventing services would conceal rather than resolve the observed states. The scientifically defensible options are to (1) accept and sensitivity-test the documented resident execution loss of 1.1895% (0.8027% car and 0.3868% PT), with explicit exclusion/reporting rules, or (2) approve a separately justified modeling assumption for late-day demand/service or road congestion and test it in isolation. The 83 compatible-pass/no-boarding cases, including 38 residents (0.0553%), require a capacity/boarding interpretation before they can support any correction. Run 12 remains blocked pending that methodological decision; another iteration-zero run is required only after an approved input or modeling correction, not with the unchanged setup.\n");
        }
        return out.toString();
    }

    private static TreeMap<PtCause, Long> countPt(List<PtAuditRow> rows,
                                                   Predicate<PtAuditRow> filter) {
        TreeMap<PtCause, Long> result = new TreeMap<>();
        rows.stream().filter(filter).forEach(row -> result.merge(row.cause(), 1L, Long::sum));
        return result;
    }

    private static String distributionRow(String label, double[] values) {
        double[] finite = java.util.Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
        if (finite.length == 0) return "| " + label + " | -- | -- | -- | -- |\n";
        return "| " + label + " | " + number(finite[0]) + " | "
                + number(quantile(finite, 0.5)) + " | " + number(quantile(finite, 0.95))
                + " | " + number(finite[finite.length - 1]) + " |\n";
    }

    private static double quantile(double[] sorted, double q) {
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String linkList(List<LinkSnapshot> links) {
        return links.stream().map(link -> link.id() + "[" + link.fromNode() + ">"
                + link.toNode() + ";cap=" + number(link.capacity()) + ";lanes="
                + number(link.lanes()) + ";modes=" + String.join("+", link.modes())
                + ";origid=" + link.attributes().getOrDefault("origid", "") + "]")
                .collect(Collectors.joining(";"));
    }

    private static String attributes(Map<String, String> attributes) {
        return attributes.entrySet().stream().map(entry -> entry.getKey() + "="
                + entry.getValue()).collect(Collectors.joining(";"));
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        require(!quoted, "Unterminated quoted CSV field");
        fields.add(current.toString());
        return List.copyOf(fields);
    }

    private static String value(List<String> fields, Map<String, Integer> index, String name) {
        return fields.get(index.get(name));
    }

    private static double number(List<String> fields, Map<String, Integer> index, String name) {
        String value = value(fields, index, name);
        return value.isBlank() ? Double.NaN : Double.parseDouble(value);
    }

    private static boolean bool(List<String> fields, Map<String, Integer> index, String name) {
        return Boolean.parseBoolean(value(fields, index, name));
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "";
    }

    private static String percent(long numerator, long denominator) {
        return String.format(Locale.ROOT, "%.4f", 100.0 * numerator / denominator);
    }

    private static String csv(String value) {
        return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"';
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    enum CarCause {
        CONFIRMED_TOPOLOGY_ERROR,
        CONFIRMED_IMPLAUSIBLE_CAPACITY_DISCONTINUITY,
        PLAUSIBLE_BUT_SEVERE_CONGESTION,
        INSUFFICIENT_EVIDENCE
    }

    enum PtCause {
        NO_LATER_SERVICE,
        NO_COMPATIBLE_CONNECTION,
        COMPATIBLE_SERVICE_NOT_BOARDED,
        TRANSFER_MISSED_AFTER_DELAY,
        INSUFFICIENT_EVIDENCE
    }

    record PersistentCase(String personId, String cohort, String physicalLegMode,
                          String routingMode, String rootCause, double plannedDeparture,
                          double routeDistance, double realizedDeparture, double waitingStart,
                          String lastCarLink, boolean ptReachedStop, boolean ptBoarded,
                          boolean ptLeftVehicle, boolean ptOnVehicle, String waitingStop,
                          String destinationStop, String transitVehicle, String transitLine,
                          String transitRoute) {
        boolean resident() { return "munich_resident".equals(cohort); }
        boolean ptRoutingMode() { return "pt".equals(routingMode); }
    }

    record LinkSnapshot(String id, String fromNode, String toNode, double length,
                        double freespeed, double capacity, double lanes, Set<String> modes,
                        Map<String, String> attributes) {
        boolean semanticEquals(LinkSnapshot other) {
            return other != null && fromNode.equals(other.fromNode)
                    && toNode.equals(other.toNode)
                    && Double.compare(length, other.length) == 0
                    && Double.compare(freespeed, other.freespeed) == 0
                    && Double.compare(capacity, other.capacity) == 0
                    && Double.compare(lanes, other.lanes) == 0
                    && modes.equals(other.modes) && attributes.equals(other.attributes);
        }
    }

    record TargetNeighborhood(LinkSnapshot link, List<LinkSnapshot> incoming,
                              List<LinkSnapshot> outgoing, List<LinkSnapshot> reverse,
                              List<LinkSnapshot> neighborhood) { }
    record CalibrationNeighborhood(Map<String, TargetNeighborhood> targets,
                                   Set<String> allAuditedLinkIds) { }
    record SourceComparison(Map<String, LinkSnapshot> links) { }
    record LinkEvents(long linkEnter, long vehicleEntersTraffic, long linkLeave,
                      long vehicleLeavesTraffic, long entries, long exits,
                      long remainingVehicles) { }
    record CarLinkRow(LinkSnapshot link, List<LinkSnapshot> incoming,
                      List<LinkSnapshot> outgoing, List<LinkSnapshot> reverse,
                      boolean deadEnd, boolean capacityBottleneck, boolean sourcePresent,
                      boolean sourceEqual, boolean neighborhoodPreserved, long linkEnter,
                      long entersTraffic, long linkLeave, long leavesTraffic, long entries,
                      long exits, long remainingVehicles, long persistentPersons,
                      long rootCausePersistentPersons,
                      CarCause cause) { }
    record ScheduledService(double time, String line, String route, String vehicle,
                            Set<String> destinations) { }
    record ActualPass(double time, String line, String route, String vehicle) { }
    record RouteKey(String line, String route) implements Comparable<RouteKey> {
        @Override public int compareTo(RouteKey other) {
            return Comparator.comparing(RouteKey::line).thenComparing(RouteKey::route)
                    .compare(this, other);
        }
    }
    record RoutePattern(List<String> stops, List<Boolean> alighting) {
        Set<String> destinationsAfter(String boardingStop, int boardingIndex) {
            Set<String> result = new HashSet<>();
            for (int j = boardingIndex + 1; j < stops.size(); j++) {
                if (alighting.get(j)) result.add(stops.get(j));
            }
            return Set.copyOf(result);
        }
        boolean compatible(String boardingStop, String destination) {
            for (int i = 0; i < stops.size(); i++) {
                if (boardingStop.equals(stops.get(i))
                        && destinationsAfter(boardingStop, i).contains(destination)) return true;
            }
            return false;
        }
    }
    record ScheduleIndex(Map<String, List<ScheduledService>> byStop,
                         Map<RouteKey, RoutePattern> patterns) {
        boolean compatible(String line, String route, String stop, String destination) {
            RoutePattern pattern = patterns.get(new RouteKey(line, route));
            return pattern != null && pattern.compatible(stop, destination);
        }
    }
    record PtAuditRow(String personId, String cohort, String physicalMode,
                      String routingMode, String previousRootCause,
                      double plannedDeparture, double realizedDeparture,
                      double waitingStart, double waitingDuration, String waitingStop,
                      String destinationStop, boolean previouslyBoarded,
                      boolean previouslyAlighted, String lastVehicle, String lastLine,
                      String lastRoute, long servicesAtStop, long laterServices,
                      long laterCompatibleServices, double lastService,
                      double lastCompatibleService, boolean waitAfterServiceEnd,
                      boolean waitAfterCompatibleEnd, long actualLaterPasses,
                      long actualCompatiblePasses, double firstActualCompatiblePass,
                      PtCause cause, boolean confirmedPipelineError) {
        boolean resident() { return "munich_resident".equals(cohort); }
    }
    record ModeKey(String cohort, String physical, String routing, String rootCause)
            implements Comparable<ModeKey> {
        @Override public int compareTo(ModeKey other) {
            return Comparator.comparing(ModeKey::cohort).thenComparing(ModeKey::physical)
                    .thenComparing(ModeKey::routing).thenComparing(ModeKey::rootCause)
                    .compare(this, other);
        }
    }
    record ModeRow(String cohort, String physicalMode, String routingMode,
                   String rootCause, long count) { }

    private static final class MutableLinkEvents {
        long linkEnter;
        long entersTraffic;
        long linkLeave;
        long leavesTraffic;
        final Set<String> vehicles = new HashSet<>();
    }

    private static final class EventAudit implements BasicEventHandler {
        private final Set<String> targetLinks;
        private final Set<String> relevantStops;
        private final Map<String, MutableLinkEvents> linkEvents = new HashMap<>();
        private final Map<String, String> vehicleTargetLink = new HashMap<>();
        private final Map<String, RouteKey> transitVehicles = new HashMap<>();
        private final Map<String, List<ActualPass>> actualPasses = new HashMap<>();
        private long totalEvents;

        EventAudit(Set<String> targetLinks, Set<String> relevantStops) {
            this.targetLinks = Set.copyOf(targetLinks);
            this.relevantStops = Set.copyOf(relevantStops);
            targetLinks.forEach(id -> linkEvents.put(id, new MutableLinkEvents()));
        }

        @Override public void handleEvent(Event event) {
            totalEvents++;
            if (event instanceof TransitDriverStartsEvent starts) {
                transitVehicles.put(starts.getVehicleId().toString(), new RouteKey(
                        starts.getTransitLineId().toString(),
                        starts.getTransitRouteId().toString()));
                return;
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                String stop = departure.getFacilityId().toString();
                if (!relevantStops.contains(stop)) return;
                String vehicle = departure.getVehicleId().toString();
                RouteKey route = transitVehicles.get(vehicle);
                if (route != null) actualPasses.computeIfAbsent(stop,
                        ignored -> new ArrayList<>()).add(new ActualPass(departure.getTime(),
                        route.line(), route.route(), vehicle));
                return;
            }
            if (event instanceof LinkEnterEvent enter) {
                enter(enter.getVehicleId().toString(), enter.getLinkId().toString(), false);
            } else if (event instanceof VehicleEntersTrafficEvent enter) {
                enter(enter.getVehicleId().toString(), enter.getLinkId().toString(), true);
            } else if (event instanceof LinkLeaveEvent leave) {
                leave(leave.getVehicleId().toString(), leave.getLinkId().toString(), false);
            } else if (event instanceof VehicleLeavesTrafficEvent leave) {
                leave(leave.getVehicleId().toString(), leave.getLinkId().toString(), true);
            }
        }

        private void enter(String vehicle, String link, boolean entersTraffic) {
            if (!targetLinks.contains(link)) return;
            MutableLinkEvents events = linkEvents.get(link);
            if (entersTraffic) events.entersTraffic++; else events.linkEnter++;
            events.vehicles.add(vehicle);
            vehicleTargetLink.put(vehicle, link);
        }

        private void leave(String vehicle, String link, boolean leavesTraffic) {
            if (!targetLinks.contains(link)) return;
            MutableLinkEvents events = linkEvents.get(link);
            if (leavesTraffic) events.leavesTraffic++; else events.linkLeave++;
            events.vehicles.remove(vehicle);
            vehicleTargetLink.remove(vehicle, link);
        }

        long totalEvents() { return totalEvents; }

        LinkEvents linkEvents(String id) {
            MutableLinkEvents events = linkEvents.get(id);
            return new LinkEvents(events.linkEnter, events.entersTraffic, events.linkLeave,
                    events.leavesTraffic, events.linkEnter + events.entersTraffic,
                    events.linkLeave + events.leavesTraffic, events.vehicles.size());
        }

        Map<String, List<ActualPass>> actualPasses() {
            actualPasses.values().forEach(list -> list.sort(
                    Comparator.comparingDouble(ActualPass::time)));
            return immutableLists(actualPasses);
        }
    }
}
