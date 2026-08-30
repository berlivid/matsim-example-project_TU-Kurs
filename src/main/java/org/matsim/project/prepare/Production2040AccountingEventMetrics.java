package org.matsim.project.prepare;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

/** Attributes the shared event-distance stream to demand scopes and territorial PT service. */
final class Production2040AccountingEventMetrics
        implements Production2040VehicleMetrics.MovementObserver {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double FRACTION_EPSILON = 1e-12;
    private final Network network;
    private final MunichMunicipalBoundary boundary;
    private final Production2040AccountingScopes.Index index;
    private final Map<Id<Link>, LinkClip> clips = new HashMap<>();
    private final Map<Id<Vehicle>, CarSegment> openCarSegments = new HashMap<>();
    private final Set<Id<Vehicle>> ignoredTrafficVehicles = new HashSet<>();
    private final Map<MunichTripBoundaryFilter.SpatialCategory, Double> endpointCarMetres =
            new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
    private final Map<Production2040AccountingScopes.Scope, Double> scopeCarMetres =
            new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<Production2040AccountingScopes.Scope, Set<Id<Vehicle>>> scopeCarVehicles =
            new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<Production2040AccountingScopes.Scope, Set<Production2040AccountingScopes.TripKey>>
            scopeCarTrips = new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<String, MutablePtService> pt = new TreeMap<>();
    private final Set<Production2040AccountingScopes.TripKey> stuckTrips = new HashSet<>();
    private long unmatchedPersons;
    private long unmatchedTrips;
    private long repeatedVehicleEnters;
    private long unmatchedVehicleLeaves;
    private long unattributedCarMovementEvents;

    Production2040AccountingEventMetrics(Network network, MunichMunicipalBoundary boundary,
            Production2040AccountingScopes.Index index) {
        this.network = java.util.Objects.requireNonNull(network);
        this.boundary = java.util.Objects.requireNonNull(boundary);
        this.index = java.util.Objects.requireNonNull(index);
        reset();
    }

    @Override
    public void reset() {
        clips.clear();
        openCarSegments.clear();
        ignoredTrafficVehicles.clear();
        endpointCarMetres.clear();
        scopeCarMetres.clear();
        scopeCarVehicles.clear();
        scopeCarTrips.clear();
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            endpointCarMetres.put(category, 0.0);
        }
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            scopeCarMetres.put(scope, 0.0);
            scopeCarVehicles.put(scope, new HashSet<>());
            scopeCarTrips.put(scope, new HashSet<>());
        }
        pt.clear();
        stuckTrips.clear();
        unmatchedPersons = 0;
        unmatchedTrips = 0;
        repeatedVehicleEnters = 0;
        unmatchedVehicleLeaves = 0;
        unattributedCarMovementEvents = 0;
    }

    @Override
    public void trafficEnter(Id<Vehicle> vehicle, Id<Person> person, String networkMode,
            Integer mainTripIndex, boolean transit) {
        if (transit || !"car".equals(networkMode)) {
            ignoredTrafficVehicles.add(vehicle);
            return;
        }
        if (openCarSegments.containsKey(vehicle)) repeatedVehicleEnters++;
        Production2040AccountingScopes.PersonScope personScope = index.persons().get(person);
        if (personScope == null) unmatchedPersons++;
        Production2040AccountingScopes.TripScope trip = index.trip(person, mainTripIndex);
        if (trip == null) unmatchedTrips++;
        openCarSegments.put(vehicle, new CarSegment(person, trip));
    }

    @Override
    public void movement(Id<Vehicle> vehicle, Id<Person> person, Id<Link> linkId,
            double metres, boolean transit, String ptMode) {
        if (transit) {
            Link link = network.getLinks().get(linkId);
            Production2040AnalysisSpec.require(link != null,
                    "Accounting movement refers to missing link " + linkId);
            LinkClip clip = clips.computeIfAbsent(linkId, ignored -> clip(link, boundary));
            MutablePtService metric = pt.computeIfAbsent(
                    ptMode == null ? "unknown" : ptMode, ignored -> new MutablePtService());
            metric.uncutMetres += metres;
            metric.territorialMetres += metres * clip.insideFraction();
            if (clip.category() == LinkLocation.CROSSING && Math.abs(metres) > 0) {
                metric.crossingLinks.add(linkId);
                metric.crossingServiceMetres += metres;
            }
            return;
        }
        CarSegment segment = openCarSegments.get(vehicle);
        if (segment == null) {
            if (!ignoredTrafficVehicles.contains(vehicle) && person != null
                    && Math.abs(metres) > 0) unattributedCarMovementEvents++;
            return;
        }
        segment.metres += metres;
    }

    @Override
    public void trafficLeave(Id<Vehicle> vehicle, Id<Person> person) {
        boolean ignored = ignoredTrafficVehicles.remove(vehicle);
        CarSegment segment = openCarSegments.remove(vehicle);
        if (segment == null) {
            if (!ignored) unmatchedVehicleLeaves++;
            return;
        }
        if (!segment.person.equals(person)) unmatchedVehicleLeaves++;
        if (segment.trip == null) return;
        Production2040AnalysisSpec.require(segment.metres >= -1e-6,
                "Negative attributed car segment distance for " + segment.trip.key());
        double metres = Math.max(0, segment.metres);
        endpointCarMetres.merge(segment.trip.endpointCategory(), metres, Double::sum);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            if (!segment.trip.included(scope)) continue;
            scopeCarMetres.merge(scope, metres, Double::sum);
            scopeCarVehicles.get(scope).add(vehicle);
            scopeCarTrips.get(scope).add(segment.trip.key());
        }
    }

    @Override
    public void personStuck(Id<Person> person, Integer mainTripIndex) {
        Production2040AccountingScopes.TripScope trip = index.trip(person, mainTripIndex);
        if (trip != null) stuckTrips.add(trip.key());
    }

    Result result() {
        Map<String, PtService> frozenPt = new TreeMap<>();
        pt.forEach((mode, metric) -> frozenPt.put(mode, metric.freeze(clips)));
        Set<Id<Link>> allCrossingLinks = new HashSet<>();
        pt.values().forEach(metric -> allCrossingLinks.addAll(metric.crossingLinks));
        double allCrossingModelMetres = allCrossingLinks.stream().map(clips::get)
                .mapToDouble(LinkClip::modelLinkMetres).sum();
        Map<Production2040AccountingScopes.Scope, CarScope> cars =
                new EnumMap<>(Production2040AccountingScopes.Scope.class);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            long stuck = stuckTrips.stream().map(index.trips()::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(trip -> "car".equals(trip.mainMode()) && trip.included(scope)).count();
            cars.put(scope, new CarScope(scopeCarMetres.get(scope),
                    scopeCarVehicles.get(scope).size(), scopeCarTrips.get(scope).size(), stuck));
        }
        return new Result(Map.copyOf(cars), Map.copyOf(endpointCarMetres), Map.copyOf(frozenPt),
                allCrossingLinks.size(), allCrossingModelMetres,
                unmatchedPersons, unmatchedTrips, repeatedVehicleEnters,
                unmatchedVehicleLeaves, unattributedCarMovementEvents,
                openCarSegments.size());
    }

    static LinkClip clip(Link link, MunichMunicipalBoundary boundary) {
        Coordinate from = new Coordinate(link.getFromNode().getCoord().getX(),
                link.getFromNode().getCoord().getY());
        Coordinate to = new Coordinate(link.getToNode().getCoord().getX(),
                link.getToNode().getCoord().getY());
        var line = GEOMETRY_FACTORY.createLineString(new Coordinate[]{from, to});
        double geometricLength = line.getLength();
        Production2040AnalysisSpec.require(Double.isFinite(geometricLength)
                        && geometricLength > 0,
                "Cannot territorially clip zero-length link geometry " + link.getId());
        double insideLength = line.intersection(boundary.geometry()).getLength();
        double fraction = Math.max(0.0, Math.min(1.0, insideLength / geometricLength));
        LinkLocation category = fraction <= FRACTION_EPSILON ? LinkLocation.OUTSIDE
                : fraction >= 1.0 - FRACTION_EPSILON ? LinkLocation.INSIDE
                : LinkLocation.CROSSING;
        return new LinkClip(fraction, category, link.getLength());
    }

    enum LinkLocation { INSIDE, OUTSIDE, CROSSING }
    record LinkClip(double insideFraction, LinkLocation category, double modelLinkMetres) { }
    record CarScope(double metres, long vehicles, long trips, long stuckTrips) { }
    record PtService(double uncutMetres, double territorialMetres, long crossingLinkCount,
                     double crossingLinkModelMetres, double crossingServiceMetres) { }
    record Result(Map<Production2040AccountingScopes.Scope, CarScope> carByScope,
                  Map<MunichTripBoundaryFilter.SpatialCategory, Double> carByEndpointCategory,
                  Map<String, PtService> ptByRouteMode,
                  long crossingLinkCount, double crossingLinkModelMetres,
                  long unmatchedPersons, long unmatchedTrips, long repeatedVehicleEnters,
                  long unmatchedVehicleLeaves, long unattributedCarMovementEvents,
                  long incompleteCarSegments) { }

    private static final class CarSegment {
        private final Id<Person> person;
        private final Production2040AccountingScopes.TripScope trip;
        private double metres;

        private CarSegment(Id<Person> person, Production2040AccountingScopes.TripScope trip) {
            this.person = person;
            this.trip = trip;
        }
    }

    private static final class MutablePtService {
        private double uncutMetres;
        private double territorialMetres;
        private double crossingServiceMetres;
        private final Set<Id<Link>> crossingLinks = new HashSet<>();

        private PtService freeze(Map<Id<Link>, LinkClip> clips) {
            double modelMetres = crossingLinks.stream()
                    .map(clips::get).mapToDouble(LinkClip::modelLinkMetres).sum();
            return new PtService(uncutMetres, territorialMetres, crossingLinks.size(),
                    modelMetres, crossingServiceMetres);
        }
    }
}
