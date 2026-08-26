package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Read-only diagnosis of input modes, routed physical modes and MATSim routing
 * modes in the preserved resident iteration-0 output.
 */
public final class DiagnoseResidentModeChoiceIteration0MainModes {
    static final int EXAMPLES_PER_TRANSITION = 200;
    private static final double COORDINATE_TOLERANCE_METRES = 1e-6;
    private static final String MISSING = ResidentTripModeClassifier.MISSING;
    private static final String UNKNOWN = ResidentTripModeClassifier.UNKNOWN;
    private static final Path OUTPUT =
            RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT;
    private static final String RUN_ID =
            RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID;
    private static final List<String> REPORT_NAMES = List.of(
            "iteration_0_main_mode_transition_summary.csv",
            "iteration_0_routing_mode_summary.csv",
            "iteration_0_main_mode_change_examples.csv",
            "iteration_0_main_mode_diagnostic_report.md");

    private DiagnoseResidentModeChoiceIteration0MainModes() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The main-mode diagnostic accepts no arguments");
        Path finalPlans = OUTPUT.resolve(RUN_ID + ".output_plans.xml.gz");
        require(Files.isDirectory(OUTPUT),
                "Iteration-0 output directory is missing: " + OUTPUT);
        require(Files.isRegularFile(finalPlans),
                "Iteration-0 final plans are missing: " + finalPlans);
        Path analysis = OUTPUT.resolve("analysis");
        List<Path> targets = REPORT_NAMES.stream().map(analysis::resolve).toList();
        for (Path target : targets) {
            require(!Files.exists(target), "Diagnostic report already exists: " + target);
        }

        var config = ConfigUtils.loadConfig(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG.toString());
        Path inputPopulation = AnalyzeMunichResidentCohort.resolvePopulation(config);
        require(Files.isRegularFile(inputPopulation),
                "Authoritative input population is missing: " + inputPopulation);
        DiagnosticResult result = analyze(inputPopulation, finalPlans,
                MunichMunicipalBoundary.loadDefault());
        writeReports(analysis, result);
        System.out.printf(Locale.ROOT,
                "ITERATION-0 MAIN-MODE DIAGNOSTIC COMPLETE%n"
                        + "persons=%d trips=%d physicalDifferences=%d "
                        + "choiceDifferences=%d%nreports=%s%n"
                        + "No Controller or QSim was started and no MATSim plan was written.%n",
                result.persons(), result.trips(), result.physicalDifferences(),
                result.choiceDifferences(), analysis);
    }

    static DiagnosticResult analyze(Path inputPopulation, Path outputPopulation,
                                    MunichMunicipalBoundary boundary) {
        Baseline baseline = readBaseline(inputPopulation, boundary);
        baseline.requireAuthoritative();
        Accumulator accumulator = new Accumulator();
        MunichTripBoundaryFilter boundaryFilter = new MunichTripBoundaryFilter(boundary);

        stream(outputPopulation, person -> {
            String personId = person.getId().toString();
            InputPerson input = baseline.persons().remove(personId);
            require(input != null, "Output contains an unknown person: " + personId);
            String cohort = PopulationUtils.getSubpopulation(person);
            require(cohort != null && !cohort.isBlank(),
                    "Output runtime cohort is missing for person " + personId);
            require(input.cohort().equals(cohort),
                    "Output runtime cohort changed for person " + personId + ": "
                            + cohort + " != " + input.cohort());
            List<TripDiagnostic> diagnostics = diagnoseTrips(personId, cohort,
                    input.trips(), person.getSelectedPlan(), boundaryFilter);
            accumulator.addPerson(personId, cohort, diagnostics);
        });

        require(baseline.persons().isEmpty(),
                "Output plans omit " + baseline.persons().size() + " input persons");
        DiagnosticResult result = accumulator.result();
        result.requireAuthoritative();
        return result;
    }

    static List<TripDiagnostic> diagnosePlans(String personId, String cohort,
                                              Plan inputPlan, Plan outputPlan,
                                              MunichTripBoundaryFilter boundaryFilter) {
        return diagnoseTrips(personId, cohort, inputTrips(inputPlan), outputPlan,
                boundaryFilter);
    }

    /** Builds the full aggregate evidence from focused synthetic diagnostic rows. */
    static DiagnosticResult summarizeDiagnostics(Collection<TripDiagnostic> rows) {
        TreeMap<String, List<TripDiagnostic>> byPerson = new TreeMap<>();
        rows.forEach(row -> byPerson.computeIfAbsent(row.personId(), ignored ->
                new ArrayList<>()).add(row));
        Accumulator accumulator = new Accumulator();
        byPerson.forEach((personId, diagnostics) -> {
            TreeSet<String> cohorts = diagnostics.stream()
                    .map(TripDiagnostic::cohort)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            require(cohorts.size() == 1,
                    "Synthetic diagnostic person has multiple cohorts: " + personId);
            accumulator.addPerson(personId, cohorts.getFirst(), diagnostics);
        });
        return accumulator.result();
    }

    private static List<TripDiagnostic> diagnoseTrips(
            String personId, String cohort, List<InputTrip> inputTrips,
            Plan outputPlan, MunichTripBoundaryFilter boundaryFilter) {
        require(outputPlan != null, "Output selected plan is missing for person " + personId);
        List<TripStructureUtils.Trip> outputTrips = trips(outputPlan);
        require(inputTrips.size() == outputTrips.size(),
                "Main-trip count changed for person " + personId + ": input="
                        + inputTrips.size() + " output=" + outputTrips.size());
        List<TripDiagnostic> diagnostics = new ArrayList<>(inputTrips.size());
        for (int index = 0; index < inputTrips.size(); index++) {
            diagnostics.add(diagnoseTrip(personId, index, cohort, inputTrips.get(index),
                    outputTrips.get(index), boundaryFilter));
        }
        return List.copyOf(diagnostics);
    }

    private static TripDiagnostic diagnoseTrip(
            String personId, int tripIndex, String cohort, InputTrip input,
            TripStructureUtils.Trip output, MunichTripBoundaryFilter boundaryFilter) {
        ResidentTripModeClassifier.Classification modes =
                ResidentTripModeClassifier.classify(output);
        boolean structureChanged = !input.origin().matches(output.getOriginActivity())
                || !input.destination().matches(output.getDestinationActivity());
        DiagnosticStatus status = status(input.mainMode(), modes, structureChanged);
        return new TripDiagnostic(personId, tripIndex, cohort,
                boundaryFilter.classify(output.getOriginActivity(),
                        output.getDestinationActivity()),
                type(output.getOriginActivity()), type(output.getDestinationActivity()),
                input.mainMode(), modes.physicalMode(), modes.choiceMode(), modes.legModes(),
                modes.routingModes(), modes.stageActivityTypes(), modes.routeDistance(), status);
    }

    private static DiagnosticStatus status(String inputMode,
                                           ResidentTripModeClassifier.Classification modes,
                                           boolean structureChanged) {
        if (structureChanged) return DiagnosticStatus.TRIP_STRUCTURE_CHANGED;
        if (modes.routingState() == ResidentTripModeClassifier.RoutingState.INCONSISTENT) {
            return DiagnosticStatus.ROUTING_MODE_INCONSISTENT;
        }
        if (modes.routingState() == ResidentTripModeClassifier.RoutingState.MISSING) {
            return DiagnosticStatus.ROUTING_MODE_MISSING;
        }
        if (UNKNOWN.equals(inputMode) || UNKNOWN.equals(modes.physicalMode())
                || modes.routingState() == ResidentTripModeClassifier.RoutingState.UNKNOWN) {
            return DiagnosticStatus.UNKNOWN_OR_UNCLASSIFIABLE;
        }
        if (!inputMode.equals(modes.choiceMode())) return DiagnosticStatus.CHOICE_MODE_CHANGED;
        if (!inputMode.equals(modes.physicalMode())) {
            return DiagnosticStatus.PHYSICAL_CHANGED_CHOICE_PRESERVED;
        }
        return DiagnosticStatus.UNCHANGED_PHYSICAL_AND_CHOICE;
    }

    private static List<InputTrip> inputTrips(Plan plan) {
        require(plan != null, "Input selected plan is missing");
        return trips(plan).stream().map(trip -> new InputTrip(
                ActivitySignature.of(trip.getOriginActivity()),
                ActivitySignature.of(trip.getDestinationActivity()),
                ResidentTripModeClassifier.physicalMainMode(trip))).toList();
    }

    private static List<TripStructureUtils.Trip> trips(Plan plan) {
        try {
            return TripStructureUtils.getTrips(plan,
                    StageActivityTypeIdentifier::isStageActivity);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Selected plan cannot be interpreted as main trips",
                    exception);
        }
    }


    private static Baseline readBaseline(Path population,
                                         MunichMunicipalBoundary boundary) {
        require(Files.isRegularFile(population), "Input population is missing: " + population);
        HashMap<String, InputPerson> persons = new HashMap<>();
        TreeMap<String, Long> cohorts = new TreeMap<>();
        MunichResidentClassifier classifier = new MunichResidentClassifier(boundary);
        stream(population, person -> {
            String id = person.getId().toString();
            String cohort = ResidentCalibrationSubpopulations.labelFor(
                    classifier.classify(person).classification());
            InputPerson previous = persons.put(id,
                    new InputPerson(cohort, inputTrips(person.getSelectedPlan())));
            require(previous == null, "Duplicate input person " + id);
            cohorts.merge(cohort, 1L, Long::sum);
        });
        return new Baseline(persons, Map.copyOf(cohorts));
    }

    private static void stream(Path population, Consumer<Person> consumer) {
        require(Files.isRegularFile(population), "Population file is missing: " + population);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(consumer::accept);
        reader.readFile(population.toString());
    }

    private static void writeReports(Path analysis, DiagnosticResult result)
            throws IOException {
        Files.createDirectories(analysis);
        Map<String, String> files = Map.of(
                "iteration_0_main_mode_transition_summary.csv", transitionCsv(result),
                "iteration_0_routing_mode_summary.csv", routingCsv(result),
                "iteration_0_main_mode_change_examples.csv", examplesCsv(result),
                "iteration_0_main_mode_diagnostic_report.md", report(result));
        for (String name : REPORT_NAMES) {
            Files.writeString(analysis.resolve(name), files.get(name), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    private static String transitionCsv(DiagnosticResult result) {
        StringBuilder out = new StringBuilder("runtime_cohort,spatial_category,"
                + "input_main_mode,output_physical_main_mode,output_choice_mode,"
                + "diagnostic_status,trip_count\n");
        result.transitions().forEach((key, count) -> out.append(csv(key.cohort())).append(',')
                .append(key.spatial()).append(',').append(csv(key.input())).append(',')
                .append(csv(key.physical())).append(',').append(csv(key.choice())).append(',')
                .append(key.status()).append(',').append(count).append('\n'));
        return out.toString();
    }

    private static String routingCsv(DiagnosticResult result) {
        StringBuilder out = new StringBuilder("input_main_mode,physical_mode,choice_mode,"
                + "leg_mode_sequence,routing_mode_sequence,trip_count\n");
        result.routing().forEach((key, count) -> out.append(csv(key.input())).append(',')
                .append(csv(key.physical())).append(',').append(csv(key.choice())).append(',')
                .append(csv(key.legSequence())).append(',')
                .append(csv(key.routingSequence())).append(',').append(count).append('\n'));
        return out.toString();
    }

    private static String examplesCsv(DiagnosticResult result) {
        StringBuilder out = new StringBuilder("person_id,trip_index,cohort,spatial_category,"
                + "origin_type,destination_type,input_mode,physical_output_mode,"
                + "choice_output_mode,leg_modes,routing_modes,stage_activity_types,"
                + "route_distance,diagnostic_status\n");
        for (TripDiagnostic row : result.examples()) {
            out.append(csv(row.personId())).append(',').append(row.tripIndex()).append(',')
                    .append(csv(row.cohort())).append(',').append(row.spatial()).append(',')
                    .append(csv(row.originType())).append(',')
                    .append(csv(row.destinationType())).append(',')
                    .append(csv(row.inputMode())).append(',')
                    .append(csv(row.physicalMode())).append(',')
                    .append(csv(row.choiceMode())).append(',')
                    .append(csv(sequence(row.legModes()))).append(',')
                    .append(csv(sequence(row.routingModes()))).append(',')
                    .append(csv(sequence(row.stageActivityTypes()))).append(',')
                    .append(Double.isFinite(row.routeDistance())
                            ? String.format(Locale.ROOT, "%.3f", row.routeDistance()) : "")
                    .append(',').append(row.status()).append('\n');
        }
        return out.toString();
    }

    private static String report(DiagnosticResult result) {
        StringBuilder out = new StringBuilder("# Iteration-0 main-mode diagnostic\n\n")
                .append("This report compares the authoritative input population with the ")
                .append("preserved iteration-0 final plans. It did not run or alter MATSim.\n\n")
                .append("## Scope and headline findings\n\n")
                .append("- Persons compared: ").append(result.persons()).append("\n")
                .append("- Main trips compared: ").append(result.trips()).append("\n")
                .append("- Munich-resident main trips: ").append(result.residentTrips())
                .append("\n- Physical main-mode differences: ")
                .append(result.physicalDifferences())
                .append("\n- Choice/routing-mode differences: ")
                .append(result.choiceDifferences())
                .append("\n- Missing routing modes: ").append(result.missingRoutingModes())
                .append("\n- Inconsistent routing modes: ")
                .append(result.inconsistentRoutingModes())
                .append("\n- Changed main-trip structures: ")
                .append(result.changedTripStructures()).append("\n\n")
                .append("All physical differences share one diagnostic status: **")
                .append(result.physicalDifferenceStatuses().size() == 1 ? "YES" : "NO")
                .append("** (`").append(String.join("`, `", result.physicalDifferenceStatuses()))
                .append("`).\n\nTrue choice-mode changes occurred: **")
                .append(result.choiceDifferences() > 0 ? "YES" : "NO").append("**.\n\n")
                .append("## Transition matrix\n\n")
                .append("| Input mode | Output physical mode | Output choice mode | Status | Trips |\n")
                .append("|---|---|---|---|---:|\n");
        result.matrix().forEach((key, count) -> out.append("| `").append(key.input())
                .append("` | `").append(key.physical()).append("` | `")
                .append(key.choice()).append("` | `").append(key.status())
                .append("` | ").append(count).append(" |\n"));
        out.append("\n## Differences by runtime cohort\n\n")
                .append("| Cohort | Trips | Physical differences | Choice differences | Missing routing mode | Inconsistent routing mode | Trip structure changed |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        result.cohorts().forEach((cohort, counts) -> out.append("| `").append(cohort)
                .append("` | ").append(counts.trips()).append(" | ")
                .append(counts.physicalDifferences()).append(" | ")
                .append(counts.choiceDifferences()).append(" | ")
                .append(counts.missingRoutingModes()).append(" | ")
                .append(counts.inconsistentRoutingModes()).append(" | ")
                .append(counts.changedTripStructures()).append(" |\n"));
        out.append("\n## Stage activities per matched main trip\n\n")
                .append("| Stage activities | Trips |\n|---:|---:|\n");
        result.stageActivityCounts().forEach((stages, count) -> out.append("| ")
                .append(stages).append(" | ").append(count).append(" |\n"));
        CohortCounts residents = result.cohorts().get(
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT);
        out.append("\nFor Munich residents specifically, ")
                .append(residents.physicalDifferences()).append(" of ")
                .append(residents.trips()).append(" trips have a physical-mode difference and ")
                .append(residents.choiceDifferences())
                .append(" have a defensible choice/routing-mode difference.\n\n")
                .append("## Interpretation and next decision\n\n")
                .append("`DefaultAnalysisMainModeIdentifier`, used by the current calibration ")
                .append("analyzer and the existing iteration-0 comparison, identifies the ")
                .append("physical/analysis mode from actual leg modes. The separate choice ")
                .append("mode in this report is read through MATSim's official routing-mode ")
                .append("API. A walk-only routed PT request therefore remains visibly distinct ")
                .append("from a genuine PT-to-walk choice change.\n\n")
                .append("No validator or productive mode definition was changed. Review the ")
                .append("transition, routing and example CSV files first. If all apparent ")
                .append("changes preserve their input routing choice and no missing, ")
                .append("inconsistent or structural cases remain, the next correction should ")
                .append("consider validating iteration-zero choice modes through routingMode ")
                .append("while continuing to report physical leg composition separately. Any ")
                .append("true choice change or unresolved case requires investigation before ")
                .append("such a correction. Run 12 remains blocked.\n");
        return out.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String sequence(Collection<String> values) {
        return String.join("|", values);
    }


    private static String type(Activity activity) {
        return activity == null || activity.getType() == null ? MISSING : activity.getType();
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    public enum DiagnosticStatus {
        UNCHANGED_PHYSICAL_AND_CHOICE,
        PHYSICAL_CHANGED_CHOICE_PRESERVED,
        CHOICE_MODE_CHANGED,
        ROUTING_MODE_MISSING,
        ROUTING_MODE_INCONSISTENT,
        TRIP_STRUCTURE_CHANGED,
        UNKNOWN_OR_UNCLASSIFIABLE
    }

    record TripDiagnostic(String personId, int tripIndex, String cohort,
                          MunichTripBoundaryFilter.SpatialCategory spatial,
                          String originType, String destinationType,
                          String inputMode, String physicalMode, String choiceMode,
                          List<String> legModes, List<String> routingModes,
                          List<String> stageActivityTypes, double routeDistance,
                          DiagnosticStatus status) { }

    private record InputTrip(ActivitySignature origin, ActivitySignature destination,
                             String mainMode) { }
    private record InputPerson(String cohort, List<InputTrip> trips) { }

    private record ActivitySignature(String type, Coord coord) {
        static ActivitySignature of(Activity activity) {
            return new ActivitySignature(
                    DiagnoseResidentModeChoiceIteration0MainModes.type(activity),
                    activity == null ? null
                    : activity.getCoord());
        }

        boolean matches(Activity activity) {
            if (!type.equals(
                    DiagnoseResidentModeChoiceIteration0MainModes.type(activity))) return false;
            Coord other = activity == null ? null : activity.getCoord();
            if (coord == null || other == null) return coord == null && other == null;
            return Double.isFinite(coord.getX()) && Double.isFinite(coord.getY())
                    && Double.isFinite(other.getX()) && Double.isFinite(other.getY())
                    && Math.abs(coord.getX() - other.getX()) <= COORDINATE_TOLERANCE_METRES
                    && Math.abs(coord.getY() - other.getY()) <= COORDINATE_TOLERANCE_METRES;
        }
    }

    private record Baseline(HashMap<String, InputPerson> persons,
                            Map<String, Long> cohorts) {
        void requireAuthoritative() {
            require(persons.size() == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                    "Authoritative input person count changed: " + persons.size());
            requireAuthoritativeCohorts(cohorts, "input classification");
        }
    }

    record TransitionKey(String cohort,
                         MunichTripBoundaryFilter.SpatialCategory spatial,
                         String input, String physical, String choice,
                         DiagnosticStatus status) implements Comparable<TransitionKey> {
        @Override public int compareTo(TransitionKey other) {
            return Comparator.comparing(TransitionKey::cohort)
                    .thenComparing(TransitionKey::spatial)
                    .thenComparing(TransitionKey::input)
                    .thenComparing(TransitionKey::physical)
                    .thenComparing(TransitionKey::choice)
                    .thenComparing(TransitionKey::status).compare(this, other);
        }
    }

    record RoutingKey(String input, String physical, String choice,
                      String legSequence, String routingSequence)
            implements Comparable<RoutingKey> {
        @Override public int compareTo(RoutingKey other) {
            return Comparator.comparing(RoutingKey::input)
                    .thenComparing(RoutingKey::physical)
                    .thenComparing(RoutingKey::choice)
                    .thenComparing(RoutingKey::legSequence)
                    .thenComparing(RoutingKey::routingSequence).compare(this, other);
        }
    }

    record MatrixKey(String input, String physical, String choice,
                     DiagnosticStatus status) implements Comparable<MatrixKey> {
        @Override public int compareTo(MatrixKey other) {
            return Comparator.comparing(MatrixKey::input)
                    .thenComparing(MatrixKey::physical)
                    .thenComparing(MatrixKey::choice)
                    .thenComparing(MatrixKey::status).compare(this, other);
        }
    }

    private record ExampleKey(String input, String physical, String choice,
                              DiagnosticStatus status) implements Comparable<ExampleKey> {
        @Override public int compareTo(ExampleKey other) {
            return Comparator.comparing(ExampleKey::input)
                    .thenComparing(ExampleKey::physical)
                    .thenComparing(ExampleKey::choice)
                    .thenComparing(ExampleKey::status).compare(this, other);
        }
    }

    record CohortCounts(long trips, long physicalDifferences, long choiceDifferences,
                        long missingRoutingModes, long inconsistentRoutingModes,
                        long changedTripStructures) { }

    record DiagnosticResult(long persons, long trips, long residentTrips,
                            Map<String, Long> personCohorts,
                            Map<String, String> cohortByPerson,
                            long physicalDifferences, long choiceDifferences,
                            long missingRoutingModes, long inconsistentRoutingModes,
                            long changedTripStructures,
                            Set<String> physicalDifferenceStatuses,
                            Map<TransitionKey, Long> transitions,
                            Map<RoutingKey, Long> routing,
                            Map<MatrixKey, Long> matrix,
                            Map<String, CohortCounts> cohorts,
                            Map<Integer, Long> stageActivityCounts,
                            List<TripDiagnostic> examples) {
        void requireAuthoritative() {
            require(persons == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                    "Output person count changed: " + persons);
            requireAuthoritativeCohorts(personCohorts, "output runtime cohort");
            require(residentTrips
                            == ResidentModeChoiceCalibrationIterationListener
                            .EXPECTED_RESIDENT_MAIN_TRIPS,
                    "Resident main-trip count changed: " + residentTrips);
            long transitionSum = transitions.values().stream()
                    .mapToLong(Long::longValue).sum();
            long routingSum = routing.values().stream().mapToLong(Long::longValue).sum();
            long stageSum = stageActivityCounts.values().stream()
                    .mapToLong(Long::longValue).sum();
            require(transitionSum == trips && routingSum == trips && stageSum == trips,
                    "Diagnostic summaries do not cover every matched trip");
        }
    }

    static final class BoundedExamples {
        private static final Comparator<TripDiagnostic> ROW_ORDER =
                Comparator.comparing(TripDiagnostic::personId)
                        .thenComparingInt(TripDiagnostic::tripIndex);
        private final int limit;
        private final TreeMap<ExampleKey, TreeSet<TripDiagnostic>> groups = new TreeMap<>();

        BoundedExamples(int limit) {
            require(limit > 0, "Example limit must be positive");
            this.limit = limit;
        }

        void add(TripDiagnostic row) {
            if (row.status() == DiagnosticStatus.UNCHANGED_PHYSICAL_AND_CHOICE) return;
            ExampleKey key = new ExampleKey(row.inputMode(), row.physicalMode(),
                    row.choiceMode(), row.status());
            TreeSet<TripDiagnostic> values = groups.computeIfAbsent(key,
                    ignored -> new TreeSet<>(ROW_ORDER));
            values.add(row);
            if (values.size() > limit) values.pollLast();
        }

        List<TripDiagnostic> rows() {
            return groups.values().stream().flatMap(Collection::stream).toList();
        }
    }

    private static final class MutableCohortCounts {
        long trips;
        long physical;
        long choice;
        long missing;
        long inconsistent;
        long structure;

        CohortCounts snapshot() {
            return new CohortCounts(trips, physical, choice, missing, inconsistent,
                    structure);
        }
    }

    private static final class Accumulator {
        private long persons;
        private long trips;
        private long residentTrips;
        private long physicalDifferences;
        private long choiceDifferences;
        private long missingRoutingModes;
        private long inconsistentRoutingModes;
        private long changedTripStructures;
        private final TreeMap<String, Long> personCohorts = new TreeMap<>();
        private final TreeMap<String, String> cohortByPerson = new TreeMap<>();
        private final TreeMap<TransitionKey, Long> transitions = new TreeMap<>();
        private final TreeMap<RoutingKey, Long> routing = new TreeMap<>();
        private final TreeMap<MatrixKey, Long> matrix = new TreeMap<>();
        private final TreeMap<String, MutableCohortCounts> cohorts = new TreeMap<>();
        private final TreeMap<Integer, Long> stageActivityCounts = new TreeMap<>();
        private final TreeSet<String> physicalDifferenceStatuses = new TreeSet<>();
        private final BoundedExamples examples =
                new BoundedExamples(EXAMPLES_PER_TRANSITION);

        void addPerson(String personId, String cohort, List<TripDiagnostic> diagnostics) {
            persons++;
            require(cohortByPerson.put(personId, cohort) == null,
                    "Duplicate output person " + personId);
            personCohorts.merge(cohort, 1L, Long::sum);
            for (TripDiagnostic row : diagnostics) add(row);
        }

        private void add(TripDiagnostic row) {
            trips++;
            if (ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(row.cohort())) {
                residentTrips++;
            }
            boolean physicalChanged = !row.inputMode().equals(row.physicalMode());
            boolean choiceChanged = row.status() == DiagnosticStatus.CHOICE_MODE_CHANGED;
            boolean missing = row.status() == DiagnosticStatus.ROUTING_MODE_MISSING;
            boolean inconsistent = row.status()
                    == DiagnosticStatus.ROUTING_MODE_INCONSISTENT;
            boolean structure = row.status() == DiagnosticStatus.TRIP_STRUCTURE_CHANGED;
            if (physicalChanged) {
                physicalDifferences++;
                physicalDifferenceStatuses.add(row.status().name());
            }
            if (choiceChanged) choiceDifferences++;
            if (missing) missingRoutingModes++;
            if (inconsistent) inconsistentRoutingModes++;
            if (structure) changedTripStructures++;
            MutableCohortCounts cohort = cohorts.computeIfAbsent(row.cohort(),
                    ignored -> new MutableCohortCounts());
            cohort.trips++;
            if (physicalChanged) cohort.physical++;
            if (choiceChanged) cohort.choice++;
            if (missing) cohort.missing++;
            if (inconsistent) cohort.inconsistent++;
            if (structure) cohort.structure++;
            transitions.merge(new TransitionKey(row.cohort(), row.spatial(),
                    row.inputMode(), row.physicalMode(), row.choiceMode(), row.status()),
                    1L, Long::sum);
            routing.merge(new RoutingKey(row.inputMode(), row.physicalMode(),
                    row.choiceMode(), sequence(row.legModes()),
                    sequence(row.routingModes())), 1L, Long::sum);
            matrix.merge(new MatrixKey(row.inputMode(), row.physicalMode(),
                    row.choiceMode(), row.status()), 1L, Long::sum);
            stageActivityCounts.merge(row.stageActivityTypes().size(), 1L, Long::sum);
            examples.add(row);
        }

        DiagnosticResult result() {
            TreeMap<String, CohortCounts> cohortSnapshots = new TreeMap<>();
            cohorts.forEach((key, value) -> cohortSnapshots.put(key, value.snapshot()));
            return new DiagnosticResult(persons, trips, residentTrips,
                    sortedMap(personCohorts), sortedMap(cohortByPerson),
                    physicalDifferences, choiceDifferences,
                    missingRoutingModes, inconsistentRoutingModes, changedTripStructures,
                    Collections.unmodifiableSet(new TreeSet<>(physicalDifferenceStatuses)),
                    sortedMap(transitions), sortedMap(routing), sortedMap(matrix),
                    sortedMap(cohortSnapshots), sortedMap(stageActivityCounts),
                    examples.rows());
        }
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> sortedMap(
            Map<K, V> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static void requireAuthoritativeCohorts(Map<String, Long> counts,
                                                    String label) {
        Map<String, Long> expected = Map.of(
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
                ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND,
                ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND);
        require(counts.equals(expected), label + " counts changed: " + counts
                + " != " + expected);
    }
}
