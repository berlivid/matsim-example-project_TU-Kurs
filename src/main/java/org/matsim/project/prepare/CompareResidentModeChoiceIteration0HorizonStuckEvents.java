package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only post-run validation and stuck-event comparison for 43h versus 48h. */
public final class CompareResidentModeChoiceIteration0HorizonStuckEvents {
    private static final double OLD_END = 43 * 3_600.0;
    private static final double NEW_END =
            ValidateResidentModeChoiceIteration0Horizon48hConfig.HORIZON_SECONDS;
    private static final double TIME_TOLERANCE_SECONDS = 1e-6;

    private CompareResidentModeChoiceIteration0HorizonStuckEvents() { }

    public static void main(String[] args) throws Exception {
        try {
            require(args.length == 0,
                    "The horizon comparison accepts no arguments");
            Result result = validateCompareAndWrite(null);
            System.out.println(result.reviewRequired()
                    ? "RESIDENT ITERATION-0 HORIZON COMPARISON PASS WITH REVIEW REQUIRED"
                    : "RESIDENT ITERATION-0 HORIZON COMPARISON PASS");
        } catch (Throwable failure) {
            System.err.println("RESIDENT ITERATION-0 HORIZON COMPARISON FAIL");
            if (failure instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    static Result validateCompareAndWrite(Map<Path, String> protectedBefore)
            throws Exception {
        var oldFiles = ValidateResidentModeChoiceIteration0Output.locateRequiredFiles(
                RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT,
                RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID);
        var newFiles = ValidateResidentModeChoiceIteration0Output.locateRequiredFiles(
                ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT,
                ValidateResidentModeChoiceIteration0Horizon48hConfig.RUN_ID);
        ValidateResidentModeChoiceIteration0Output.validateNormalTermination(oldFiles.log());
        ValidateResidentModeChoiceIteration0Output.validateNormalTermination(newFiles.log());
        validateOutputConfigs(oldFiles.outputConfig(), newFiles.outputConfig());

        Cohorts oldCohorts = readCohorts(oldFiles.finalPlans());
        Cohorts newCohorts = readCohorts(newFiles.finalPlans());
        require(oldCohorts.byPerson().equals(newCohorts.byPerson()),
                "Runtime cohort assignments differ between 43h and 48h outputs");
        EventAudit oldEvents = readEvents(oldFiles.events(), oldCohorts.byPerson());
        EventAudit newEvents = readEvents(newFiles.events(), newCohorts.byPerson());
        require(oldEvents.totalEvents() > 0 && newEvents.totalEvents() > 0,
                "An events file contains no readable events");
        require(oldEvents.uniquePersons() == 2_417,
                "Preserved 43h unique stuck-person count changed: "
                        + oldEvents.uniquePersons());
        require(oldEvents.uniqueResidents() == 1_190,
                "Preserved 43h resident stuck-person count changed: "
                        + oldEvents.uniqueResidents());
        require(oldEvents.records().stream().allMatch(record ->
                        hour(record.time()) == 43),
                "The preserved stuck events are no longer all reported in hour 43");

        Comparison comparison = compare(oldEvents.records(), newEvents.records());
        Map<Path, String> protectedAfter =
                ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
        if (protectedBefore != null) {
            require(protectedBefore.equals(protectedAfter),
                    "A protected input changed during the 48-hour test");
        }
        require(!Files.exists(ValidateResidentModeChoiceCalibrationConfig.OUTPUT),
                "The productive 0-20 output was created by the horizon test");

        Path analysis = ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT
                .resolve("analysis");
        Files.createDirectories(analysis);
        Files.writeString(analysis.resolve(
                        "iteration_0_horizon_43h_vs_48h_stuck_events.csv"),
                comparisonCsv(oldEvents.records(), newEvents.records()),
                StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "iteration_0_horizon_43h_vs_48h_summary.csv"),
                summaryCsv(oldEvents, newEvents, comparison), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "iteration_0_horizon_43h_vs_48h_report.md"),
                report(oldEvents, newEvents, comparison), StandardCharsets.UTF_8);
        return new Result(comparison.reviewRequired(), comparison);
    }

    static Comparison compare(List<StuckRecord> oldRecords,
                              List<StuckRecord> newRecords) {
        Set<String> oldPersons = personIds(oldRecords);
        Set<String> newPersons = personIds(newRecords);
        Set<String> persisted = new HashSet<>(oldPersons);
        persisted.retainAll(newPersons);
        boolean oldExactlyAtCutoff = !oldRecords.isEmpty() && oldRecords.stream()
                .allMatch(record -> close(record.time(), OLD_END));
        long newAt48 = newRecords.stream()
                .filter(record -> hour(record.time()) == 48).count();
        long newFinalHour = newRecords.stream()
                .filter(record -> record.time() >= NEW_END - 3_600.0).count();
        CauseAssessment cause;
        if (oldExactlyAtCutoff && newRecords.isEmpty()) {
            cause = CauseAssessment.SUPPORTED_ALL_OLD_EVENTS_DISAPPEARED;
        } else if (oldExactlyAtCutoff && persisted.isEmpty()) {
            cause = CauseAssessment.SUPPORTED_FOR_OLD_EVENTS_NEW_EVENTS_REMAIN;
        } else if (oldExactlyAtCutoff && newRecords.size() < oldRecords.size()) {
            cause = CauseAssessment.PARTIALLY_SUPPORTED;
        } else {
            cause = CauseAssessment.NOT_SUPPORTED;
        }
        boolean review = !newRecords.isEmpty();
        return new Comparison(oldRecords.size(), oldPersons.size(), newRecords.size(),
                newPersons.size(), persisted.size(), oldExactlyAtCutoff, newAt48,
                newFinalHour, cause, review);
    }

    static String comparisonCsv(List<StuckRecord> oldRecords,
                                List<StuckRecord> newRecords) {
        TreeMap<GroupKey, Group> groups = new TreeMap<>();
        addGroups(groups, "43h", oldRecords);
        addGroups(groups, "48h", newRecords);
        StringBuilder csv = new StringBuilder(
                "horizon,runtime_cohort,leg_mode,event_time_seconds,event_time,"
                        + "event_count,unique_persons\n");
        groups.forEach((key, group) -> csv.append(key.horizon()).append(',')
                .append(key.cohort()).append(',').append(key.mode()).append(',')
                .append(number(key.time())).append(',')
                .append(Gtfs2019ScheduleTimePolicy.formatTime(key.time())).append(',')
                .append(group.events()).append(',').append(group.persons().size())
                .append('\n'));
        if (newRecords.isEmpty()) {
            csv.append("48h,ALL,ALL,,,0,0\n");
        }
        return csv.toString();
    }

    private static String summaryCsv(EventAudit oldEvents, EventAudit newEvents,
                                     Comparison comparison) {
        return "metric,old_43h,new_48h,status\n"
                + "stuck_events," + oldEvents.records().size() + ','
                + newEvents.records().size() + ','
                + (newEvents.records().isEmpty() ? "PASS" : "REVIEW_REQUIRED") + "\n"
                + "unique_stuck_persons," + oldEvents.uniquePersons() + ','
                + newEvents.uniquePersons() + ','
                + (newEvents.uniquePersons() == 0 ? "PASS" : "REVIEW_REQUIRED") + "\n"
                + "unique_stuck_residents," + oldEvents.uniqueResidents() + ','
                + newEvents.uniqueResidents() + ','
                + (newEvents.uniqueResidents() == 0 ? "PASS" : "REVIEW_REQUIRED") + "\n"
                + "persisting_old_stuck_persons," + oldEvents.uniquePersons() + ','
                + comparison.persistingOldPersons() + ','
                + (comparison.persistingOldPersons() == 0 ? "PASS" : "REVIEW_REQUIRED")
                + "\nnew_events_in_hour_48,0," + comparison.newEventsInHour48() + ','
                + (comparison.newEventsInHour48() == 0 ? "PASS" : "REVIEW_REQUIRED")
                + "\nnew_events_in_final_hour,0," + comparison.newEventsInFinalHour() + ','
                + (comparison.newEventsInFinalHour() == 0 ? "PASS" : "REVIEW_REQUIRED")
                + "\ncutoff_cause_assessment,," + comparison.cause() + ','
                + (comparison.cause()
                        == CauseAssessment.SUPPORTED_ALL_OLD_EVENTS_DISAPPEARED
                        ? "PASS" : "REVIEW_REQUIRED") + "\n";
    }

    private static String report(EventAudit oldEvents, EventAudit newEvents,
                                 Comparison comparison) {
        String change = newEvents.records().size() < oldEvents.records().size()
                ? "declined" : newEvents.records().size() == oldEvents.records().size()
                ? "did not change" : "increased";
        return "# Resident iteration-0 QSim-horizon comparison\n\n"
                + "This read-only comparison evaluates the preserved 43-hour run against "
                + "the isolated 48-hour iteration-0 technical test. It does not calibrate a "
                + "behavioral parameter.\n\n"
                + "| Measure | 43h | 48h |\n|---|---:|---:|\n"
                + "| Stuck events | " + oldEvents.records().size() + " | "
                + newEvents.records().size() + " |\n"
                + "| Unique persons | " + oldEvents.uniquePersons() + " | "
                + newEvents.uniquePersons() + " |\n"
                + "| Unique Munich residents | " + oldEvents.uniqueResidents() + " | "
                + newEvents.uniqueResidents() + " |\n"
                + "| Old affected persons still stuck | -- | "
                + comparison.persistingOldPersons() + " |\n"
                + "| Events in hour 48 | -- | " + comparison.newEventsInHour48()
                + " |\n\n"
                + "The stuck-event count " + change + " at 48 hours. Cutoff-cause "
                + "assessment: `" + comparison.cause() + "`. "
                + (comparison.newEventsInHour48() > 0
                        ? "Events occur in hour 48, so some failures may have moved to the new cutoff."
                        : "No event moved into hour 48.")
                + "\n\nExact event times by runtime cohort and leg mode are listed in "
                + "`iteration_0_horizon_43h_vs_48h_stuck_events.csv`. The 48-hour "
                + "horizon is suitable for later productive adoption only if all 2,417 old "
                + "cutoff events disappear, no new or persisting stuck event remains, no "
                + "event moves to the 48-hour boundary, technical validation passes and all "
                + "protected inputs remain unchanged. Any nonzero 48-hour result requires "
                + "review and does not automatically authorize Run 12.\n";
    }

    private static void validateOutputConfigs(Path oldFile, Path newFile)
            throws Exception {
        Config oldConfig = ConfigUtils.loadConfig(oldFile.toString());
        require(oldConfig.qsim().getEndTime().isDefined()
                        && oldConfig.qsim().getEndTime().seconds() == OLD_END,
                "Preserved output config no longer records 43:00:00");
        require(oldConfig.controller().getLastIteration() == 0,
                "Preserved output config is not iteration 0");
        Config expected = ValidateResidentModeChoiceIteration0Horizon48hConfig
                .expectedConfigWithoutOutputChecks();
        Config actual = ConfigUtils.loadConfig(newFile.toString());
        ResidentOutputConfigSemanticComparison.requireEquivalent(expected, actual);
        require(actual.qsim().getEndTime().isDefined()
                        && actual.qsim().getEndTime().seconds() == NEW_END,
                "48-hour output config does not record 48:00:00");
        require(actual.controller().getLastIteration() == 0,
                "48-hour output config does not record lastIteration=0");
    }

    private static Cohorts readCohorts(Path plans) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TreeMap<String, String> byPerson = new TreeMap<>();
        TreeMap<String, Long> counts = new TreeMap<>();
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            String cohort = PopulationUtils.getSubpopulation(person);
            require(Set.of(ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                            ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
                            ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND)
                            .contains(cohort),
                    "Missing or unexpected runtime cohort for " + person.getId());
            require(byPerson.put(person.getId().toString(), cohort) == null,
                    "Duplicate person in output plans: " + person.getId());
            counts.merge(cohort, 1L, Long::sum);
        });
        reader.readFile(plans.toString());
        require(byPerson.size() == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                "Output person count changed: " + byPerson.size());
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.MUNICH_RESIDENT, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                "Output Munich-resident count changed");
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                "Output regional-background count changed");
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                "Output unresolved-background count changed");
        return new Cohorts(Map.copyOf(byPerson), Map.copyOf(counts));
    }

    private static EventAudit readEvents(Path events, Map<String, String> cohorts) {
        EventCollector collector = new EventCollector(cohorts);
        EventsManager manager = EventsUtils.createEventsManager();
        manager.addHandler(collector);
        new MatsimEventsReader(manager).readFile(events.toString());
        return collector.result();
    }

    private static void addGroups(Map<GroupKey, Group> groups, String horizon,
                                  List<StuckRecord> records) {
        records.forEach(record -> groups.computeIfAbsent(
                        new GroupKey(horizon, record.cohort(), record.mode(), record.time()),
                        ignored -> new Group())
                .add(record.personId()));
    }

    private static Set<String> personIds(List<StuckRecord> records) {
        Set<String> result = new HashSet<>();
        records.forEach(record -> result.add(record.personId()));
        return result;
    }

    private static int hour(double time) {
        return (int) Math.floor(time / 3_600.0);
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= TIME_TOLERANCE_SECONDS;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String mode(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    enum CauseAssessment {
        SUPPORTED_ALL_OLD_EVENTS_DISAPPEARED,
        SUPPORTED_FOR_OLD_EVENTS_NEW_EVENTS_REMAIN,
        PARTIALLY_SUPPORTED,
        NOT_SUPPORTED
    }

    record StuckRecord(String personId, String cohort, String mode, double time) { }
    record Comparison(long oldEvents, long oldPersons, long newEvents, long newPersons,
                      long persistingOldPersons, boolean oldExactlyAtCutoff,
                      long newEventsInHour48, long newEventsInFinalHour,
                      CauseAssessment cause, boolean reviewRequired) { }
    record Result(boolean reviewRequired, Comparison comparison) { }
    private record Cohorts(Map<String, String> byPerson, Map<String, Long> counts) { }
    private record EventAudit(long totalEvents, List<StuckRecord> records,
                              long uniquePersons, long uniqueResidents) { }
    private record GroupKey(String horizon, String cohort, String mode, double time)
            implements Comparable<GroupKey> {
        @Override public int compareTo(GroupKey other) {
            return Comparator.comparing(GroupKey::horizon)
                    .thenComparing(GroupKey::cohort)
                    .thenComparing(GroupKey::mode)
                    .thenComparingDouble(GroupKey::time).compare(this, other);
        }
    }

    private static final class Group {
        private long events;
        private final Set<String> persons = new HashSet<>();
        void add(String person) { events++; persons.add(person); }
        long events() { return events; }
        Set<String> persons() { return persons; }
    }

    private static final class EventCollector
            implements BasicEventHandler, PersonStuckEventHandler {
        private final Map<String, String> cohorts;
        private final List<StuckRecord> records = new ArrayList<>();
        private long totalEvents;

        EventCollector(Map<String, String> cohorts) { this.cohorts = cohorts; }

        @Override public void handleEvent(Event event) { totalEvents++; }

        @Override public void handleEvent(PersonStuckEvent event) {
            String person = event.getPersonId().toString();
            String cohort = cohorts.get(person);
            require(cohort != null, "Stuck person is absent from final plans: " + person);
            records.add(new StuckRecord(person, cohort, mode(event.getLegMode()),
                    event.getTime()));
        }

        EventAudit result() {
            Set<String> persons = personIds(records);
            long residents = records.stream()
                    .filter(record -> ResidentCalibrationSubpopulations.MUNICH_RESIDENT
                            .equals(record.cohort()))
                    .map(StuckRecord::personId).distinct().count();
            return new EventAudit(totalEvents, List.copyOf(records), persons.size(), residents);
        }
    }
}
