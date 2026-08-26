package org.matsim.project.prepare;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

/** Reports Munich-resident stuck persons and their deterministically matched main trips. */
@Singleton
public final class ResidentModeChoiceStuckEventListener
        implements PersonDepartureEventHandler, PersonStuckEventHandler, AfterMobsimListener {
    static final double STUCK_TRIP_REVIEW_THRESHOLD_PERCENT = 1.0;

    private final ResidentStuckMainTripTracker tracker;
    private final Path target;
    private final List<IterationSnapshot> history = new ArrayList<>();
    private long cumulativeResidentEvents;

    @Inject
    public ResidentModeChoiceStuckEventListener(Scenario scenario, Config config) {
        tracker = new ResidentStuckMainTripTracker(scenario);
        target = Path.of(config.controller().getOutputDirectory()).resolve("analysis")
                .resolve("resident_stuck_events_by_iteration_and_mode.csv");
    }

    @Override
    public synchronized void reset(int iteration) {
        tracker.reset(iteration);
    }

    @Override
    public synchronized void handleEvent(PersonDepartureEvent event) {
        tracker.handleEvent(event);
    }

    @Override
    public synchronized void handleEvent(PersonStuckEvent event) {
        long before = tracker.snapshot().events();
        tracker.handleEvent(event);
        if (tracker.snapshot().events() > before) cumulativeResidentEvents++;
    }

    @Override
    public synchronized void notifyAfterMobsim(AfterMobsimEvent event) {
        history.add(snapshot(event.getIteration(), tracker.snapshot(), cumulativeResidentEvents));
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, csv(history));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write resident stuck-event metrics", exception);
        }
    }

    synchronized long currentResidentEventCount() {
        return tracker.snapshot().events();
    }

    static IterationSnapshot snapshot(int iteration,
                                      ResidentStuckMainTripTracker.Snapshot tracked,
                                      long cumulativeEvents) {
        return new IterationSnapshot(iteration, tracked.events(), tracked.uniquePersons(),
                tracked.affectedMainTripCount(), cumulativeEvents, tracked.modes());
    }

    static String csv(List<IterationSnapshot> snapshots) {
        List<IterationSnapshot> ordered = snapshots.stream()
                .sorted(Comparator.comparingInt(IterationSnapshot::iteration)).toList();
        Set<Integer> iterations = new TreeSet<>();
        for (IterationSnapshot snapshot : ordered) {
            if (!iterations.add(snapshot.iteration())) {
                throw new IllegalStateException(
                        "Duplicate resident stuck-event iteration: " + snapshot.iteration());
            }
        }
        IterationSnapshot baseline = ordered.stream()
                .filter(snapshot -> snapshot.iteration() == 0).findFirst().orElse(null);
        StringBuilder out = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (IterationSnapshot current : ordered) {
            row(out, current, baseline, "all", current.events(), current.uniquePersons(),
                    current.affectedMainTrips());
            TreeSet<String> modes = new TreeSet<>();
            current.modes().forEach(mode -> modes.add(mode.routingMode()));
            if (baseline != null) baseline.modes()
                    .forEach(mode -> modes.add(mode.routingMode()));
            for (String routingMode : modes) {
                ResidentStuckMainTripTracker.ModeResult mode = current.modes().stream()
                        .filter(candidate -> candidate.routingMode().equals(routingMode))
                        .findFirst().orElse(new ResidentStuckMainTripTracker.ModeResult(
                                routingMode, 0, 0, 0));
                ResidentStuckMainTripTracker.ModeResult baselineMode = baseline == null ? null
                        : baseline.modes().stream()
                        .filter(candidate -> candidate.routingMode().equals(routingMode))
                        .findFirst().orElse(null);
                row(out, current, baseline == null ? null : new IterationSnapshot(
                                0,
                                baselineMode == null ? 0 : baselineMode.events(),
                                baselineMode == null ? 0 : baselineMode.uniquePersons(),
                                baselineMode == null ? 0 : baselineMode.affectedMainTrips(),
                                0, List.of()),
                        routingMode, mode.events(), mode.uniquePersons(),
                        mode.affectedMainTrips());
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, IterationSnapshot current,
                            IterationSnapshot baseline, String mode, long events,
                            long persons, long trips) {
        long baselineEvents = baseline == null ? 0 : baseline.events();
        long baselinePersons = baseline == null ? 0 : baseline.uniquePersons();
        long baselineTrips = baseline == null ? 0 : baseline.affectedMainTrips();
        double personShare = 100.0 * persons
                / ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS;
        double tripShare = 100.0 * trips / ResidentStuckMainTripTracker.RESIDENT_MAIN_TRIPS;
        String status = 100.0 * current.affectedMainTrips()
                / ResidentStuckMainTripTracker.RESIDENT_MAIN_TRIPS
                > STUCK_TRIP_REVIEW_THRESHOLD_PERCENT ? "REVIEW_REQUIRED" : "PASS";
        out.append(current.iteration()).append(',').append(mode).append(',')
                .append(events).append(',').append(persons).append(',').append(trips)
                .append(',').append(number(personShare)).append(',').append(number(tripShare))
                .append(',').append(events - baselineEvents)
                .append(',').append(persons - baselinePersons)
                .append(',').append(trips - baselineTrips)
                .append(',').append(current.cumulativeEvents()).append(',')
                .append(status).append('\n');
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".resident-stuck-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record IterationSnapshot(int iteration, long events, long uniquePersons,
                             long affectedMainTrips, long cumulativeEvents,
                             List<ResidentStuckMainTripTracker.ModeResult> modes) { }
}
