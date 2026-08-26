package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only technical acceptance check for the protected server iteration-0 run. */
public final class ValidateResidentModeChoiceIteration0Output {
    private static final Path OUTPUT =
            RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT;
    private static final String RUN_ID =
            RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID;
    private static final Expectations AUTHORITATIVE = new Expectations(
            324_043, 68_770, 147_655, 107_618, 137_540,
            Map.of(
                    MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE, 123_186L,
                    MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY, 7_177L,
                    MunichTripBoundaryFilter.SpatialCategory.DESTINATION_ONLY, 7_177L,
                    MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE, 0L,
                    MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE, 0L));

    private ValidateResidentModeChoiceIteration0Output() { }

    public static void main(String[] args) throws Exception {
        try {
            require(args.length == 0, "The iteration-0 output validator accepts no arguments");
            ValidationResult result = validateAndWrite(null);
            System.out.println(result.reviewRequired()
                    ? "ITERATION-0 VALIDATION PASS WITH REVIEW REQUIRED"
                    : "ITERATION-0 VALIDATION PASS");
        } catch (Throwable failure) {
            System.err.println("ITERATION-0 VALIDATION FAIL");
            if (failure instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    static ValidationResult validateAndWrite(Map<Path, String> protectedBefore)
            throws Exception {
        RequiredFiles files = locateRequiredFiles(OUTPUT, RUN_ID);
        validateNormalTermination(files.log());
        validateOutputConfig(files.outputConfig());

        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        Path inputPopulation = AnalyzeMunichResidentCohort.resolvePopulation(
                ValidateResidentModeChoiceCalibrationConfig.loadAndValidateStructure(false));
        PlanAudit plans = comparePlans(inputPopulation, files.finalPlans(), boundary);
        AnalysisAudit analysis = validateAnalysis(files.iterationAnalysis());
        EventAudit events = readEvents(files.events(), plans.cohortByPerson());
        Facts facts = new Facts(plans.persons(), plans.cohorts(), plans.residentTrips(),
                plans.spatial(), analysis.complete(), analysis.backgroundExcluded(),
                events.totalEvents(), events.carDepartures(), events.carVehicles(),
                events.ptDepartures(), events.transitDrivers(), events.ptBoardings(),
                events.stuck().size(), events.uniqueStuckPersons(), plans.modeChanges());
        boolean reviewRequired = validateFacts(facts, AUTHORITATIVE);

        Map<Path, HashAudit> hashes = protectedHashes(protectedBefore);
        require(!Files.exists(ValidateResidentModeChoiceCalibrationConfig.OUTPUT),
                "Productive 0-20 output was created during iteration-0 validation");
        writeReports(files.output(), facts, plans, events, hashes, reviewRequired);
        return new ValidationResult(reviewRequired, facts);
    }

    static RequiredFiles locateRequiredFiles(Path output, String runId) {
        require(Files.isDirectory(output), "Iteration-0 output directory is missing: " + output);
        Path iteration = output.resolve("ITERS/it.0");
        require(Files.isDirectory(iteration), "Iteration 0 directory is missing: " + iteration);
        return new RequiredFiles(output,
                required(iteration.resolve(runId + ".0.events.xml.gz"), "iteration events"),
                required(iteration.resolve(runId + ".0.plans.xml.gz"), "iteration plans"),
                required(output.resolve(runId + ".output_plans.xml.gz"), "final plans"),
                required(output.resolve(runId + ".output_config.xml"), "output config"),
                required(output.resolve(runId + ".output_network.xml.gz"), "output network"),
                required(output.resolve(runId + ".output_transitSchedule.xml.gz"),
                        "output transit schedule"),
                required(output.resolve(runId + ".output_transitVehicles.xml.gz"),
                        "output transit vehicles"),
                required(output.resolve(runId + ".logfile.log"), "controller log"),
                required(output.resolve("analysis/resident_mode_choice_iteration_metrics.csv"),
                        "resident iteration analysis"));
    }

    static boolean validateFacts(Facts facts, Expectations expected) {
        require(facts.persons() == expected.persons(), "Output person count changed");
        require(facts.cohorts().getOrDefault(
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT, 0L)
                        == expected.residents(), "Output Munich-resident count changed");
        require(facts.cohorts().getOrDefault(
                ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND, 0L)
                        == expected.regional(), "Output regional-background count changed");
        require(facts.cohorts().getOrDefault(
                ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND, 0L)
                        == expected.unresolved(), "Output unresolved-background count changed");
        require(facts.cohorts().values().stream().mapToLong(Long::longValue).sum()
                        == expected.persons(), "Runtime cohort counts do not sum to all persons");
        require(facts.residentTrips() == expected.residentTrips(),
                "Resident main-trip count changed");
        for (var entry : expected.spatial().entrySet()) {
            require(facts.spatial().getOrDefault(entry.getKey(), 0L).equals(entry.getValue()),
                    "Resident spatial count changed for " + entry.getKey());
        }
        require(facts.spatial().values().stream().mapToLong(Long::longValue).sum()
                        == expected.residentTrips(),
                "Resident spatial categories do not sum to all resident trips");
        require(facts.analysisComplete(), "Resident trip/Pkm analysis rows are incomplete");
        require(facts.backgroundExcluded(),
                "Background groups are not explicitly excluded from target metrics");
        require(facts.totalEvents() > 0, "Events file contains no readable events");
        require(facts.carDepartures() > 0 && facts.carVehicles() > 0,
                "Car was not demonstrably routed and simulated");
        require(facts.ptDepartures() > 0 && facts.transitDrivers() > 0
                        && facts.ptBoardings() > 0,
                "PT routing, vehicle or passenger events are missing");
        require(facts.modeChanges() == 0,
                "Iteration 0 contains unexplained input-to-output main-mode changes: "
                        + facts.modeChanges());
        require(facts.uniqueStuckPersons() <= facts.stuckEvents(),
                "Unique stuck persons exceed stuck events");
        return facts.stuckEvents() > 0;
    }

    static List<String> mainModes(Plan plan) {
        if (plan == null) return List.of();
        return new MunichTripBoundaryFilterHolder().filter.classify(plan).stream()
                .map(MunichTripBoundaryFilter.ClassifiedTrip::inputMainMode).toList();
    }

    private static PlanAudit comparePlans(Path input, Path output,
                                          MunichMunicipalBoundary boundary) {
        Map<String, InputPerson> baseline = readBaseline(input, boundary);
        TreeMap<String, Long> cohorts = new TreeMap<>();
        EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> spatial =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        TreeMap<ModeKey, Long> comparisons = new TreeMap<>();
        Map<String, String> cohortByPerson = new HashMap<>();
        Counter counter = new Counter();
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        stream(output, person -> {
            counter.persons++;
            String id = person.getId().toString();
            InputPerson source = baseline.remove(id);
            require(source != null, "Output contains an unknown person: " + id);
            String cohort = PopulationUtils.getSubpopulation(person);
            require(source.cohort().equals(cohort),
                    "Runtime cohort changed for person " + id + ": " + cohort);
            cohorts.merge(cohort, 1L, Long::sum);
            cohortByPerson.put(id, cohort);
            List<MunichTripBoundaryFilter.ClassifiedTrip> trips =
                    filter.classify(person.getSelectedPlan());
            List<String> modes = trips.stream()
                    .map(MunichTripBoundaryFilter.ClassifiedTrip::inputMainMode).toList();
            require(modes.size() == source.modes().size(),
                    "Main-trip count changed for person " + id);
            for (int index = 0; index < modes.size(); index++) {
                String from = source.modes().get(index);
                String to = modes.get(index);
                comparisons.merge(new ModeKey(cohort, from, to), 1L, Long::sum);
                if (!from.equals(to)) counter.modeChanges++;
            }
            if (ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(cohort)) {
                counter.residentTrips += trips.size();
                trips.forEach(trip -> spatial.merge(trip.category(), 1L, Long::sum));
            }
        });
        require(baseline.isEmpty(), "Output plans omit " + baseline.size() + " input persons");
        return new PlanAudit(counter.persons, Map.copyOf(cohorts), counter.residentTrips,
                Map.copyOf(spatial), counter.modeChanges, Map.copyOf(comparisons),
                Map.copyOf(cohortByPerson));
    }

    private static Map<String, InputPerson> readBaseline(
            Path input, MunichMunicipalBoundary boundary) {
        Map<String, InputPerson> result = new HashMap<>();
        MunichResidentClassifier classifier = new MunichResidentClassifier(boundary);
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        stream(input, person -> {
            var classification = classifier.classify(person).classification();
            String cohort = switch (classification) {
                case MUNICH_RESIDENT -> ResidentCalibrationSubpopulations.MUNICH_RESIDENT;
                case NON_MUNICH_RESIDENT ->
                        ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND;
                default -> ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND;
            };
            List<String> modes = filter.classify(person.getSelectedPlan()).stream()
                    .map(MunichTripBoundaryFilter.ClassifiedTrip::inputMainMode).toList();
            require(result.put(person.getId().toString(),
                    new InputPerson(cohort, modes)) == null,
                    "Duplicate input person " + person.getId());
        });
        return result;
    }

    private static void stream(Path population,
                               java.util.function.Consumer<Person> consumer) {
        require(Files.isRegularFile(population), "Population file is missing: " + population);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(consumer::accept);
        reader.readFile(population.toString());
    }

    private static EventAudit readEvents(Path file, Map<String, String> cohorts) {
        EventCollector collector = new EventCollector(cohorts);
        EventsManager manager = EventsUtils.createEventsManager();
        manager.addHandler(collector);
        new MatsimEventsReader(manager).readFile(file.toString());
        return collector.result();
    }

    private static AnalysisAudit validateAnalysis(Path file) throws IOException {
        Map<CsvKey, Double> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        require(!lines.isEmpty() && lines.getFirst().startsWith("iteration,metric,dimension"),
                "Resident analysis CSV header is missing");
        for (int index = 1; index < lines.size(); index++) {
            String[] columns = lines.get(index).split(",", -1);
            if (columns.length < 4 || !"0".equals(columns[0])) continue;
            rows.put(new CsvKey(columns[1], columns[2]), Double.parseDouble(columns[3]));
        }
        require(value(rows, "resident_persons", "all") == 68_770,
                "Resident analysis person row changed");
        require(value(rows, "resident_main_trips", "all") == 137_540,
                "Resident analysis trip row changed");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            value(rows, "resident_trip_share", mode);
            value(rows, "raw_simulated_daily_sample_pkm", mode);
            value(rows, "resident_pkm_share", mode);
        }
        for (var entry : AUTHORITATIVE.spatial().entrySet()) {
            require(value(rows, "resident_spatial_main_trips", entry.getKey().name())
                            == entry.getValue(),
                    "Analysis spatial row changed for " + entry.getKey());
        }
        boolean excluded = value(rows, "background_persons_excluded_from_targets",
                ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND) == 147_655
                && value(rows, "background_persons_excluded_from_targets",
                ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND) == 107_618;
        return new AnalysisAudit(true, excluded);
    }

    private static double value(Map<CsvKey, Double> rows, String metric, String dimension) {
        Double value = rows.get(new CsvKey(metric, dimension));
        require(value != null && Double.isFinite(value),
                "Missing analysis row " + metric + "/" + dimension);
        return value;
    }

    private static void validateOutputConfig(Path file) throws Exception {
        Config output = ConfigUtils.loadConfig(file.toString());
        Config expected = RunMatsim2019ResidentModeChoiceIteration0Validation
                .applyApprovedOverrides(ValidateResidentModeChoiceCalibrationConfig
                        .loadAndValidateStructure(false));
        require(RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(output)
                        .equals(RunMatsim2019ResidentModeChoiceIteration0Validation
                                .snapshot(expected)),
                "Output config differs from the productive config plus three approved overrides");
        require(output.controller().getLastIteration() == 0,
                "Output config does not record lastIteration=0");
        require(ValidateResidentModeChoiceCalibrationConfig.strategyMap(output).equals(
                        ValidateResidentModeChoiceCalibrationConfig.strategyMap(expected)),
                "Output strategy scoping changed");
    }

    private static void validateNormalTermination(Path log) throws IOException {
        String text = Files.readString(log, StandardCharsets.UTF_8);
        require(text.contains("shutdown completed"),
                "Controller log does not record completed shutdown");
        String lower = text.toLowerCase(Locale.ROOT);
        require(!lower.contains("unexpected shutdown") && !lower.contains(" fatal ")
                        && !lower.lines().anyMatch(line -> line.contains(" error ")),
                "Controller or routing error found in log");
    }

    private static Map<Path, HashAudit> protectedHashes(Map<Path, String> before)
            throws Exception {
        Map<Path, String> after =
                ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        LinkedHashMap<Path, HashAudit> result = new LinkedHashMap<>();
        for (var entry : after.entrySet()) {
            String expected = before == null
                    ? ValidateResidentModeChoiceCalibrationConfig
                            .protectedInputExpectations().get(entry.getKey())
                    : before.get(entry.getKey());
            if (expected == null && entry.getKey().equals(
                    ValidateResidentModeChoiceCalibrationConfig.CONFIG.normalize())) {
                expected = entry.getValue();
            }
            require(expected != null && expected.equals(entry.getValue()),
                    "Protected input changed during iteration-0 validation: "
                            + entry.getKey());
            result.put(entry.getKey(), new HashAudit(expected, entry.getValue(), "UNCHANGED"));
        }
        if (before != null) require(before.keySet().equals(after.keySet()),
                "Protected input set changed during iteration-0 validation");
        return Map.copyOf(result);
    }

    private static void writeReports(Path output, Facts facts, PlanAudit plans,
                                     EventAudit events, Map<Path, HashAudit> hashes,
                                     boolean review) throws IOException {
        Path analysis = output.resolve("analysis");
        Files.createDirectories(analysis);
        Files.writeString(analysis.resolve("iteration_0_validation_summary.csv"),
                summaryCsv(facts, review), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve("iteration_0_validation_report.md"),
                report(facts, events, review), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve("iteration_0_stuck_summary.csv"),
                stuckCsv(events), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve("iteration_0_mode_comparison.csv"),
                modeCsv(plans), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve("protected_input_hashes.csv"),
                hashCsv(hashes), StandardCharsets.UTF_8);
    }

    private static String summaryCsv(Facts facts, boolean review) {
        String csv = "metric,value,status\n"
                + "validation_status," + (review ? "PASS_WITH_REVIEW_REQUIRED" : "PASS")
                + "," + (review ? "REVIEW_REQUIRED" : "PASS") + "\n"
                + "persons," + facts.persons() + ",PASS\n"
                + "munich_resident," + facts.cohorts().get(
                        ResidentCalibrationSubpopulations.MUNICH_RESIDENT) + ",PASS\n"
                + "regional_background," + facts.cohorts().get(
                        ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND) + ",PASS\n"
                + "unresolved_background," + facts.cohorts().get(
                        ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND) + ",PASS\n"
                + "resident_main_trips," + facts.residentTrips() + ",PASS\n"
                + "main_mode_changes," + facts.modeChanges() + ",PASS\n"
                + "stuck_events," + facts.stuckEvents() + ","
                + (review ? "REVIEW_REQUIRED" : "PASS") + "\n";
        StringBuilder result = new StringBuilder(csv);
        facts.spatial().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.append("resident_spatial_")
                        .append(entry.getKey()).append(',').append(entry.getValue())
                        .append(",PASS\n"));
        result.append("car_departures,").append(facts.carDepartures()).append(",PASS\n")
                .append("car_vehicles_entering_traffic,").append(facts.carVehicles())
                .append(",PASS\npt_departures,").append(facts.ptDepartures())
                .append(",PASS\ntransit_driver_starts,").append(facts.transitDrivers())
                .append(",PASS\npt_passenger_boardings,").append(facts.ptBoardings())
                .append(",PASS\n");
        return result.toString();
    }

    private static String report(Facts facts, EventAudit events, boolean review) {
        long residentStuck = events.stuck().stream()
                .map(StuckRecord::person).distinct()
                .filter(person -> ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                        events.cohorts().get(person))).count();
        return "# Resident calibration iteration-0 validation\n\n"
                + "Status: **" + (review ? "PASS WITH REVIEW REQUIRED" : "PASS")
                + "**.\n\nIteration 0 validates technical execution, routing, transit, "
                + "runtime cohort assignment and analysis. It does not demonstrate calibrated "
                + "mode choice or convergence. Strategy scoping is established structurally by "
                + "the productive config validator and focused tests; observed resident mode "
                + "change is assessed in the later 0-20 run.\n\n"
                + "Persons: " + facts.persons() + "; resident trips: "
                + facts.residentTrips() + "; unexplained main-mode changes: "
                + facts.modeChanges() + ".\n\nStuck events: " + facts.stuckEvents()
                + "; unique affected persons: " + facts.uniqueStuckPersons() + " ("
                + percent(facts.uniqueStuckPersons(), facts.persons()) + "% of all persons); "
                + "affected residents: " + residentStuck + " ("
                + percent(residentStuck, 68_770) + "% of resident persons). "
                + (review ? "A nonzero result is marked `REVIEW_REQUIRED`; no arbitrary "
                        + "acceptance threshold is imposed."
                        : "No stuck event was observed.") + "\n\n"
                + "Car departures/vehicles: " + events.carDepartures() + "/"
                + events.carVehicles() + "; PT departures/drivers/passenger boardings: "
                + events.ptDepartures() + "/" + events.transitDrivers() + "/"
                + events.ptBoardings() + ".\n";
    }

    private static String stuckCsv(EventAudit events) {
        StringBuilder csv = new StringBuilder(
                "runtime_cohort,main_mode,hour,event_count,unique_persons,all_person_share_percent,resident_person_share_percent,status\n");
        events.stuckGroups().forEach((key, group) -> csv.append(key.cohort()).append(',')
                .append(key.mainMode()).append(',').append(key.hour()).append(',')
                .append(group.events()).append(',').append(group.persons().size()).append(',')
                .append(percent(group.persons().size(), 324_043)).append(',')
                .append(percent(group.persons().stream().filter(person ->
                        ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                                events.cohorts().get(person))).count(), 68_770)).append(',')
                .append(events.stuck().isEmpty() ? "PASS" : "REVIEW_REQUIRED").append('\n'));
        if (events.stuckGroups().isEmpty()) csv.append("ALL,ALL,ALL,0,0,0.000000000,0.000000000,PASS\n");
        return csv.toString();
    }

    private static String modeCsv(PlanAudit plans) {
        StringBuilder csv = new StringBuilder(
                "runtime_cohort,input_main_mode,output_main_mode,trip_count,changed,status\n");
        plans.comparisons().forEach((key, count) -> csv.append(key.cohort()).append(',')
                .append(key.from()).append(',').append(key.to()).append(',').append(count)
                .append(',').append(!key.from().equals(key.to())).append(',')
                .append(key.from().equals(key.to()) ? "PASS" : "FAIL").append('\n'));
        return csv.toString();
    }

    private static String hashCsv(Map<Path, HashAudit> hashes) {
        StringBuilder csv = new StringBuilder("path,before_sha256,after_sha256,status\n");
        hashes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> csv
                .append(entry.getKey().toString().replace('\\', '/')).append(',')
                .append(entry.getValue().before()).append(',')
                .append(entry.getValue().after()).append(',')
                .append(entry.getValue().status()).append('\n'));
        return csv.toString();
    }

    private static Path required(Path file, String purpose) {
        require(Files.isRegularFile(file) && readableSize(file) > 0,
                "Missing or empty " + purpose + ": " + file);
        return file;
    }

    private static long readableSize(Path file) {
        try { return Files.size(file); }
        catch (IOException exception) { throw new IllegalStateException("Unreadable file: " + file, exception); }
    }

    private static String percent(long numerator, long denominator) {
        return String.format(Locale.ROOT, "%.9f", denominator == 0 ? 0.0
                : 100.0 * numerator / denominator);
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    record Expectations(long persons, long residents, long regional, long unresolved,
                        long residentTrips,
                        Map<MunichTripBoundaryFilter.SpatialCategory, Long> spatial) { }
    record Facts(long persons, Map<String, Long> cohorts, long residentTrips,
                 Map<MunichTripBoundaryFilter.SpatialCategory, Long> spatial,
                 boolean analysisComplete, boolean backgroundExcluded, long totalEvents,
                 long carDepartures, long carVehicles, long ptDepartures,
                 long transitDrivers, long ptBoardings, long stuckEvents,
                 long uniqueStuckPersons, long modeChanges) { }
    record ValidationResult(boolean reviewRequired, Facts facts) { }
    record RequiredFiles(Path output, Path events, Path iterationPlans, Path finalPlans,
                         Path outputConfig, Path outputNetwork, Path outputSchedule,
                         Path outputVehicles, Path log, Path iterationAnalysis) { }
    private record InputPerson(String cohort, List<String> modes) { }
    private record ModeKey(String cohort, String from, String to)
            implements Comparable<ModeKey> {
        @Override public int compareTo(ModeKey other) {
            int value = cohort.compareTo(other.cohort);
            if (value == 0) value = from.compareTo(other.from);
            return value == 0 ? to.compareTo(other.to) : value;
        }
    }
    private record PlanAudit(long persons, Map<String, Long> cohorts, long residentTrips,
                             Map<MunichTripBoundaryFilter.SpatialCategory, Long> spatial,
                             long modeChanges, Map<ModeKey, Long> comparisons,
                             Map<String, String> cohortByPerson) { }
    private record AnalysisAudit(boolean complete, boolean backgroundExcluded) { }
    private record CsvKey(String metric, String dimension) { }
    private record HashAudit(String before, String after, String status) { }
    private record StuckRecord(String person, String cohort, String mainMode, int hour) { }
    private record StuckKey(String cohort, String mainMode, int hour)
            implements Comparable<StuckKey> {
        @Override public int compareTo(StuckKey other) {
            int value = cohort.compareTo(other.cohort);
            if (value == 0) value = mainMode.compareTo(other.mainMode);
            return value == 0 ? Integer.compare(hour, other.hour) : value;
        }
    }
    private record StuckGroup(long events, Set<String> persons) { }
    private record EventAudit(long totalEvents, long carDepartures, long carVehicles,
                              long ptDepartures, long transitDrivers, long ptBoardings,
                              List<StuckRecord> stuck, long uniqueStuckPersons,
                              Map<StuckKey, StuckGroup> stuckGroups,
                              Map<String, String> cohorts) { }

    private static final class Counter {
        long persons;
        long residentTrips;
        long modeChanges;
    }

    private static final class MunichTripBoundaryFilterHolder {
        final MunichTripBoundaryFilter filter;
        MunichTripBoundaryFilterHolder() {
            try { filter = new MunichTripBoundaryFilter(MunichMunicipalBoundary.loadDefault()); }
            catch (IOException exception) { throw new IllegalStateException(exception); }
        }
    }

    private static final class EventCollector implements BasicEventHandler,
            PersonDepartureEventHandler, VehicleEntersTrafficEventHandler,
            TransitDriverStartsEventHandler, PersonEntersVehicleEventHandler,
            PersonStuckEventHandler {
        private final Map<String, String> cohorts;
        private final Map<String, String> currentMainMode = new HashMap<>();
        private final Set<String> transitVehicles = new HashSet<>();
        private final Set<String> transitDrivers = new HashSet<>();
        private final List<StuckRecord> stuck = new ArrayList<>();
        private long totalEvents;
        private long carDepartures;
        private long carVehicles;
        private long ptDepartures;
        private long transitDriverEvents;
        private long ptBoardings;

        EventCollector(Map<String, String> cohorts) { this.cohorts = cohorts; }

        @Override public void handleEvent(Event event) { totalEvents++; }

        @Override public void handleEvent(PersonDepartureEvent event) {
            String routing = normalize(event.getRoutingMode(), event.getLegMode());
            currentMainMode.put(event.getPersonId().toString(), routing);
            if ("car".equals(routing)) carDepartures++;
            if ("pt".equals(routing)) ptDepartures++;
        }

        @Override public void handleEvent(VehicleEntersTrafficEvent event) {
            if ("car".equals(normalize(event.getNetworkMode(), ""))) carVehicles++;
        }

        @Override public void handleEvent(TransitDriverStartsEvent event) {
            transitDriverEvents++;
            transitVehicles.add(event.getVehicleId().toString());
            transitDrivers.add(event.getDriverId().toString());
        }

        @Override public void handleEvent(PersonEntersVehicleEvent event) {
            if (transitVehicles.contains(event.getVehicleId().toString())
                    && !transitDrivers.contains(event.getPersonId().toString())) ptBoardings++;
        }

        @Override public void handleEvent(PersonStuckEvent event) {
            String person = event.getPersonId().toString();
            String cohort = cohorts.get(person);
            require(cohort != null, "Stuck event refers to an unknown person: " + person);
            String mainMode = currentMainMode.getOrDefault(person,
                    normalize(event.getLegMode(), "unknown"));
            stuck.add(new StuckRecord(person, cohort, mainMode,
                    (int) Math.floor(event.getTime() / 3600.0)));
        }

        EventAudit result() {
            TreeMap<StuckKey, MutableStuck> groups = new TreeMap<>();
            Set<String> persons = new HashSet<>();
            for (StuckRecord record : stuck) {
                persons.add(record.person());
                groups.computeIfAbsent(new StuckKey(record.cohort(), record.mainMode(),
                        record.hour()), ignored -> new MutableStuck()).add(record.person());
            }
            TreeMap<StuckKey, StuckGroup> immutable = new TreeMap<>();
            groups.forEach((key, value) -> immutable.put(key,
                    new StuckGroup(value.events, Set.copyOf(value.persons))));
            return new EventAudit(totalEvents, carDepartures, carVehicles, ptDepartures,
                    transitDriverEvents, ptBoardings, List.copyOf(stuck), persons.size(),
                    Map.copyOf(immutable), cohorts);
        }

        private static String normalize(String preferred, String fallback) {
            String value = preferred == null || preferred.isBlank() ? fallback : preferred;
            return value == null || value.isBlank() ? "unknown"
                    : value.toLowerCase(Locale.ROOT);
        }
    }

    private static final class MutableStuck {
        long events;
        Set<String> persons = new HashSet<>();
        void add(String person) { events++; persons.add(person); }
    }
}
