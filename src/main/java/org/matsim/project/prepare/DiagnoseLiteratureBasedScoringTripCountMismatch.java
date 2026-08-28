package org.matsim.project.prepare;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only person-level diagnosis of the 257-trip output mismatch. */
public final class DiagnoseLiteratureBasedScoringTripCountMismatch {
    static final Path OUTPUT = ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT;
    static final Path DIAGNOSTIC = OUTPUT.resolve("trip-count-diagnostic");
    static final String RUN_ID = ValidateLiteratureBasedScoringDiagnosticConfig.RUN_ID;
    static final long EXPECTED_INPUT_TRIPS = 540_468;
    static final long OBSERVED_FINAL_TRIPS = 540_211;
    static final long EXPECTED_DIFFERENCE = 257;

    private DiagnoseLiteratureBasedScoringTripCountMismatch() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "Run 07A accepts no arguments");
        require(Files.isDirectory(OUTPUT), "Missing fixed diagnostic output: " + OUTPUT);
        require(!Files.exists(DIAGNOSTIC),
                "Trip-count diagnostic already exists and will not be overwritten: "
                        + DIAGNOSTIC);

        Config config = ValidateLiteratureBasedScoringDiagnosticConfig
                .loadAndValidate(false);
        Path outputPlans = required(OUTPUT.resolve(RUN_ID + ".output_plans.xml.gz"),
                "final output plans");
        Path events = OUTPUT.resolve(RUN_ID + ".output_events.xml.gz");
        if (!Files.isRegularFile(events)) {
            events = OUTPUT.resolve("ITERS/it.10/" + RUN_ID + ".10.events.xml.gz");
        }
        events = required(events, "final events");

        Map<String, StuckInfo> stuck = readStuckEvents(events);
        Map<String, Integer> inputCounts = readInputCounts(
                config.plans().getInputFileURL(config.getContext()));
        Comparison comparison = compareOutput(outputPlans, inputCounts, stuck);
        require(comparison.inputTrips() == EXPECTED_INPUT_TRIPS,
                "Unexpected input main-trip count: expected " + EXPECTED_INPUT_TRIPS
                        + ", actual " + comparison.inputTrips());

        publish(comparison);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING TRIP-COUNT DIAGNOSTIC COMPLETE%n"
                        + "inputTrips=%d outputPlanTrips=%d missing=%d additional=%d%n"
                        + "affectedPersons=%d status=%s%noutput=%s%n",
                comparison.inputTrips(), comparison.outputTrips(),
                comparison.missingTrips(), comparison.additionalTrips(),
                comparison.affected().size(), comparison.status(), DIAGNOSTIC);
    }

    static Map<String, Integer> readInputCounts(URL population) {
        require(population != null, "Diagnostic config has no input population URL");
        Map<String, Integer> counts = new HashMap<>(400_000);
        streamPopulation(population, person -> {
            Plan plan = person.getSelectedPlan();
            require(plan != null, "Input person has no selected plan: " + person.getId());
            Integer previous = counts.put(person.getId().toString(), mainTripCount(plan));
            require(previous == null, "Duplicate input person ID: " + person.getId());
        });
        return counts;
    }

    static Comparison compareOutput(Path outputPlans, Map<String, Integer> inputCounts,
            Map<String, StuckInfo> stuck) {
        long inputTrips = inputCounts.values().stream().mapToLong(Integer::longValue).sum();
        int inputPersons = inputCounts.size();
        List<PersonDifference> affected = new ArrayList<>();
        Set<String> outputIds = new HashSet<>(400_000);
        long[] outputTrips = {0};

        streamPopulation(outputPlans.toString(), person -> {
            String id = person.getId().toString();
            require(outputIds.add(id), "Duplicate output person ID: " + id);
            PlanInfo output = planInfo(person.getSelectedPlan());
            outputTrips[0] += output.mainTrips();
            Integer input = inputCounts.remove(id);
            PersonDifference difference = comparePerson(id, input == null ? 0 : input,
                    output, stuck.get(id), input != null, true);
            if (input == null || difference.missingTrips() != 0
                    || !difference.outputPlanComplete()) {
                affected.add(difference);
            }
        });

        for (var missingPerson : inputCounts.entrySet()) {
            affected.add(comparePerson(missingPerson.getKey(), missingPerson.getValue(),
                    PlanInfo.MISSING, stuck.get(missingPerson.getKey()), true, false));
        }
        affected.sort(Comparator.comparing(PersonDifference::personId));

        long missing = affected.stream().mapToLong(value ->
                Math.max(0, value.missingTrips())).sum();
        long additional = affected.stream().mapToLong(value ->
                Math.max(0, -value.missingTrips())).sum();
        long stuckMissing = affected.stream().filter(PersonDifference::stuck)
                .mapToLong(value -> Math.max(0, value.missingTrips())).sum();
        long nonStuckMissing = missing - stuckMissing;
        long incompleteMissing = affected.stream().filter(value ->
                        !value.outputPlanComplete())
                .mapToLong(value -> Math.max(0, value.missingTrips())).sum();
        Status status = determineStatus(missing, additional, stuckMissing,
                nonStuckMissing, incompleteMissing);
        return new Comparison(inputPersons, outputIds.size(), inputTrips, outputTrips[0],
                missing, additional, stuckMissing, nonStuckMissing, incompleteMissing,
                List.copyOf(affected), status);
    }

    static PersonDifference comparePerson(String personId, int inputTrips,
            PlanInfo output, StuckInfo stuck, boolean inputPresent, boolean outputPresent) {
        return new PersonDifference(personId, inputPresent, outputPresent, inputTrips,
                output.mainTrips(), inputTrips - output.mainTrips(),
                output.startsWithMainActivity(), output.endsWithMainActivity(),
                outputPresent && output.startsWithMainActivity()
                        && output.endsWithMainActivity(),
                stuck != null, stuck == null ? List.of() : stuck.times(),
                stuck == null ? List.of() : stuck.modes());
    }

    static Status determineStatus(long missing, long additional, long stuckMissing,
            long nonStuckMissing, long incompleteMissing) {
        if (missing == EXPECTED_DIFFERENCE && additional == 0
                && incompleteMissing == EXPECTED_DIFFERENCE) {
            return Status.EXPLAINED_BY_INCOMPLETE_FINAL_PLANS;
        }
        if (missing == EXPECTED_DIFFERENCE && additional == 0
                && stuckMissing == EXPECTED_DIFFERENCE && nonStuckMissing == 0) {
            if (incompleteMissing != 0) return Status.UNEXPLAINED_REVIEW_REQUIRED;
            return Status.EXPLAINED_BY_STUCK_EVENTS;
        }
        return Status.UNEXPLAINED_REVIEW_REQUIRED;
    }

    static PlanInfo planInfo(Plan plan) {
        if (plan == null || plan.getPlanElements().isEmpty()) return PlanInfo.MISSING;
        List<PlanElement> elements = plan.getPlanElements();
        return new PlanInfo(mainTripCount(plan), isMainActivity(elements.getFirst()),
                isMainActivity(elements.getLast()));
    }

    static int mainTripCount(Plan plan) {
        if (plan == null) return 0;
        return TripStructureUtils.getTrips(plan,
                StageActivityTypeIdentifier::isStageActivity).size();
    }

    private static boolean isMainActivity(PlanElement element) {
        return element instanceof Activity activity && activity.getType() != null
                && !StageActivityTypeIdentifier.isStageActivity(activity.getType());
    }

    static Map<String, StuckInfo> readStuckEvents(Path events) {
        Map<String, MutableStuckInfo> mutable = new HashMap<>();
        var manager = EventsUtils.createEventsManager();
        manager.addHandler((PersonStuckEventHandler) event -> mutable
                .computeIfAbsent(event.getPersonId().toString(), ignored ->
                        new MutableStuckInfo()).add(event));
        new MatsimEventsReader(manager).readFile(events.toString());
        Map<String, StuckInfo> result = new HashMap<>(mutable.size());
        mutable.forEach((person, value) -> result.put(person, value.freeze()));
        return result;
    }

    private static void publish(Comparison comparison) throws IOException {
        Path temporary = OUTPUT.resolve(".trip-count-diagnostic-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            Files.writeString(temporary.resolve("trip_count_mismatch_persons.csv"),
                    personsCsv(comparison), StandardCharsets.UTF_8);
            Files.writeString(temporary.resolve("trip_count_mismatch_report.md"),
                    report(comparison), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, DIAGNOSTIC, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, DIAGNOSTIC);
            }
        } catch (IOException | RuntimeException exception) {
            deleteTemporary(temporary);
            throw exception;
        }
    }

    private static String personsCsv(Comparison result) {
        StringBuilder csv = new StringBuilder("person_id,input_person_present,"
                + "output_person_present,input_main_trip_count,output_main_trip_count,"
                + "missing_trip_difference,output_starts_with_main_activity,"
                + "output_ends_with_main_activity,output_plan_structurally_complete,"
                + "person_stuck_event,stuck_event_times_seconds,stuck_event_modes\n");
        for (PersonDifference person : result.affected()) {
            csv.append(escape(person.personId())).append(',')
                    .append(person.inputPresent()).append(',')
                    .append(person.outputPresent()).append(',')
                    .append(person.inputMainTrips()).append(',')
                    .append(person.outputMainTrips()).append(',')
                    .append(person.missingTrips()).append(',')
                    .append(person.outputStartsWithMainActivity()).append(',')
                    .append(person.outputEndsWithMainActivity()).append(',')
                    .append(person.outputPlanComplete()).append(',')
                    .append(person.stuck()).append(',')
                    .append(escape(joinDoubles(person.stuckTimes()))).append(',')
                    .append(escape(String.join(";", person.stuckModes()))).append('\n');
        }
        return csv.toString();
    }

    private static String report(Comparison result) {
        long missingPersons = result.affected().stream()
                .filter(value -> value.missingTrips() > 0).count();
        long additionalPersons = result.affected().stream()
                .filter(value -> value.missingTrips() < 0).count();
        long malformedPersons = result.affected().stream()
                .filter(value -> !value.outputPlanComplete()).count();
        return "# Literature-based scoring trip-count mismatch diagnostic\n\n"
                + "This read-only comparison uses the same MATSim stage-activity predicate "
                + "and main-trip definition as the established result analyzer. It compares "
                + "the selected plan of each original person with the selected final output "
                + "plan and links PersonStuckEvents without changing either source.\n\n"
                + "## Counts\n\n"
                + "- Input persons: " + result.inputPersons() + "\n"
                + "- Output persons: " + result.outputPersons() + "\n"
                + "- Input main trips: " + result.inputTrips() + "\n"
                + "- Output-plan main trips: " + result.outputTrips() + "\n"
                + "- Previously observed standard output-trip count: "
                + OBSERVED_FINAL_TRIPS + "\n"
                + "- Missing output-plan trips: " + result.missingTrips() + "\n"
                + "- Additional output-plan trips: " + result.additionalTrips() + "\n"
                + "- Persons with missing trips: " + missingPersons + "\n"
                + "- Persons with additional trips: " + additionalPersons + "\n"
                + "- Affected or structurally incomplete persons: "
                + result.affected().size() + "\n\n"
                + "## Evidence\n\n"
                + "- Missing trips belonging to persons with PersonStuckEvents: "
                + result.stuckMissingTrips() + "\n"
                + "- Missing trips belonging to persons without PersonStuckEvents: "
                + result.nonStuckMissingTrips() + "\n"
                + "- Missing trips associated with absent, non-main-starting or "
                + "non-main-ending output plans: " + result.incompleteMissingTrips() + "\n"
                + "- Structurally incomplete output persons: " + malformedPersons + "\n"
                + "- All 257 missing trips belong to stuck persons: "
                + (result.missingTrips() == EXPECTED_DIFFERENCE
                && result.stuckMissingTrips() == EXPECTED_DIFFERENCE
                && result.nonStuckMissingTrips() == 0) + "\n\n"
                + "Association with a stuck event is reported as evidence, not assumed as a "
                + "cause. If selected output plans retain all input trips while the standard "
                + "output-trips file does not, the mismatch remains a writer/execution-history "
                + "question requiring review.\n\n"
                + result.status();
    }

    private static void streamPopulation(URL population,
            org.matsim.core.population.algorithms.PersonAlgorithm algorithm) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(algorithm);
        reader.readURL(population);
    }

    private static void streamPopulation(String population,
            org.matsim.core.population.algorithms.PersonAlgorithm algorithm) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(algorithm);
        reader.readFile(population);
    }

    private static Path required(Path file, String label) {
        require(Files.isRegularFile(file), "Missing " + label + ": " + file);
        return file;
    }

    private static String joinDoubles(List<Double> values) {
        return values.stream().map(value -> String.format(Locale.ROOT, "%.3f", value))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String escape(String value) {
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n")) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void deleteTemporary(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    enum Status {
        EXPLAINED_BY_STUCK_EVENTS,
        EXPLAINED_BY_INCOMPLETE_FINAL_PLANS,
        UNEXPLAINED_REVIEW_REQUIRED
    }

    record PlanInfo(int mainTrips, boolean startsWithMainActivity,
                    boolean endsWithMainActivity) {
        static final PlanInfo MISSING = new PlanInfo(0, false, false);
    }

    record StuckInfo(List<Double> times, List<String> modes) { }

    record PersonDifference(String personId, boolean inputPresent,
                            boolean outputPresent, int inputMainTrips,
                            int outputMainTrips, int missingTrips,
                            boolean outputStartsWithMainActivity,
                            boolean outputEndsWithMainActivity,
                            boolean outputPlanComplete, boolean stuck,
                            List<Double> stuckTimes, List<String> stuckModes) { }

    record Comparison(int inputPersons, int outputPersons, long inputTrips,
                      long outputTrips, long missingTrips, long additionalTrips,
                      long stuckMissingTrips, long nonStuckMissingTrips,
                      long incompleteMissingTrips, List<PersonDifference> affected,
                      Status status) { }

    private static final class MutableStuckInfo {
        private final List<Double> times = new ArrayList<>();
        private final List<String> modes = new ArrayList<>();

        private void add(PersonStuckEvent event) {
            times.add(event.getTime());
            modes.add(event.getLegMode() == null || event.getLegMode().isBlank()
                    ? "unknown" : event.getLegMode().toLowerCase(Locale.ROOT));
        }

        private StuckInfo freeze() {
            return new StuckInfo(List.copyOf(times), List.copyOf(modes));
        }
    }
}
