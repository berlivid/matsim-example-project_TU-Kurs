package org.matsim.project.prepare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.config.Config;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

/**
 * Read-only calibration metrics for selected/routed or externally loaded MATSim plans.
 * Main-mode metrics and physical-stage passenger-kilometres are deliberately separate.
 */
public final class ModeChoiceCalibrationAnalysis {
    public static final double POPULATION_SCALE_FACTOR = 20.0;
    public static final String PRIMARY_SPATIAL_DEFINITION =
            "both_main_activity_endpoints_inside_or_on_munich";
    public static final String MAIN_TRIP_DEFINITION =
            "main_trip_between_consecutive_main_activities";
    private static final Set<String> MAIN_MODES = Set.of("car", "pt", "walk", "bike");
    private static final Set<String> PT_SUBMODES = Set.of(
            "bus", "tram", "subway", "rail", "ferry");
    private static final double MAX_PLAUSIBLE_LEG_DISTANCE_METRES = 2_000_000.0;
    private static final double RAW_PLAN_COORDINATE_TOLERANCE_METRES = 1e-6;
    private final Network network;
    private final TransitSchedule schedule;
    private final Config config;
    private final MunichTripBoundaryFilter boundaryFilter;

    public ModeChoiceCalibrationAnalysis(Scenario scenario,
                                         MunichMunicipalBoundary boundary) {
        this(scenario.getNetwork(), scenario.getTransitSchedule(), scenario.getConfig(), boundary);
    }

    ModeChoiceCalibrationAnalysis(Network network, TransitSchedule schedule, Config config,
                                  MunichMunicipalBoundary boundary) {
        this.network = java.util.Objects.requireNonNull(network);
        this.schedule = java.util.Objects.requireNonNull(schedule);
        this.config = java.util.Objects.requireNonNull(config);
        this.boundaryFilter = new MunichTripBoundaryFilter(boundary);
    }

    public AnalysisResult analyze(int iteration, Map<Id<Person>, Plan> plans) {
        Map<GroupKey, MutableMetrics> metrics = new HashMap<>();
        long plansWithClosedSubtour = 0;
        long plansWithoutClosedSubtour = 0;
        TreeSet<String> unknownMainModes = new TreeSet<>();

        List<Map.Entry<Id<Person>, Plan>> ordered = plans.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        for (var entry : ordered) {
            Plan plan = entry.getValue();
            if (plan == null) continue;
            boolean capable = hasClosedSubtour(plan);
            if (capable) plansWithClosedSubtour++; else plansWithoutClosedSubtour++;
            PlanEligibility eligibility = capable
                    ? PlanEligibility.MODE_CHOICE_CAPABLE
                    : PlanEligibility.NOT_MODE_CHOICE_CAPABLE;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(
                    plan, StageActivityTypeIdentifier::isStageActivity);
            for (TripStructureUtils.Trip trip : trips) {
                MunichTripBoundaryFilter.SpatialCategory category = boundaryFilter.classify(
                        trip.getOriginActivity(), trip.getDestinationActivity());
                ResidentTripModeClassifier.Classification classification =
                        ResidentTripModeClassifier.classify(trip);
                String rawMainMode = classification.physicalMode();
                String mainMode = MAIN_MODES.contains(rawMainMode) ? rawMainMode : "unknown";
                String rawChoiceMode = classification.routingState()
                        == ResidentTripModeClassifier.RoutingState.CONSISTENT
                        ? classification.choiceMode() : "unknown";
                String choiceMode = MAIN_MODES.contains(rawChoiceMode)
                        ? rawChoiceMode : "unknown";
                if ("unknown".equals(mainMode)) unknownMainModes.add(rawMainMode);
                TripMeasurement measurement = measureTrip(trip, mainMode);
                for (SpatialScope scope : scopes(category)) {
                    add(metrics, new GroupKey(scope, PlanEligibility.ALL_PLANS), entry.getKey(),
                            mainMode, choiceMode, measurement);
                    add(metrics, new GroupKey(scope, eligibility), entry.getKey(),
                            mainMode, choiceMode, measurement);
                }
            }
        }

        TreeMap<GroupKey, MetricSnapshot> snapshots = new TreeMap<>();
        metrics.forEach((key, value) -> snapshots.put(key, value.snapshot()));
        return new AnalysisResult(iteration, Collections.unmodifiableMap(snapshots),
                plansWithClosedSubtour, plansWithoutClosedSubtour,
                Collections.unmodifiableSet(unknownMainModes));
    }

    private static void add(Map<GroupKey, MutableMetrics> metrics, GroupKey key,
                            Id<Person> personId, String mainMode, String choiceMode,
                            TripMeasurement measurement) {
        metrics.computeIfAbsent(key, ignored -> new MutableMetrics())
                .add(personId, mainMode, choiceMode, measurement);
    }

    private TripMeasurement measureTrip(TripStructureUtils.Trip trip, String mainMode) {
        List<LegMeasurement> stages = new ArrayList<>();
        boolean allValid = true;
        double mainDistanceMetres = 0.0;
        List<? extends PlanElement> elements = trip.getTripElements();
        for (int index = 0; index < elements.size(); index++) {
            if (!(elements.get(index) instanceof Leg leg)) continue;
            Activity from = previousActivity(elements, index, trip.getOriginActivity());
            Activity to = nextActivity(elements, index, trip.getDestinationActivity());
            String physicalMode = physicalMode(leg);
            DistanceMeasurement distance = measureLeg(leg, from, to, physicalMode);
            stages.add(new LegMeasurement(physicalMode, distance));
            if (distance.valid()) mainDistanceMetres += distance.metres();
            else allValid = false;
        }
        return new TripMeasurement(allValid ? mainDistanceMetres : Double.NaN,
                List.copyOf(stages));
    }

    private DistanceMeasurement measureLeg(Leg leg, Activity from, Activity to,
                                           String physicalMode) {
        Route route = leg.getRoute();
        if (route != null && plausible(route.getDistance())) {
            return new DistanceMeasurement(route.getDistance(), DistanceSource.ROUTE_REPORTED);
        }
        if (route instanceof NetworkRoute networkRoute) {
            double metres = networkDistance(networkRoute);
            if (plausible(metres)) {
                return new DistanceMeasurement(metres, DistanceSource.NETWORK_LINK_SUM);
            }
        }
        if (route instanceof TransitPassengerRoute passengerRoute) {
            double metres = transitDistance(passengerRoute);
            if (plausible(metres)) {
                return new DistanceMeasurement(metres, DistanceSource.TRANSIT_ROUTE_PATH);
            }
        }
        double fallback = beelineFallback(from, to, leg.getMode(), physicalMode);
        if (plausible(fallback)) {
            return new DistanceMeasurement(fallback, DistanceSource.BEELINE_FALLBACK);
        }
        return new DistanceMeasurement(Double.NaN, DistanceSource.INVALID_OR_MISSING);
    }

    private double networkDistance(NetworkRoute route) {
        List<Id<Link>> ids = new ArrayList<>();
        if (route.getStartLinkId() != null) ids.add(route.getStartLinkId());
        ids.addAll(route.getLinkIds());
        if (route.getEndLinkId() != null
                && (ids.isEmpty() || !route.getEndLinkId().equals(ids.getLast()))) {
            ids.add(route.getEndLinkId());
        }
        if (ids.isEmpty()) return Double.NaN;
        double distance = 0.0;
        for (Id<Link> id : ids) {
            Link link = network.getLinks().get(id);
            if (link == null || !plausible(link.getLength())) return Double.NaN;
            distance += link.getLength();
        }
        return distance;
    }

    private double transitDistance(TransitPassengerRoute route) {
        try {
            return RouteUtils.calcDistance(route, schedule, network);
        } catch (RuntimeException exception) {
            return Double.NaN;
        }
    }

    private double beelineFallback(Activity from, Activity to, String legMode,
                                   String physicalMode) {
        Coord origin = from == null ? null : from.getCoord();
        Coord destination = to == null ? null : to.getCoord();
        if (!finite(origin) || !finite(destination)) return Double.NaN;
        double straight = Math.hypot(destination.getX() - origin.getX(),
                destination.getY() - origin.getY());
        String routingMode = config.routing().getBeelineDistanceFactors().containsKey(legMode)
                ? legMode : physicalMode;
        double factor = config.routing().getBeelineDistanceFactors()
                .getOrDefault(routingMode, 1.0);
        return straight * factor;
    }

    private String physicalMode(Leg leg) {
        if (leg.getRoute() instanceof TransitPassengerRoute route) {
            return transitSubmode(route);
        }
        String mode = leg.getMode() == null ? "" : leg.getMode().toLowerCase(Locale.ROOT);
        if (mode.equals("car")) return "car";
        if (mode.equals("bike") || mode.contains("bike")) return "bike";
        if (mode.equals("walk") || mode.contains("walk")) return "walk";
        if (mode.equals("pt")) return "unknown_pt";
        return "unknown_stage";
    }

    private String transitSubmode(TransitPassengerRoute passengerRoute) {
        TransitLine line = schedule.getTransitLines().get(passengerRoute.getLineId());
        TransitRoute route = line == null ? null : line.getRoutes().get(passengerRoute.getRouteId());
        if (route == null || route.getTransportMode() == null) return "unknown_pt";
        String mode = route.getTransportMode().toLowerCase(Locale.ROOT);
        return PT_SUBMODES.contains(mode) ? mode : "unknown_pt";
    }

    private boolean hasClosedSubtour(Plan plan) {
        try {
            return TripStructureUtils.getSubtours(plan.getPlanElements(),
                            StageActivityTypeIdentifier::isStageActivity,
                            config.subtourModeChoice().getCoordDistance()).stream()
                    .anyMatch(TripStructureUtils.Subtour::isClosed);
        } catch (NullPointerException exception) {
            // Read-only postprocessing may encounter plans with coordinates but no link IDs.
            return TripStructureUtils.getSubtours(plan.getPlanElements(),
                            StageActivityTypeIdentifier::isStageActivity,
                            RAW_PLAN_COORDINATE_TOLERANCE_METRES).stream()
                    .anyMatch(TripStructureUtils.Subtour::isClosed);
        }
    }

    private static Activity previousActivity(List<? extends PlanElement> elements, int legIndex,
                                             Activity fallback) {
        for (int i = legIndex - 1; i >= 0; i--) {
            if (elements.get(i) instanceof Activity activity) return activity;
        }
        return fallback;
    }

    private static Activity nextActivity(List<? extends PlanElement> elements, int legIndex,
                                         Activity fallback) {
        for (int i = legIndex + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof Activity activity) return activity;
        }
        return fallback;
    }

    private static Collection<SpatialScope> scopes(
            MunichTripBoundaryFilter.SpatialCategory category) {
        LinkedHashSet<SpatialScope> scopes = new LinkedHashSet<>();
        scopes.add(SpatialScope.ALL_TRIPS);
        switch (category) {
            case BOTH_INSIDE -> scopes.add(SpatialScope.BOTH_INSIDE);
            case ORIGIN_ONLY -> {
                scopes.add(SpatialScope.ORIGIN_ONLY);
                scopes.add(SpatialScope.BOUNDARY_CROSSING);
            }
            case DESTINATION_ONLY -> {
                scopes.add(SpatialScope.DESTINATION_ONLY);
                scopes.add(SpatialScope.BOUNDARY_CROSSING);
            }
            case BOTH_OUTSIDE -> scopes.add(SpatialScope.BOTH_OUTSIDE);
            case INVALID_OR_MISSING_COORDINATE -> {
                scopes.add(SpatialScope.INVALID_OR_MISSING_COORDINATE);
                scopes.add(SpatialScope.INVALID_COORDINATE);
            }
        }
        return scopes;
    }

    private static boolean finite(Coord coord) {
        return coord != null && Double.isFinite(coord.getX()) && Double.isFinite(coord.getY());
    }

    private static boolean plausible(double distance) {
        return Double.isFinite(distance) && distance >= 0.0
                && distance <= MAX_PLAUSIBLE_LEG_DISTANCE_METRES;
    }

    public enum SpatialScope {
        ALL_TRIPS,
        BOTH_INSIDE,
        ORIGIN_ONLY,
        DESTINATION_ONLY,
        BOUNDARY_CROSSING,
        BOTH_OUTSIDE,
        INVALID_OR_MISSING_COORDINATE,
        INVALID_COORDINATE
    }

    public enum PlanEligibility {
        ALL_PLANS,
        MODE_CHOICE_CAPABLE,
        NOT_MODE_CHOICE_CAPABLE
    }

    public enum DistanceSource {
        ROUTE_REPORTED,
        NETWORK_LINK_SUM,
        TRANSIT_ROUTE_PATH,
        BEELINE_FALLBACK,
        INVALID_OR_MISSING
    }

    public record GroupKey(SpatialScope spatialScope, PlanEligibility planEligibility)
            implements Comparable<GroupKey> {
        @Override
        public int compareTo(GroupKey other) {
            int scope = spatialScope.compareTo(other.spatialScope);
            return scope != 0 ? scope : planEligibility.compareTo(other.planEligibility);
        }
    }

    public record MetricSnapshot(long validPersons, long mainTrips,
                                 Map<String, Long> mainTripsByMode,
                                 Map<String, Long> choiceMainTripsByMode,
                                 Map<PhysicalChoiceTransition, Long> physicalChoiceTransitions,
                                 long ptRequestsWithWalkOnlyPhysicalRoute,
                                 Map<String, Long> validDistanceTripsByMode,
                                 Map<String, Double> mainModePassengerMetresByMode,
                                 Map<String, Double> physicalPassengerMetresByMode,
                                 Map<DistanceSource, Long> distanceSources,
                                 long invalidStageDistances,
                                 long invalidMainTripDistances) {
        public double modalSharePercent(String mode) {
            return mainTrips == 0 ? Double.NaN
                    : 100.0 * mainTripsByMode.getOrDefault(mode, 0L) / mainTrips;
        }

        public double choiceModalSharePercent(String mode) {
            return mainTrips == 0 ? Double.NaN
                    : 100.0 * choiceMainTripsByMode.getOrDefault(mode, 0L) / mainTrips;
        }

        public double mainModePkm(String mode) {
            return mainModePassengerMetresByMode.getOrDefault(mode, 0.0) / 1_000.0;
        }

        public double physicalStagePkm(String mode) {
            return physicalPassengerMetresByMode.getOrDefault(mode, 0.0) / 1_000.0;
        }

        public double meanTripLengthKm(String mode) {
            long trips = validDistanceTripsByMode.getOrDefault(mode, 0L);
            return trips == 0 ? Double.NaN : mainModePkm(mode) / trips;
        }

        public double totalMainModePkm() {
            return mainModePassengerMetresByMode.values().stream()
                    .mapToDouble(Double::doubleValue).sum() / 1_000.0;
        }
    }

    public record PhysicalChoiceTransition(String physicalMode, String choiceMode)
            implements Comparable<PhysicalChoiceTransition> {
        @Override
        public int compareTo(PhysicalChoiceTransition other) {
            int physical = physicalMode.compareTo(other.physicalMode);
            return physical != 0 ? physical : choiceMode.compareTo(other.choiceMode);
        }
    }

    public record AnalysisResult(int iteration, Map<GroupKey, MetricSnapshot> groups,
                                 long plansWithClosedSubtour,
                                 long plansWithoutClosedSubtour,
                                 Set<String> unknownMainModes) {
        public MetricSnapshot metrics(SpatialScope scope, PlanEligibility eligibility) {
            return groups.getOrDefault(new GroupKey(scope, eligibility), emptySnapshot());
        }

        private static MetricSnapshot emptySnapshot() {
            return new MetricSnapshot(0, 0, Map.of(), Map.of(), Map.of(), 0,
                    Map.of(), Map.of(), Map.of(), Map.of(), 0, 0);
        }
    }

    private record DistanceMeasurement(double metres, DistanceSource source) {
        boolean valid() { return source != DistanceSource.INVALID_OR_MISSING; }
    }

    private record LegMeasurement(String physicalMode, DistanceMeasurement distance) { }

    private record TripMeasurement(double mainDistanceMetres, List<LegMeasurement> stages) { }

    private static final class MutableMetrics {
        final Set<Id<Person>> persons = new TreeSet<>();
        final TreeMap<String, Long> mainTrips = new TreeMap<>();
        final TreeMap<String, Long> choiceMainTrips = new TreeMap<>();
        final TreeMap<PhysicalChoiceTransition, Long> physicalChoiceTransitions =
                new TreeMap<>();
        final TreeMap<String, Long> validDistanceTrips = new TreeMap<>();
        final TreeMap<String, Double> mainMetres = new TreeMap<>();
        final TreeMap<String, Double> physicalMetres = new TreeMap<>();
        final EnumMap<DistanceSource, Long> sources = new EnumMap<>(DistanceSource.class);
        long trips;
        long invalidStages;
        long invalidMainTrips;
        long ptRequestsWithWalkOnlyPhysicalRoute;

        void add(Id<Person> personId, String mainMode, String choiceMode,
                 TripMeasurement measurement) {
            persons.add(personId);
            trips++;
            mainTrips.merge(mainMode, 1L, Long::sum);
            choiceMainTrips.merge(choiceMode, 1L, Long::sum);
            physicalChoiceTransitions.merge(
                    new PhysicalChoiceTransition(mainMode, choiceMode), 1L, Long::sum);
            if ("walk".equals(mainMode) && "pt".equals(choiceMode)
                    && measurement.stages().stream()
                    .allMatch(stage -> "walk".equals(stage.physicalMode()))) {
                ptRequestsWithWalkOnlyPhysicalRoute++;
            }
            if (Double.isFinite(measurement.mainDistanceMetres())) {
                validDistanceTrips.merge(mainMode, 1L, Long::sum);
                mainMetres.merge(mainMode, measurement.mainDistanceMetres(), Double::sum);
            } else {
                invalidMainTrips++;
            }
            for (LegMeasurement stage : measurement.stages()) {
                sources.merge(stage.distance().source(), 1L, Long::sum);
                if (stage.distance().valid()) {
                    physicalMetres.merge(stage.physicalMode(), stage.distance().metres(), Double::sum);
                } else {
                    invalidStages++;
                }
            }
        }

        MetricSnapshot snapshot() {
            return new MetricSnapshot(persons.size(), trips, Map.copyOf(mainTrips),
                    Map.copyOf(choiceMainTrips), Map.copyOf(physicalChoiceTransitions),
                    ptRequestsWithWalkOnlyPhysicalRoute,
                    Map.copyOf(validDistanceTrips), Map.copyOf(mainMetres),
                    Map.copyOf(physicalMetres), Map.copyOf(sources),
                    invalidStages, invalidMainTrips);
        }
    }
}
