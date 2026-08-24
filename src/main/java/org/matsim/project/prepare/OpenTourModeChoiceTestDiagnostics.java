package org.matsim.project.prepare;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.BasicLocation;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.algorithms.ChooseRandomLegModeForSubtour;
import org.matsim.core.replanning.modules.SubtourModeChoice;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ExperiencedPlansService;

/**
 * Aggregates the originally open-plan cohort during the isolated server test.
 * No person identifier is written. Chain-resource checks mirror MATSim's facility/link-location
 * sequence; an open plan ending away from its first location is reported, not treated as an error.
 */
@Singleton
public final class OpenTourModeChoiceTestDiagnostics
        implements AfterMobsimListener, ShutdownListener, PersonStuckEventHandler {
    static final long EXPECTED_OPEN_PERSONS = 107_618;
    static final long EXPECTED_OPEN_BOTH_INSIDE_TRIPS = 37_417;
    private static final double RAW_COORDINATE_TOLERANCE_METRES = 1e-6;
    private static final Set<String> ALLOWED_MODES = Set.of("car", "pt", "walk", "bike");
    private static final List<String> ORDERED_MODES = List.of("car", "pt", "walk", "bike");
    private static final List<String> CHAIN_MODES = List.of("car", "bike");
    private static final DefaultAnalysisMainModeIdentifier MAIN_MODE =
            new DefaultAnalysisMainModeIdentifier();

    private final ExperiencedPlansService experiencedPlans;
    private final MunichTripBoundaryFilter boundaryFilter;
    private final Map<Id<Person>, Baseline> openCohort;
    private final Path analysisDirectory;
    private final ChooseRandomLegModeForSubtour exactChoiceSet;
    private final List<Snapshot> snapshots = new ArrayList<>();
    private long stuckEvents;

    @Inject
    public OpenTourModeChoiceTestDiagnostics(Scenario scenario, Config config,
            ExperiencedPlansService experiencedPlans) {
        this.experiencedPlans = experiencedPlans;
        try {
            boundaryFilter = new MunichTripBoundaryFilter(MunichMunicipalBoundary.loadDefault());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load the unchanged Munich boundary", exception);
        }
        analysisDirectory = Path.of(config.controller().getOutputDirectory()).resolve("analysis");
        exactChoiceSet = new ChooseRandomLegModeForSubtour(
                TripStructureUtils.getRoutingModeIdentifier(),
                ignored -> ValidateModeChoiceCalibrationConfig.OFFERED_MODES,
                config.subtourModeChoice().getModes(),
                config.subtourModeChoice().getChainBasedModes(), new Random(0),
                SubtourModeChoice.Behavior.betweenAllAndFewerConstraints,
                config.subtourModeChoice().getProbaForRandomSingleTripMode(),
                config.subtourModeChoice().getCoordDistance());
        openCohort = baselineCohort(scenario);
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        stuckEvents++;
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        Map<Id<Person>, Plan> plans = experiencedPlans.getExperiencedPlans();
        Snapshot snapshot = inspect(event.getIteration(), plans);
        snapshots.add(snapshot);
        try {
            Files.createDirectories(analysisDirectory);
            writeAtomically(analysisDirectory.resolve("open_tour_iteration_diagnostic.csv"),
                    historyCsv(snapshots));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write open-tour test diagnostics", exception);
        }
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        try {
            Files.createDirectories(analysisDirectory);
            String csv = "last_iteration,unexpected_shutdown,stuck_events\n"
                    + event.getIteration() + "," + event.isUnexpected() + "," + stuckEvents + "\n";
            writeAtomically(analysisDirectory.resolve("open_tour_test_completion.csv"), csv);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write open-tour completion evidence", exception);
        }
    }

    Snapshot inspect(int iteration, Map<Id<Person>, Plan> plans) {
        long present = 0;
        long capable = 0;
        long notCapable = 0;
        long capabilityUnverifiable = 0;
        long capableTrips = 0;
        long capableBothInsideTrips = 0;
        long notCapableTrips = 0;
        long notCapableBothInsideTrips = 0;
        long mainTrips = 0;
        long bothInsideTrips = 0;
        long changedSignatures = 0;
        long unknownModes = 0;
        TreeMap<String, Long> tripsByMode = zeroModes();
        TreeMap<String, Long> insideByMode = zeroModes();
        TreeMap<String, Long> jumps = zeroChainModes();
        TreeMap<String, Long> endAway = zeroChainModes();
        TreeMap<String, Long> locationUnverifiable = zeroChainModes();

        for (var entry : openCohort.entrySet()) {
            Plan plan = plans.get(entry.getKey());
            if (plan == null) continue;
            present++;
            List<TripStructureUtils.Trip> trips = trips(plan);
            List<String> modes = trips.stream().map(OpenTourModeChoiceTestDiagnostics::mode).toList();
            if (!modes.equals(entry.getValue().modes())) changedSignatures++;
            long planBothInsideTrips = 0;
            for (int index = 0; index < trips.size(); index++) {
                TripStructureUtils.Trip trip = trips.get(index);
                String mode = modes.get(index);
                mainTrips++;
                tripsByMode.merge(mode, 1L, Long::sum);
                if (!ALLOWED_MODES.contains(mode)) unknownModes++;
                if (boundaryFilter.classify(trip.getOriginActivity(), trip.getDestinationActivity())
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) {
                    bothInsideTrips++;
                    planBothInsideTrips++;
                    insideByMode.merge(mode, 1L, Long::sum);
                }
            }
            try {
                if (exactChoiceSet.determineChoiceSet(plan).isEmpty()) {
                    notCapable++;
                    notCapableTrips += trips.size();
                    notCapableBothInsideTrips += planBothInsideTrips;
                } else {
                    capable++;
                    capableTrips += trips.size();
                    capableBothInsideTrips += planBothInsideTrips;
                }
            } catch (RuntimeException exception) {
                capabilityUnverifiable++;
            }
            for (String chainMode : CHAIN_MODES) {
                ChainAudit audit = auditChainMode(plan, chainMode);
                if (audit.resourceJumps() > 0) jumps.merge(chainMode, 1L, Long::sum);
                if (audit.endsAwayFromInitial()) endAway.merge(chainMode, 1L, Long::sum);
                if (!audit.fullyVerifiable()) locationUnverifiable.merge(chainMode, 1L, Long::sum);
            }
        }
        return new Snapshot(iteration, openCohort.size(), present, capable, notCapable,
                capabilityUnverifiable, capableTrips, capableBothInsideTrips,
                notCapableTrips, notCapableBothInsideTrips, mainTrips, bothInsideTrips,
                changedSignatures, unknownModes, stuckEvents,
                Map.copyOf(tripsByMode), Map.copyOf(insideByMode),
                Map.copyOf(jumps), Map.copyOf(endAway), Map.copyOf(locationUnverifiable),
                baselineTripsByMode(), baselineInsideByMode());
    }

    static ChainAudit auditChainMode(Plan plan, String chainMode) {
        List<TripStructureUtils.Trip> trips = trips(plan);
        if (trips.isEmpty()) return new ChainAudit(0, false, true, false);
        Object initial = location(trips.getFirst().getOriginActivity());
        Object resource = initial;
        int jumps = 0;
        boolean used = false;
        boolean verifiable = initial != null;
        for (TripStructureUtils.Trip trip : trips) {
            if (!chainMode.equals(mode(trip))) continue;
            used = true;
            Object origin = location(trip.getOriginActivity());
            Object destination = location(trip.getDestinationActivity());
            if (resource == null || origin == null || destination == null) {
                verifiable = false;
                resource = destination;
                continue;
            }
            if (!resource.equals(origin)) jumps++;
            resource = destination;
        }
        boolean endsAway = used && verifiable && !Objects.equals(initial, resource);
        return new ChainAudit(jumps, endsAway, verifiable, used);
    }

    private Map<Id<Person>, Baseline> baselineCohort(Scenario scenario) {
        LinkedHashMap<Id<Person>, Baseline> result = new LinkedHashMap<>();
        long inside = 0;
        for (Person person : scenario.getPopulation().getPersons().values()) {
            Plan plan = person.getSelectedPlan();
            if (plan == null || hasClosedSubtour(plan)) continue;
            List<TripStructureUtils.Trip> trips = trips(plan);
            if (trips.isEmpty()) continue;
            List<String> modes = trips.stream().map(OpenTourModeChoiceTestDiagnostics::mode).toList();
            if (!modes.stream().allMatch(ALLOWED_MODES::contains)) continue;
            int bothInside = 0;
            for (TripStructureUtils.Trip trip : trips) {
                if (boundaryFilter.classify(trip.getOriginActivity(), trip.getDestinationActivity())
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) bothInside++;
            }
            inside += bothInside;
            result.put(person.getId(), new Baseline(List.copyOf(modes), bothInside));
        }
        ValidateModeChoiceCalibrationConfig.require(result.size() == EXPECTED_OPEN_PERSONS,
                "Open-person baseline changed: " + result.size());
        ValidateModeChoiceCalibrationConfig.require(inside == EXPECTED_OPEN_BOTH_INSIDE_TRIPS,
                "Open-plan BOTH_INSIDE baseline changed: " + inside);
        return Map.copyOf(result);
    }

    private Map<String, Long> baselineTripsByMode() {
        TreeMap<String, Long> counts = zeroModes();
        openCohort.values().forEach(value -> value.modes().forEach(
                mode -> counts.merge(mode, 1L, Long::sum)));
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> baselineInsideByMode() {
        // The source population is monomodal per person, so all primary trips of a baseline
        // record use its sole mode. Fail closed if that established structure changes.
        TreeMap<String, Long> counts = zeroModes();
        for (Baseline baseline : openCohort.values()) {
            Set<String> distinct = new HashSet<>(baseline.modes());
            ValidateModeChoiceCalibrationConfig.require(distinct.size() == 1,
                    "Open-plan baseline is no longer monomodal");
            counts.merge(distinct.iterator().next(), (long) baseline.bothInsideTrips(), Long::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    static String historyCsv(List<Snapshot> snapshots) {
        StringBuilder csv = new StringBuilder("iteration,metric,mode,value,unit\n");
        Set<Integer> iterations = new HashSet<>();
        snapshots.stream().sorted(java.util.Comparator.comparingInt(Snapshot::iteration))
                .forEach(snapshot -> {
                    ValidateModeChoiceCalibrationConfig.require(iterations.add(snapshot.iteration()),
                            "Duplicate open-tour diagnostic iteration: " + snapshot.iteration());
                    scalar(csv, snapshot.iteration(), "original_open_persons", "all",
                            snapshot.originalOpenPersons(), "persons");
                    scalar(csv, snapshot.iteration(), "cohort_persons_present", "all",
                            snapshot.cohortPersonsPresent(), "persons");
                    scalar(csv, snapshot.iteration(), "mode_choice_capable_persons", "all",
                            snapshot.modeChoiceCapablePersons(), "persons");
                    scalar(csv, snapshot.iteration(), "still_not_mode_choice_capable_persons", "all",
                            snapshot.stillNotCapablePersons(), "persons");
                    scalar(csv, snapshot.iteration(), "capability_location_unverifiable", "all",
                            snapshot.capabilityUnverifiable(), "persons");
                    scalar(csv, snapshot.iteration(), "mode_choice_capable_main_trips", "all",
                            snapshot.modeChoiceCapableTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "mode_choice_capable_both_inside_main_trips", "all",
                            snapshot.modeChoiceCapableBothInsideTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "still_not_mode_choice_capable_main_trips", "all",
                            snapshot.stillNotCapableTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "still_not_mode_choice_capable_both_inside_main_trips", "all",
                            snapshot.stillNotCapableBothInsideTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "cohort_main_trips", "all",
                            snapshot.mainTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "cohort_both_inside_main_trips", "all",
                            snapshot.bothInsideTrips(), "trips");
                    scalar(csv, snapshot.iteration(), "persons_with_changed_mode_signature", "all",
                            snapshot.changedModeSignatures(), "persons");
                    scalar(csv, snapshot.iteration(), "unknown_main_modes", "unknown",
                            snapshot.unknownModes(), "trips");
                    scalar(csv, snapshot.iteration(), "stuck_events_cumulative", "all",
                            snapshot.stuckEvents(), "events");
                    for (String mode : ORDERED_MODES) {
                        scalar(csv, snapshot.iteration(), "baseline_main_trips", mode,
                                snapshot.baselineTripsByMode().getOrDefault(mode, 0L), "trips");
                        scalar(csv, snapshot.iteration(), "baseline_both_inside_main_trips", mode,
                                snapshot.baselineInsideByMode().getOrDefault(mode, 0L), "trips");
                        scalar(csv, snapshot.iteration(), "current_main_trips", mode,
                                snapshot.tripsByMode().getOrDefault(mode, 0L), "trips");
                        scalar(csv, snapshot.iteration(), "current_both_inside_main_trips", mode,
                                snapshot.insideByMode().getOrDefault(mode, 0L), "trips");
                    }
                    for (String mode : CHAIN_MODES) {
                        scalar(csv, snapshot.iteration(), "chain_resource_jump_persons", mode,
                                snapshot.chainJumps().getOrDefault(mode, 0L), "persons");
                        scalar(csv, snapshot.iteration(), "chain_end_away_from_initial_persons", mode,
                                snapshot.chainEndAway().getOrDefault(mode, 0L), "persons");
                        scalar(csv, snapshot.iteration(), "chain_location_unverifiable_persons", mode,
                                snapshot.chainUnverifiable().getOrDefault(mode, 0L), "persons");
                    }
                });
        return csv.toString();
    }

    private static void scalar(StringBuilder csv, int iteration, String metric, String mode,
                               long value, String unit) {
        csv.append(iteration).append(',').append(metric).append(',').append(mode).append(',')
                .append(value).append(',').append(unit).append('\n');
    }

    private static boolean hasClosedSubtour(Plan plan) {
        return TripStructureUtils.getSubtours(plan.getPlanElements(),
                        StageActivityTypeIdentifier::isStageActivity,
                        RAW_COORDINATE_TOLERANCE_METRES).stream()
                .anyMatch(TripStructureUtils.Subtour::isClosed);
    }

    private static List<TripStructureUtils.Trip> trips(Plan plan) {
        return TripStructureUtils.getTrips(plan, StageActivityTypeIdentifier::isStageActivity);
    }

    private static String mode(TripStructureUtils.Trip trip) {
        try {
            String result = MAIN_MODE.identifyMainMode(trip.getTripElements());
            return result == null ? "unknown" : result.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static Object location(Activity activity) {
        Id<? extends BasicLocation> location = activity.getFacilityId();
        return location != null ? location : activity.getLinkId();
    }

    private static TreeMap<String, Long> zeroModes() {
        TreeMap<String, Long> result = new TreeMap<>();
        ORDERED_MODES.forEach(mode -> result.put(mode, 0L));
        result.put("unknown", 0L);
        return result;
    }

    private static TreeMap<String, Long> zeroChainModes() {
        TreeMap<String, Long> result = new TreeMap<>();
        CHAIN_MODES.forEach(mode -> result.put(mode, 0L));
        return result;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record ChainAudit(int resourceJumps, boolean endsAwayFromInitial,
                      boolean fullyVerifiable, boolean modeUsed) { }

    record Snapshot(int iteration, long originalOpenPersons, long cohortPersonsPresent,
                    long modeChoiceCapablePersons, long stillNotCapablePersons,
                    long capabilityUnverifiable, long modeChoiceCapableTrips,
                    long modeChoiceCapableBothInsideTrips, long stillNotCapableTrips,
                    long stillNotCapableBothInsideTrips, long mainTrips, long bothInsideTrips,
                    long changedModeSignatures, long unknownModes, long stuckEvents,
                    Map<String, Long> tripsByMode, Map<String, Long> insideByMode,
                    Map<String, Long> chainJumps, Map<String, Long> chainEndAway,
                    Map<String, Long> chainUnverifiable, Map<String, Long> baselineTripsByMode,
                    Map<String, Long> baselineInsideByMode) { }

    private record Baseline(List<String> modes, int bothInsideTrips) { }
}
