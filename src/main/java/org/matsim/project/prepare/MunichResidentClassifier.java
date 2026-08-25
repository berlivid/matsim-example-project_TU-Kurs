package org.matsim.project.prepare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/**
 * Read-only classification of persons by the location of exact {@code home}
 * main activities in their selected plan.
 */
public final class MunichResidentClassifier {
    public static final Set<String> HOME_ACTIVITY_TYPES = Set.of("home");

    private final MunichMunicipalBoundary boundary;

    public MunichResidentClassifier(MunichMunicipalBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary);
    }

    public Result classify(Person person) {
        if (person == null) {
            return unresolved(Classification.INVALID_SELECTED_PLAN,
                    "Person object is null", List.of(), List.of());
        }
        Plan plan = person.getSelectedPlan();
        if (plan == null || plan.getPlanElements() == null
                || plan.getPlanElements().isEmpty()) {
            return unresolved(Classification.INVALID_SELECTED_PLAN,
                    "Selected plan is missing or empty", List.of(), List.of());
        }

        try {
            TripStructureUtils.getTrips(plan, StageActivityTypeIdentifier::isStageActivity);
        } catch (RuntimeException exception) {
            return unresolved(Classification.INVALID_SELECTED_PLAN,
                    "Selected plan cannot be interpreted as MATSim main trips: "
                            + exception.getClass().getSimpleName(),
                    mainActivityTypes(plan), List.of());
        }

        List<Activity> homes = plan.getPlanElements().stream()
                .filter(Activity.class::isInstance)
                .map(Activity.class::cast)
                .filter(activity -> !isStageActivity(activity))
                .filter(activity -> HOME_ACTIVITY_TYPES.contains(activity.getType()))
                .toList();
        List<String> activityTypes = mainActivityTypes(plan);
        if (homes.isEmpty()) {
            return unresolved(Classification.NO_HOME_ACTIVITY,
                    "No exact home main activity in the selected plan",
                    activityTypes, List.of());
        }

        List<Coord> coordinates = homes.stream().map(Activity::getCoord).toList();
        if (coordinates.stream().anyMatch(coordinate ->
                !boundary.isValidCoordinate(coordinate))) {
            return unresolved(Classification.MISSING_HOME_COORDINATE,
                    "At least one exact home main activity has a missing or non-finite coordinate",
                    activityTypes, coordinates);
        }

        boolean firstInside = boundary.covers(coordinates.getFirst());
        boolean conflicting = coordinates.stream()
                .map(boundary::covers)
                .anyMatch(inside -> inside != firstInside);
        if (conflicting) {
            return unresolved(Classification.CONFLICTING_HOME_LOCATIONS,
                    "Exact home coordinates imply both inside and outside Munich",
                    activityTypes, coordinates);
        }

        Classification classification = firstInside
                ? Classification.MUNICH_RESIDENT
                : Classification.NON_MUNICH_RESIDENT;
        return new Result(classification, "All valid exact home coordinates have the same "
                + (firstInside ? "inside" : "outside") + " boundary result",
                activityTypes, List.copyOf(coordinates));
    }

    private static Result unresolved(Classification classification, String reason,
                                     List<String> activityTypes, List<Coord> coordinates) {
        return new Result(classification, reason, List.copyOf(activityTypes),
                coordinates);
    }

    private static List<String> mainActivityTypes(Plan plan) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (var element : plan.getPlanElements()) {
            if (element instanceof Activity activity
                    && !isStageActivity(activity)) {
                types.add(activity.getType() == null ? "<missing>" : activity.getType());
            }
        }
        return new ArrayList<>(types);
    }

    private static boolean isStageActivity(Activity activity) {
        return activity.getType() != null
                && StageActivityTypeIdentifier.isStageActivity(activity.getType());
    }

    public enum Classification {
        MUNICH_RESIDENT,
        NON_MUNICH_RESIDENT,
        NO_HOME_ACTIVITY,
        MISSING_HOME_COORDINATE,
        CONFLICTING_HOME_LOCATIONS,
        INVALID_SELECTED_PLAN;

        public boolean isUnresolved() {
            return this != MUNICH_RESIDENT && this != NON_MUNICH_RESIDENT;
        }
    }

    public record Result(Classification classification, String reason,
                         List<String> mainActivityTypes, List<Coord> homeCoordinates) {
        public Result {
            Objects.requireNonNull(classification);
            Objects.requireNonNull(reason);
            mainActivityTypes = List.copyOf(mainActivityTypes);
            homeCoordinates = Collections.unmodifiableList(new ArrayList<>(homeCoordinates));
        }

        public String activityTypesDiagnostic() {
            return String.join(";", mainActivityTypes);
        }

        public String homeCoordinatesDiagnostic() {
            if (homeCoordinates.isEmpty()) return "";
            List<String> values = new ArrayList<>();
            for (Coord coordinate : homeCoordinates) {
                values.add(coordinate == null ? "<missing>" : String.format(Locale.ROOT,
                        "%.3f|%.3f", coordinate.getX(), coordinate.getY()));
            }
            return String.join(";", values);
        }
    }
}
