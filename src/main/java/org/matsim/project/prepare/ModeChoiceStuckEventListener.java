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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

/** Records stuck events separately from selected-plan metrics without inferring their cause. */
@Singleton
public final class ModeChoiceStuckEventListener
        implements PersonStuckEventHandler, AfterMobsimListener {
    static final double END_WINDOW_SECONDS = 3_600.0;

    private final long populationSize;
    private final double qsimEndTime;
    private final Path target;
    private final List<IterationSnapshot> history = new ArrayList<>();
    private final Set<Id<Person>> cumulativePersons = new HashSet<>();
    private final List<StuckRecord> current = new ArrayList<>();
    private long cumulativeEvents;

    @Inject
    public ModeChoiceStuckEventListener(Scenario scenario, Config config) {
        populationSize = scenario.getPopulation().getPersons().size();
        ValidateModeChoiceCalibrationConfig.require(config.qsim().getEndTime().isDefined(),
                "Stuck-event analysis requires a finite qsim.endTime");
        qsimEndTime = config.qsim().getEndTime().seconds();
        ValidateModeChoiceCalibrationConfig.require(Double.isFinite(qsimEndTime),
                "Stuck-event analysis requires a finite qsim.endTime");
        target = Path.of(config.controller().getOutputDirectory()).resolve("analysis")
                .resolve("stuck_events_iteration_metrics.csv");
    }

    @Override
    public synchronized void reset(int iteration) {
        current.clear();
    }

    @Override
    public synchronized void handleEvent(PersonStuckEvent event) {
        current.add(new StuckRecord(event.getPersonId(), normalize(event.getLegMode()),
                event.getLinkId() == null ? "" : event.getLinkId().toString(), event.getTime()));
        cumulativeEvents++;
        cumulativePersons.add(event.getPersonId());
    }

    synchronized long currentEventCount() { return current.size(); }
    synchronized long cumulativeEventCount() { return cumulativeEvents; }
    synchronized long cumulativeUniquePersonCount() { return cumulativePersons.size(); }

    @Override
    public synchronized void notifyAfterMobsim(AfterMobsimEvent event) {
        history.add(snapshot(event.getIteration(), current, cumulativeEvents,
                cumulativePersons.size(), populationSize, qsimEndTime));
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, csv(history));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write stuck-event metrics", exception);
        }
    }

    static IterationSnapshot snapshot(int iteration, List<StuckRecord> records,
                                      long cumulativeEvents, long cumulativeUniquePersons,
                                      long populationSize, double qsimEndTime) {
        Set<Id<Person>> persons = new HashSet<>();
        TreeMap<String, ModeSnapshot> modes = new TreeMap<>();
        for (StuckRecord record : records) {
            persons.add(record.personId());
            String window = timeWindow(record.time(), qsimEndTime);
            modes.computeIfAbsent(record.mode() + "\u0000" + window,
                    ignored -> new ModeSnapshot(record.mode(), window))
                    .add(record);
        }
        return new IterationSnapshot(iteration, records.size(), persons.size(),
                cumulativeEvents, cumulativeUniquePersons, populationSize, qsimEndTime,
                List.copyOf(modes.values()));
    }

    static String csv(List<IterationSnapshot> snapshots) {
        StringBuilder csv = new StringBuilder("iteration,leg_mode,time_window,event_count,"
                + "unique_persons,population_share_percent,min_event_time_seconds,"
                + "max_event_time_seconds,cumulative_event_count,cumulative_unique_persons,"
                + "qsim_end_time_seconds\n");
        Set<Integer> iterations = new HashSet<>();
        snapshots.stream().sorted(java.util.Comparator.comparingInt(IterationSnapshot::iteration))
                .forEach(snapshot -> {
                    ValidateModeChoiceCalibrationConfig.require(
                            iterations.add(snapshot.iteration()),
                            "Duplicate stuck-event iteration: " + snapshot.iteration());
                    if (snapshot.modes().isEmpty()) {
                        row(csv, snapshot, "all", "NO_EVENTS", 0, 0,
                                Double.NaN, Double.NaN);
                    } else {
                        row(csv, snapshot, "all", "ALL_WINDOWS", snapshot.events(),
                                snapshot.uniquePersons(),
                                snapshot.modes().stream().mapToDouble(mode -> mode.minTime).min()
                                        .orElse(Double.NaN),
                                snapshot.modes().stream().mapToDouble(mode -> mode.maxTime).max()
                                        .orElse(Double.NaN));
                        snapshot.modes().forEach(mode -> row(csv, snapshot, mode.mode,
                                mode.window, mode.events, mode.persons.size(), mode.minTime,
                                mode.maxTime));
                    }
                });
        return csv.toString();
    }

    private static void row(StringBuilder csv, IterationSnapshot snapshot, String mode,
                            String window, long events, long persons, double min, double max) {
        double share = snapshot.populationSize() == 0 ? Double.NaN
                : 100.0 * persons / snapshot.populationSize();
        csv.append(snapshot.iteration()).append(',').append(mode).append(',').append(window)
                .append(',').append(events).append(',').append(persons).append(',')
                .append(number(share)).append(',').append(number(min)).append(',')
                .append(number(max)).append(',').append(snapshot.cumulativeEvents()).append(',')
                .append(snapshot.cumulativeUniquePersons()).append(',')
                .append(number(snapshot.qsimEndTime())).append('\n');
    }

    static String timeWindow(double time, double end) {
        if (time > end) return "AFTER_QSIM_END";
        if (time >= end - END_WINDOW_SECONDS) return "FINAL_HOUR_BEFORE_OR_AT_QSIM_END";
        return "EARLIER";
    }

    private static String normalize(String mode) {
        return mode == null || mode.isBlank() ? "unknown" : mode;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "";
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".stuck-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record StuckRecord(Id<Person> personId, String mode, String linkId, double time) { }
    record IterationSnapshot(int iteration, long events, long uniquePersons,
                             long cumulativeEvents, long cumulativeUniquePersons,
                             long populationSize, double qsimEndTime,
                             List<ModeSnapshot> modes) { }

    static final class ModeSnapshot {
        final String mode;
        final String window;
        long events;
        final Set<Id<Person>> persons = new HashSet<>();
        double minTime = Double.POSITIVE_INFINITY;
        double maxTime = Double.NEGATIVE_INFINITY;

        ModeSnapshot(String mode, String window) {
            this.mode = mode;
            this.window = window;
        }

        void add(StuckRecord record) {
            events++;
            persons.add(record.personId());
            minTime = Math.min(minTime, record.time());
            maxTime = Math.max(maxTime, record.time());
        }
    }
}
