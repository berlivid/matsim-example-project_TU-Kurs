package org.matsim.project.prepare;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/** Separates realized physical main mode from MATSim's requested routing mode. */
public final class ResidentTripModeClassifier {
    public static final String MISSING = "<missing>";
    public static final String INCONSISTENT = "<inconsistent>";
    public static final String UNKNOWN = "unknown";
    private static final DefaultAnalysisMainModeIdentifier PHYSICAL_IDENTIFIER =
            new DefaultAnalysisMainModeIdentifier();
    private static final MainModeIdentifier ROUTING_IDENTIFIER =
            TripStructureUtils.getRoutingModeIdentifier();

    private ResidentTripModeClassifier() { }

    public static Classification classify(TripStructureUtils.Trip trip) {
        List<Leg> legs = trip.getLegsOnly();
        List<String> legModes = legs.stream().map(leg -> normalize(leg.getMode())).toList();
        List<String> routingModes = legs.stream()
                .map(TripStructureUtils::getRoutingMode).map(ResidentTripModeClassifier::normalize)
                .toList();
        Routing routing = routing(trip, routingModes);
        List<String> stages = trip.getTripElements().stream()
                .filter(Activity.class::isInstance).map(Activity.class::cast)
                .filter(activity -> activity.getType() != null
                        && StageActivityTypeIdentifier.isStageActivity(activity.getType()))
                .map(Activity::getType).toList();
        return new Classification(physicalMainMode(trip), routing.mode(), routing.state(),
                List.copyOf(legModes), List.copyOf(routingModes), List.copyOf(stages),
                routeDistance(legs));
    }

    public static String physicalMainMode(TripStructureUtils.Trip trip) {
        try {
            String mode = PHYSICAL_IDENTIFIER.identifyMainMode(trip.getTripElements());
            return blank(mode) ? UNKNOWN : mode.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return UNKNOWN;
        }
    }

    private static Routing routing(TripStructureUtils.Trip trip,
                                   List<String> routingModes) {
        if (routingModes.isEmpty()) return new Routing(UNKNOWN, RoutingState.UNKNOWN);
        TreeSet<String> present = routingModes.stream()
                .filter(mode -> !MISSING.equals(mode)).collect(
                        java.util.stream.Collectors.toCollection(TreeSet::new));
        if (present.size() > 1) {
            return new Routing(INCONSISTENT, RoutingState.INCONSISTENT);
        }
        if (present.isEmpty() || routingModes.contains(MISSING)) {
            return new Routing(MISSING, RoutingState.MISSING);
        }
        String only = present.getFirst();
        try {
            String official = normalize(ROUTING_IDENTIFIER
                    .identifyMainMode(trip.getTripElements()));
            if (!only.equals(official)) {
                return new Routing(INCONSISTENT, RoutingState.INCONSISTENT);
            }
            return new Routing(official, RoutingState.CONSISTENT);
        } catch (RuntimeException exception) {
            return new Routing(INCONSISTENT, RoutingState.INCONSISTENT);
        }
    }

    private static double routeDistance(List<Leg> legs) {
        if (legs.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (Leg leg : legs) {
            Route route = leg.getRoute();
            if (route == null || !Double.isFinite(route.getDistance())
                    || route.getDistance() < 0.0) return Double.NaN;
            sum += route.getDistance();
        }
        return sum;
    }

    private static String normalize(String value) {
        return blank(value) ? MISSING : value.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum RoutingState {
        CONSISTENT,
        MISSING,
        INCONSISTENT,
        UNKNOWN
    }

    public record Classification(String physicalMode, String choiceMode,
                                 RoutingState routingState, List<String> legModes,
                                 List<String> routingModes, List<String> stageActivityTypes,
                                 double routeDistance) { }

    private record Routing(String mode, RoutingState state) { }
}
