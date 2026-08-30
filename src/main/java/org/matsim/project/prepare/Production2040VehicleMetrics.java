package org.matsim.project.prepare;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

/**
 * Streams final-iteration vehicle events once. Vehicle distance follows the
 * MATSim 2025.0 relative-position convention for first and last links.
 */
final class Production2040VehicleMetrics implements LinkEnterEventHandler,
        VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler,
        TransitDriverStartsEventHandler, PersonEntersVehicleEventHandler,
        PersonLeavesVehicleEventHandler, ActivityEndEventHandler,
        VehicleArrivesAtFacilityEventHandler {
    private final Network network;
    private final TransitSchedule schedule;
    private final Map<Id<Person>, java.util.List<Boolean>> relevantTrips;
    private final Vehicles transitVehicles;
    private final Map<Id<Person>, Integer> currentTripIndex = new HashMap<>();
    private final Map<Id<Vehicle>, VehicleState> vehicles = new HashMap<>();
    private final Map<String, MutablePtMetric> pt = new TreeMap<>();
    private long missingLinks;
    private long missingTransitReferences;
    private long unmatchedAlightings;

    Production2040VehicleMetrics(Network network, TransitSchedule schedule,
            Map<Id<Person>, java.util.List<Boolean>> relevantTrips) {
        this(network, schedule, null, relevantTrips);
    }

    Production2040VehicleMetrics(Network network, TransitSchedule schedule,
            Vehicles transitVehicles,
            Map<Id<Person>, java.util.List<Boolean>> relevantTrips) {
        this.network = java.util.Objects.requireNonNull(network);
        this.schedule = java.util.Objects.requireNonNull(schedule);
        this.transitVehicles = transitVehicles;
        this.relevantTrips = Map.copyOf(relevantTrips);
    }

    @Override
    public void reset(int iteration) {
        vehicles.clear();
        pt.clear();
        missingLinks = 0;
        missingTransitReferences = 0;
        unmatchedAlightings = 0;
        currentTripIndex.clear();
    }

    @Override
    public void handleEvent(ActivityEndEvent event) {
        if (StageActivityTypeIdentifier.isStageActivity(event.getActType())) return;
        currentTripIndex.merge(event.getPersonId(), 0, (oldValue, ignored) -> oldValue + 1);
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        TransitStopFacility facility = schedule.getFacilities().get(event.getFacilityId());
        if (facility == null) {
            missingTransitReferences++;
        } else {
            state(event.getVehicleId()).currentFacility = facility;
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.transit = true;
        state.transitDriverStarted = true;
        state.driver = event.getDriverId();
        var line = schedule.getTransitLines().get(event.getTransitLineId());
        TransitRoute route = line == null ? null : line.getRoutes().get(event.getTransitRouteId());
        var departure = route == null ? null : route.getDepartures().get(event.getDepartureId());
        boolean validVehicle = transitVehicles == null
                || transitVehicles.getVehicles().containsKey(event.getVehicleId());
        boolean validDeparture = departure != null
                && event.getVehicleId().equals(departure.getVehicleId());
        if (route == null || !validDeparture || !validVehicle
                || route.getTransportMode() == null
                || route.getTransportMode().isBlank()) {
            state.ptMode = "unknown";
            missingTransitReferences++;
        } else {
            state.ptMode = Production2040AnalysisSpec.normalizePtRouteMode(
                    route.getTransportMode());
        }
        state.route = route;
        metric(state.ptMode);
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.networkMode = Production2040AnalysisSpec.normalizeMainMode(event.getNetworkMode());
        state.currentLink = event.getLinkId();
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) {
            addMovement(state, length * (1.0 - boundedPosition(
                    event.getRelativePositionOnLink())));
        }
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.currentLink = event.getLinkId();
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) addMovement(state, length);
    }

    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        VehicleState state = state(event.getVehicleId());
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) {
            addMovement(state, -length * (1.0 - boundedPosition(
                    event.getRelativePositionOnLink())));
        }
        state.currentLink = null;
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        VehicleState state = state(event.getVehicleId());
        if (!state.transit || event.getPersonId().equals(state.driver)) return;
        if (state.passengers.add(event.getPersonId())) {
            MutablePtMetric metric = metric(state.ptMode);
            metric.boardings++;
            boolean relevant = isRelevantTrip(event.getPersonId());
            if (relevant) metric.relevantBoardings++;
            state.boardings.put(event.getPersonId(), new Boarding(state.currentFacility,
                    relevant));
            if (state.currentFacility == null) missingTransitReferences++;
        }
    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent event) {
        VehicleState state = state(event.getVehicleId());
        if (!state.transit || event.getPersonId().equals(state.driver)) return;
        Boarding boarding = state.boardings.remove(event.getPersonId());
        if (!state.passengers.remove(event.getPersonId()) || boarding == null) {
            unmatchedAlightings++;
            return;
        }
        if (boarding.accessFacility() == null || state.currentFacility == null
                || state.route == null) {
            missingTransitReferences++;
            return;
        }
        double metres;
        try {
            metres = RouteUtils.calcDistance(state.route, boarding.accessFacility(),
                    state.currentFacility, network);
        } catch (RuntimeException error) {
            missingTransitReferences++;
            return;
        }
        if (!Double.isFinite(metres) || metres < 0) {
            missingTransitReferences++;
            return;
        }
        MutablePtMetric metric = metric(state.ptMode);
        metric.completedBoardings++;
        if (boarding.relevant()) metric.relevantCompletedBoardings++;
        metric.passengerMetres += metres;
        if (boarding.relevant()) metric.relevantPassengerMetres += metres;
    }

    private void addMovement(VehicleState state, double metres) {
        if (!Double.isFinite(metres)) return;
        state.distanceMetres += metres;
        if (!state.transit) return;
        MutablePtMetric metric = metric(state.ptMode);
        metric.vehicleMetres += metres;
    }

    private boolean isRelevantTrip(Id<Person> person) {
        java.util.List<Boolean> trips = relevantTrips.get(person);
        Integer index = currentTripIndex.get(person);
        return trips != null && index != null && index >= 0 && index < trips.size()
                && trips.get(index);
    }

    private double linkLength(Id<Link> linkId) {
        Link link = network.getLinks().get(linkId);
        if (link == null || !Double.isFinite(link.getLength()) || link.getLength() < 0) {
            missingLinks++;
            return Double.NaN;
        }
        return link.getLength();
    }

    private static double boundedPosition(double value) {
        Production2040AnalysisSpec.require(Double.isFinite(value)
                        && value >= 0.0 && value <= 1.0,
                "Vehicle traffic event has invalid relative link position " + value);
        return value;
    }

    private VehicleState state(Id<Vehicle> vehicle) {
        return vehicles.computeIfAbsent(vehicle, id -> {
            VehicleState state = new VehicleState();
            if (transitVehicles != null && transitVehicles.getVehicles().containsKey(id)) {
                state.transit = true;
            }
            return state;
        });
    }

    private MutablePtMetric metric(String mode) {
        return pt.computeIfAbsent(mode == null ? "unknown" : mode,
                ignored -> new MutablePtMetric());
    }

    Result result() {
        double carMetres = 0;
        long carVehicles = 0;
        long unassignedVehicles = 0;
        long openBoardings = 0;
        long unresolvedTransit = 0;
        for (VehicleState state : vehicles.values()) {
            Production2040AnalysisSpec.require(state.distanceMetres >= -1e-6,
                    "Negative vehicle distance after first/last-link correction");
            if (state.transit) {
                if (!state.transitDriverStarted && Math.abs(state.distanceMetres) > 1e-6) {
                    unresolvedTransit++;
                }
                openBoardings += state.boardings.size();
                continue;
            }
            if ("car".equals(state.networkMode)) {
                carMetres += Math.max(0, state.distanceMetres);
                carVehicles++;
            } else if (Math.abs(state.distanceMetres) > 1e-6) unassignedVehicles++;
        }
        Map<String, PtMetric> frozen = new TreeMap<>();
        pt.forEach((mode, value) -> frozen.put(mode, value.freeze()));
        return new Result(carMetres, carVehicles, unassignedVehicles, missingLinks,
                missingTransitReferences + unresolvedTransit, unmatchedAlightings, openBoardings,
                Map.copyOf(frozen));
    }

    record PtMetric(double vehicleMetres, double passengerMetres,
                    double relevantPassengerMetres, long boardings,
                    long relevantBoardings, long completedBoardings,
                    long relevantCompletedBoardings) { }

    record Result(double carMetres, long carVehicles, long unassignedVehicles,
                  long missingLinks, long missingTransitReferences,
                  long unmatchedAlightings, long openBoardings,
                  Map<String, PtMetric> ptByRouteMode) { }

    private static final class VehicleState {
        private boolean transit;
        private boolean transitDriverStarted;
        private String networkMode = "unknown";
        private String ptMode = "unknown";
        private Id<Person> driver;
        private TransitRoute route;
        private TransitStopFacility currentFacility;
        private Id<Link> currentLink;
        private double distanceMetres;
        private final Set<Id<Person>> passengers = new HashSet<>();
        private final Map<Id<Person>, Boarding> boardings = new HashMap<>();
    }

    private record Boarding(TransitStopFacility accessFacility, boolean relevant) { }

    private static final class MutablePtMetric {
        private double vehicleMetres;
        private double passengerMetres;
        private double relevantPassengerMetres;
        private long boardings;
        private long relevantBoardings;
        private long completedBoardings;
        private long relevantCompletedBoardings;

        private PtMetric freeze() {
            return new PtMetric(vehicleMetres, passengerMetres,
                    relevantPassengerMetres, boardings, relevantBoardings,
                    completedBoardings, relevantCompletedBoardings);
        }
    }
}
