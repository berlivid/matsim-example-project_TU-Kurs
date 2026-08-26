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
import org.matsim.core.population.PopulationUtils;

/** Records only Munich-resident stuck events by iteration and leg mode. */
@Singleton
public final class ResidentModeChoiceStuckEventListener
        implements PersonStuckEventHandler, AfterMobsimListener {
    private final Scenario scenario;
    private final Path target;
    private final List<IterationSnapshot> history = new ArrayList<>();
    private final List<StuckRecord> current = new ArrayList<>();
    private long cumulativeResidentEvents;

    @Inject
    public ResidentModeChoiceStuckEventListener(Scenario scenario, Config config) {
        this.scenario = scenario;
        target = Path.of(config.controller().getOutputDirectory()).resolve("analysis")
                .resolve("resident_stuck_events_by_iteration_and_mode.csv");
    }

    @Override
    public synchronized void reset(int iteration) {
        current.clear();
    }

    @Override
    public synchronized void handleEvent(PersonStuckEvent event) {
        Person person = scenario.getPopulation().getPersons().get(event.getPersonId());
        if (person == null || !ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                PopulationUtils.getSubpopulation(person))) return;
        current.add(new StuckRecord(event.getPersonId(), normalize(event.getLegMode())));
        cumulativeResidentEvents++;
    }

    @Override
    public synchronized void notifyAfterMobsim(AfterMobsimEvent event) {
        history.add(snapshot(event.getIteration(), current, cumulativeResidentEvents));
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, csv(history));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write resident stuck-event metrics", exception);
        }
    }

    synchronized long currentResidentEventCount() {
        return current.size();
    }

    static IterationSnapshot snapshot(int iteration, List<StuckRecord> records,
                                      long cumulativeEvents) {
        TreeMap<String, ModeSnapshot> modes = new TreeMap<>();
        Set<Id<Person>> persons = new HashSet<>();
        for (StuckRecord record : records) {
            persons.add(record.personId());
            modes.computeIfAbsent(record.mode(), ignored -> new ModeSnapshot())
                    .add(record.personId());
        }
        return new IterationSnapshot(iteration, records.size(), persons.size(),
                cumulativeEvents, List.copyOf(modes.entrySet().stream()
                .map(entry -> new ModeResult(entry.getKey(), entry.getValue().events,
                        entry.getValue().persons.size())).toList()));
    }

    static String csv(List<IterationSnapshot> snapshots) {
        StringBuilder out = new StringBuilder(
                "iteration,leg_mode,event_count,unique_resident_persons,resident_population_share_percent,cumulative_resident_events\n");
        snapshots.stream().sorted(java.util.Comparator.comparingInt(
                        IterationSnapshot::iteration))
                .forEach(snapshot -> {
                    row(out, snapshot, "all", snapshot.events(), snapshot.uniquePersons());
                    snapshot.modes().forEach(mode -> row(out, snapshot, mode.mode(),
                            mode.events(), mode.uniquePersons()));
                });
        return out.toString();
    }

    private static void row(StringBuilder out, IterationSnapshot snapshot, String mode,
                            long events, long persons) {
        out.append(snapshot.iteration()).append(',').append(mode).append(',')
                .append(events).append(',').append(persons).append(',')
                .append(String.format(Locale.ROOT, "%.9f",
                        100.0 * persons
                                / ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS))
                .append(',').append(snapshot.cumulativeEvents()).append('\n');
    }

    private static String normalize(String mode) {
        return mode == null || mode.isBlank() ? "unknown" : mode.toLowerCase(Locale.ROOT);
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

    record StuckRecord(Id<Person> personId, String mode) { }
    record IterationSnapshot(int iteration, long events, long uniquePersons,
                             long cumulativeEvents, List<ModeResult> modes) { }
    record ModeResult(String mode, long events, long uniquePersons) { }

    private static final class ModeSnapshot {
        private long events;
        private final Set<Id<Person>> persons = new HashSet<>();

        void add(Id<Person> person) {
            events++;
            persons.add(person);
        }
    }
}
