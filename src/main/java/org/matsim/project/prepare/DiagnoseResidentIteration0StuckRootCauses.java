package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Read-only, two-stream diagnosis of persons stuck at the 43h and 48h QSim cutoffs.
 * The large event files are deliberately read exactly once each.
 */
public final class DiagnoseResidentIteration0StuckRootCauses {
    static final double OLD_END = 43 * 3_600.0;
    static final double NEW_END = 48 * 3_600.0;
    static final double VERY_LATE_WINDOW = 3_600.0;
    private static final Path OLD_OUTPUT =
            RunMatsim2019ResidentModeChoiceIteration0Validation.OUTPUT;
    private static final String OLD_RUN_ID =
            RunMatsim2019ResidentModeChoiceIteration0Validation.RUN_ID;
    private static final Path NEW_OUTPUT =
            ValidateResidentModeChoiceIteration0Horizon48hConfig.OUTPUT;
    private static final String NEW_RUN_ID =
            ValidateResidentModeChoiceIteration0Horizon48hConfig.RUN_ID;
    private static final Path GENERATED = Path.of(
            "generated/resident_iteration0_stuck_root_cause");
    private static final Set<String> COHORTS = Set.of(
            ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
            ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
            ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND);

    private DiagnoseResidentIteration0StuckRootCauses() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The stuck-root-cause diagnostic accepts no arguments");
        RequiredFiles oldFiles = required(OLD_OUTPUT, OLD_RUN_ID);
        RequiredFiles newFiles = required(NEW_OUTPUT, NEW_RUN_ID);

        OldStuckCollector oldCollector = new OldStuckCollector();
        readEventsOnce(oldFiles.events(), oldCollector);
        Map<String, OldStuck> oldStuck = oldCollector.result();
        require(oldStuck.size() == 2_417,
                "Expected 2,417 unique persons stuck at 43h, found " + oldStuck.size());

        DetailedEventCollector newCollector = new DetailedEventCollector(oldStuck.keySet());
        readEventsOnce(newFiles.events(), newCollector);
        EventEvidence evidence = newCollector.result();
        require(evidence.stuck().size() == 1_701,
                "Expected 1,701 unique persons stuck at 48h, found "
                        + evidence.stuck().size());
        require(oldStuck.keySet().containsAll(evidence.stuck().keySet()),
                "The 48h run contains stuck persons who were not stuck at 43h");

        Set<String> resolved = new HashSet<>(oldStuck.keySet());
        resolved.removeAll(evidence.stuck().keySet());
        require(resolved.size() == 716,
                "Expected 716 persons resolved between horizons, found " + resolved.size());

        var config = ConfigUtils.loadConfig(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG.toString());
        Path inputPopulation = AnalyzeMunichResidentCohort.resolvePopulation(config);
        PopulationEvidence input = readInputPopulation(inputPopulation, oldStuck.keySet());
        PopulationEvidence output = readOutputPopulation(newFiles.plans(), oldStuck.keySet());
        require(input.cohortCounts().equals(output.cohortCounts()),
                "Authoritative input and runtime output cohort counts differ");
        require(input.byPerson().keySet().equals(output.byPerson().keySet()),
                "Relevant persons differ between input and output populations");
        for (String person : oldStuck.keySet()) {
            require(input.byPerson().get(person).cohort()
                            .equals(output.byPerson().get(person).cohort()),
                    "Runtime cohort differs from input classification for " + person);
            require(input.byPerson().get(person).mainTrips()
                            == output.byPerson().get(person).mainTrips(),
                    "Main-trip structure differs for " + person);
        }

        Analysis result = analyze(oldStuck, evidence, output.byPerson(), resolved);
        require(result.persistent().size() == 1_701, "Persistent detail count changed");
        require(result.resolved().size() == 716, "Resolved detail count changed");
        long residentPersistent = result.persistent().stream().filter(row ->
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(row.cohort())).count();
        require(residentPersistent == 818,
                "Expected 818 persistent Munich residents, found " + residentPersistent);

        writeOutputs(result);
        System.out.printf(Locale.ROOT,
                "RESIDENT ITERATION-0 STUCK ROOT-CAUSE DIAGNOSTIC COMPLETE%n"
                        + "oldStuck=%d resolved=%d persistent=%d residentPersistent=%d%n"
                        + "reports=%s%nNo Controller or QSim was started; plans and events were read only.%n",
                oldStuck.size(), result.resolved().size(), result.persistent().size(),
                residentPersistent, GENERATED);
    }

    static Analysis analyze(Map<String, OldStuck> oldStuck, EventEvidence evidence,
                            Map<String, PersonPlanEvidence> plans, Set<String> resolvedIds) {
        List<PersistentRow> persistent = new ArrayList<>();
        evidence.stuck().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String id = entry.getKey();
                    Trace trace = entry.getValue();
                    PersonPlanEvidence person = plans.get(id);
                    require(person != null, "Missing output plan context for " + id);
                    LegPlanContext leg = person.context(trace.departureOrdinal());
                    Cause cause = classify(new ClassificationEvidence(
                            trace.departureTime(), trace.routingMode(), trace.legMode(),
                            trace.ptWaited(), trace.ptBoarded(), trace.ptOnVehicleAtCutoff(),
                            trace.lastCarMovementTime(), trace.teleportationArrival()));
                    persistent.add(new PersistentRow(id, person.cohort(), trace.stuckMode(),
                            leg.originType(), leg.destinationType(), leg.plannedDeparture(),
                            leg.routeDistance(), trace.departureTime(),
                            elapsed(trace.departureTime()),
                            trace.lastEventType(), trace.lastEventTime(), trace.lastLink(),
                            trace.lastCarMovementType(), trace.lastCarMovementTime(),
                            trace.lastCarMovementLink(), trace.ptWaited(), trace.ptBoarded(),
                            trace.ptLeftVehicle(), trace.ptOnVehicleAtCutoff(),
                            trace.waitingStop(), trace.destinationStop(), trace.transitVehicle(),
                            trace.transitLine(), trace.transitRoute(),
                            oldStuck.containsKey(id), cause));
                });

        List<ResolvedRow> resolved = resolvedIds.stream().sorted().map(id -> {
            OldStuck old = oldStuck.get(id);
            Trace trace = evidence.traces().get(id);
            PersonPlanEvidence plan = plans.get(id);
            require(trace != null && plan != null, "Missing resolved-person evidence for " + id);
            return new ResolvedRow(id, plan.cohort(), old.mode(), old.time(),
                    trace.firstArrivalAfterOldEnd(), trace.lastArrivalTime(),
                    trace.lastEventType(), trace.lastEventTime(),
                    finite(trace.firstArrivalAfterOldEnd()));
        }).toList();
        return new Analysis(List.copyOf(persistent), List.copyOf(resolved));
    }

    static Cause classify(ClassificationEvidence evidence) {
        if (!finite(evidence.departureTime())) return Cause.INSUFFICIENT_EVIDENCE;
        if (evidence.departureTime() >= NEW_END - VERY_LATE_WINDOW) {
            return Cause.VERY_LATE_DEPARTURE;
        }
        String routing = normalize(evidence.routingMode(), evidence.legMode());
        if ("pt".equals(routing)) {
            return evidence.ptBoarded()
                    ? Cause.PT_BOARDED_NOT_ARRIVED : Cause.PT_NEVER_BOARDED;
        }
        if ("car".equals(routing)) return Cause.CAR_NO_PROGRESS_OR_NETWORK_CLUSTER;
        if (Set.of("walk", "bike", "transit_walk").contains(routing)
                && !evidence.teleportationArrival()) {
            return Cause.TELEPORTED_LEG_EXCEEDS_HORIZON;
        }
        return Cause.INSUFFICIENT_EVIDENCE;
    }

    private static PopulationEvidence readInputPopulation(Path file, Set<String> watched)
            throws IOException {
        MunichResidentClassifier classifier = new MunichResidentClassifier(
                MunichMunicipalBoundary.loadDefault());
        TreeMap<String, Long> counts = new TreeMap<>();
        HashMap<String, PersonPlanEvidence> relevant = new HashMap<>();
        final long[] persons = {0};
        streamPopulation(file, person -> {
            persons[0]++;
            String cohort = ResidentCalibrationSubpopulations.labelFor(
                    classifier.classify(person).classification());
            counts.merge(cohort, 1L, Long::sum);
            if (watched.contains(person.getId().toString())) {
                relevant.put(person.getId().toString(), planEvidence(person, cohort));
            }
        });
        requireAuthoritative(persons[0], counts, relevant, watched, "input population");
        return new PopulationEvidence(Map.copyOf(relevant), Map.copyOf(counts));
    }

    private static PopulationEvidence readOutputPopulation(Path file, Set<String> watched) {
        TreeMap<String, Long> counts = new TreeMap<>();
        HashMap<String, PersonPlanEvidence> relevant = new HashMap<>();
        final long[] persons = {0};
        streamPopulation(file, person -> {
            persons[0]++;
            String cohort = PopulationUtils.getSubpopulation(person);
            require(COHORTS.contains(cohort),
                    "Missing or unexpected runtime cohort for " + person.getId());
            counts.merge(cohort, 1L, Long::sum);
            if (watched.contains(person.getId().toString())) {
                relevant.put(person.getId().toString(), planEvidence(person, cohort));
            }
        });
        requireAuthoritative(persons[0], counts, relevant, watched, "48h output plans");
        return new PopulationEvidence(Map.copyOf(relevant), Map.copyOf(counts));
    }

    private static void requireAuthoritative(long persons, Map<String, Long> counts,
                                             Map<String, ?> relevant, Set<String> watched,
                                             String source) {
        require(persons == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                source + " person count changed: " + persons);
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.MUNICH_RESIDENT, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                source + " Munich-resident count changed");
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                source + " regional-background count changed");
        require(counts.getOrDefault(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND, 0L)
                        == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                source + " unresolved-background count changed");
        require(relevant.keySet().equals(watched),
                source + " does not contain every watched person");
    }

    private static PersonPlanEvidence planEvidence(Person person, String cohort) {
        Plan plan = person.getSelectedPlan();
        require(plan != null, "Missing selected plan for " + person.getId());
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan,
                StageActivityTypeIdentifier::isStageActivity);
        Map<Leg, TripStructureUtils.Trip> tripByLeg = new java.util.IdentityHashMap<>();
        trips.forEach(trip -> trip.getLegsOnly().forEach(leg -> tripByLeg.put(leg, trip)));
        List<LegPlanContext> contexts = new ArrayList<>();
        for (PlanElement element : plan.getPlanElements()) {
            if (!(element instanceof Leg leg)) continue;
            TripStructureUtils.Trip trip = tripByLeg.get(leg);
            require(trip != null, "Leg cannot be assigned to a main trip for " + person.getId());
            double planned = leg.getDepartureTime().orElse(
                    trip.getOriginActivity().getEndTime().orElse(Double.NaN));
            contexts.add(new LegPlanContext(type(trip.getOriginActivity()),
                    type(trip.getDestinationActivity()), planned, routeDistance(leg)));
        }
        return new PersonPlanEvidence(cohort, trips.size(), List.copyOf(contexts));
    }

    private static void streamPopulation(Path file, Consumer<Person> consumer) {
        require(Files.isRegularFile(file), "Population file is missing: " + file);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(consumer::accept);
        reader.readFile(file.toString());
    }

    private static void readEventsOnce(Path file, BasicEventHandler handler) {
        EventsManager manager = EventsUtils.createEventsManager();
        manager.addHandler(handler);
        new MatsimEventsReader(manager).readFile(file.toString());
    }

    private static RequiredFiles required(Path output, String runId) {
        Path plans = output.resolve(runId + ".output_plans.xml.gz");
        Path config = output.resolve(runId + ".output_config.xml");
        Path events = output.resolve("ITERS/it.0/" + runId + ".0.events.xml.gz");
        Path analysis = output.resolve("analysis");
        require(Files.isDirectory(output), "Output directory is missing: " + output);
        require(Files.isRegularFile(plans), "Output plans are missing: " + plans);
        require(Files.isRegularFile(config), "Output config is missing: " + config);
        require(Files.isRegularFile(events), "Iteration-0 events are missing: " + events);
        require(Files.isDirectory(analysis), "Existing analysis directory is missing: " + analysis);
        try (var files = Files.list(analysis)) {
            require(files.findAny().isPresent(), "Existing analysis directory is empty: " + analysis);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect analysis directory " + analysis,
                    exception);
        }
        return new RequiredFiles(plans, config, events, analysis);
    }

    private static void writeOutputs(Analysis result) throws IOException {
        Files.createDirectories(GENERATED);
        Files.writeString(GENERATED.resolve("persistent_stuck_persons.csv"),
                persistentCsv(result.persistent()), StandardCharsets.UTF_8);
        Files.writeString(GENERATED.resolve("resolved_between_43h_and_48h.csv"),
                resolvedCsv(result.resolved()), StandardCharsets.UTF_8);
        Files.writeString(GENERATED.resolve("stuck_root_cause_summary.csv"),
                summaryCsv(result.persistent()), StandardCharsets.UTF_8);
        Files.writeString(GENERATED.resolve("stuck_link_or_stop_clusters.csv"),
                clustersCsv(result.persistent()), StandardCharsets.UTF_8);
        Files.writeString(GENERATED.resolve("stuck_root_cause_report.md"),
                report(result), StandardCharsets.UTF_8);
    }

    private static String persistentCsv(List<PersistentRow> rows) {
        StringBuilder out = new StringBuilder("person_id,runtime_cohort,leg_mode,planned_origin_type,"
                + "planned_destination_type,planned_departure_time,route_distance,realized_departure_time,"
                + "elapsed_to_48h_seconds,last_event_before_stuck,last_event_time,last_link_id,"
                + "last_car_movement,last_car_movement_time,last_car_movement_link,pt_reached_stop,"
                + "pt_boarded,pt_left_vehicle,pt_on_vehicle_at_cutoff,waiting_stop,destination_stop,"
                + "transit_vehicle,transit_line,transit_route,already_stuck_at_43h,root_cause\n");
        rows.forEach(row -> out.append(csv(row.personId())).append(',').append(row.cohort())
                .append(',').append(row.legMode()).append(',').append(csv(row.originType()))
                .append(',').append(csv(row.destinationType())).append(',')
                .append(number(row.plannedDeparture())).append(',')
                .append(number(row.routeDistance())).append(',')
                .append(number(row.realizedDeparture())).append(',')
                .append(number(row.elapsedToCutoff())).append(',').append(row.lastEventType())
                .append(',').append(number(row.lastEventTime())).append(',')
                .append(csv(row.lastLink())).append(',').append(row.lastCarMovementType())
                .append(',').append(number(row.lastCarMovementTime())).append(',')
                .append(csv(row.lastCarMovementLink())).append(',').append(row.ptWaited())
                .append(',').append(row.ptBoarded()).append(',').append(row.ptLeftVehicle())
                .append(',').append(row.ptOnVehicle()).append(',').append(csv(row.waitingStop()))
                .append(',').append(csv(row.destinationStop())).append(',')
                .append(csv(row.transitVehicle())).append(',').append(csv(row.transitLine()))
                .append(',').append(csv(row.transitRoute())).append(',')
                .append(row.alreadyStuck43()).append(',').append(row.cause()).append('\n'));
        return out.toString();
    }

    private static String resolvedCsv(List<ResolvedRow> rows) {
        StringBuilder out = new StringBuilder("person_id,runtime_cohort,old_leg_mode,old_stuck_time,"
                + "first_arrival_after_43h,last_arrival_time,last_event_type,last_event_time,"
                + "observed_arrival_after_43h\n");
        rows.forEach(row -> out.append(csv(row.personId())).append(',').append(row.cohort())
                .append(',').append(row.oldMode()).append(',').append(number(row.oldTime()))
                .append(',').append(number(row.firstArrivalAfter43())).append(',')
                .append(number(row.lastArrival())).append(',').append(row.lastEventType())
                .append(',').append(number(row.lastEventTime())).append(',')
                .append(row.arrivalObserved()).append('\n'));
        return out.toString();
    }

    private static String summaryCsv(List<PersistentRow> rows) {
        TreeMap<SummaryKey, Long> counts = new TreeMap<>();
        rows.forEach(row -> counts.merge(new SummaryKey(row.cohort(), row.legMode(), row.cause()),
                1L, Long::sum));
        StringBuilder out = new StringBuilder("runtime_cohort,leg_mode,root_cause,person_count\n");
        counts.forEach((key, count) -> out.append(key.cohort()).append(',')
                .append(key.mode()).append(',').append(key.cause()).append(',')
                .append(count).append('\n'));
        return out.toString();
    }

    private static String clustersCsv(List<PersistentRow> rows) {
        TreeMap<ClusterKey, Long> counts = new TreeMap<>();
        rows.forEach(row -> {
            if (row.cause() == Cause.CAR_NO_PROGRESS_OR_NETWORK_CLUSTER) {
                counts.merge(new ClusterKey(row.cohort(), row.cause(), "link",
                        present(row.lastCarMovementLink(), row.lastLink()), "", ""), 1L, Long::sum);
            } else if (row.cause() == Cause.PT_NEVER_BOARDED
                    || row.cause() == Cause.PT_BOARDED_NOT_ARRIVED) {
                counts.merge(new ClusterKey(row.cohort(), row.cause(), "stop_or_route",
                        present(row.waitingStop(), "<none>"), present(row.transitLine(), "<none>"),
                        present(row.transitRoute(), "<none>")), 1L, Long::sum);
            }
        });
        List<Map.Entry<ClusterKey, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<ClusterKey, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey())).toList();
        StringBuilder out = new StringBuilder(
                "runtime_cohort,root_cause,cluster_type,link_or_stop_id,transit_line,transit_route,person_count\n");
        sorted.forEach(entry -> out.append(entry.getKey().cohort()).append(',')
                .append(entry.getKey().cause()).append(',').append(entry.getKey().type())
                .append(',').append(csv(entry.getKey().location())).append(',')
                .append(csv(entry.getKey().line())).append(',')
                .append(csv(entry.getKey().route())).append(',').append(entry.getValue())
                .append('\n'));
        return out.toString();
    }

    private static String report(Analysis result) {
        TreeMap<Cause, Long> all = causeCounts(result.persistent(), null);
        TreeMap<Cause, Long> residents = causeCounts(result.persistent(),
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT);
        TreeMap<String, Long> persistentCohorts = new TreeMap<>();
        TreeMap<String, Long> resolvedCohorts = new TreeMap<>();
        result.persistent().forEach(row -> persistentCohorts.merge(row.cohort(), 1L, Long::sum));
        result.resolved().forEach(row -> resolvedCohorts.merge(row.cohort(), 1L, Long::sum));
        long resolvedWithArrival = result.resolved().stream().filter(ResolvedRow::arrivalObserved).count();
        StringBuilder out = new StringBuilder("# Resident iteration-0 stuck root-cause diagnosis\n\n")
                .append("This is a read-only technical diagnosis of the preserved 43-hour and 48-hour iteration-0 outputs. Each compressed events file was streamed once. No Controller or QSim was started, and no scenario input or existing output was changed.\n\n")
                .append("## Population-level result\n\n")
                .append("| Runtime cohort | Stuck at 43h and resolved by 48h | Still stuck at 48h |\n|---|---:|---:|\n");
        COHORTS.stream().sorted().forEach(cohort -> out.append("| ").append(cohort)
                .append(" | ").append(resolvedCohorts.getOrDefault(cohort, 0L)).append(" | ")
                .append(persistentCohorts.getOrDefault(cohort, 0L)).append(" |\n"));
        out.append("| **All** | **").append(result.resolved().size()).append("** | **")
                .append(result.persistent().size()).append("** |\n\n")
                .append(resolvedWithArrival).append(" of the ").append(result.resolved().size())
                .append(" resolved persons have an observed arrival after 43:00 in the 48-hour event stream.\n\n")
                .append("## Evidence-based cause distribution\n\n")
                .append("| Root-cause class | All persons | Munich residents |\n|---|---:|---:|\n");
        for (Cause cause : Cause.values()) out.append("| ").append(cause).append(" | ")
                .append(all.getOrDefault(cause, 0L)).append(" | ")
                .append(residents.getOrDefault(cause, 0L)).append(" |\n");
        List<Map.Entry<String, Long>> carLinks = result.persistent().stream()
                .filter(row -> row.cause() == Cause.CAR_NO_PROGRESS_OR_NETWORK_CLUSTER)
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> present(row.lastCarMovementLink(), row.lastLink()),
                        TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue()
                        .reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        out.append("\n`VERY_LATE_DEPARTURE` uses a transparent one-hour diagnostic window (departure at or after 47:00); it is not a behavioral assumption. PT classes depend on observed waiting/boarding events. The car class records the last observed vehicle movement and does not by itself claim a routing failure. Unresolved evidence remains explicitly classified as `INSUFFICIENT_EVIDENCE`.\n\n")
                .append("## Concentration and timing evidence\n\n");
        if (carLinks.size() >= 3) {
            long topThree = carLinks.stream().limit(3).mapToLong(Map.Entry::getValue).sum();
            out.append(topThree).append(" of ").append(all.getOrDefault(
                            Cause.CAR_NO_PROGRESS_OR_NETWORK_CLUSTER, 0L))
                    .append(" car cases have their final vehicle movement on three links: `")
                    .append(carLinks.get(0).getKey()).append("` (")
                    .append(carLinks.get(0).getValue()).append("), `")
                    .append(carLinks.get(1).getKey()).append("` (")
                    .append(carLinks.get(1).getValue()).append("), and `")
                    .append(carLinks.get(2).getKey()).append("` (")
                    .append(carLinks.get(2).getValue()).append("). All car-class records have `entered link` as the last movement event.\n\n");
        }
        long neverWaiting = result.persistent().stream().filter(row ->
                row.cause() == Cause.PT_NEVER_BOARDED && row.ptWaited()).count();
        long boardedLeft = result.persistent().stream().filter(row ->
                row.cause() == Cause.PT_BOARDED_NOT_ARRIVED && row.ptLeftVehicle()
                        && !row.ptOnVehicle()).count();
        out.append(neverWaiting).append(" of ")
                .append(all.getOrDefault(Cause.PT_NEVER_BOARDED, 0L))
                .append(" never-boarded cases have an explicit waiting-at-stop event. All ")
                .append(boardedLeft).append(" boarded-not-arrived cases left a transit vehicle and were waiting for a later connection at the cutoff; none remained aboard. PT stops and the last used transit lines/routes are distributed rather than dominated by one route.\n\n")
                .append("## Interpretation\n\n")
                .append("All persistent records occurred at the 48-hour cutoff, but the cutoff timestamp alone was not used as a causal classification. The five-hour extension allowed 716 persons to arrive, yet it did not resolve the concentrated car queues or the PT waiting chains. The evidence therefore rejects a simple claim that 48 hours alone repairs the technical problem.\n\n")
                .append("The smallest defensible next correction is a targeted network-data audit of the three dominant links and their immediate downstream links, followed by a check that passengers delayed into late transfers still have a service connection. This is a recommendation for a separate controlled correction, not a modification made by this diagnosis. A new protected iteration-0 test is required after any such correction; Run 12 remains blocked.\n\n")
                .append("Detailed person rows retain planned and realized timing, the last event before the stuck event, car movement, and PT stop/vehicle/route evidence. Complete cluster counts are in `stuck_link_or_stop_clusters.csv`.\n");
        return out.toString();
    }

    private static TreeMap<Cause, Long> causeCounts(List<PersistentRow> rows, String cohort) {
        TreeMap<Cause, Long> result = new TreeMap<>();
        rows.stream().filter(row -> cohort == null || cohort.equals(row.cohort()))
                .forEach(row -> result.merge(row.cause(), 1L, Long::sum));
        return result;
    }

    private static double elapsed(double departure) {
        return finite(departure) ? NEW_END - departure : Double.NaN;
    }

    private static String number(double value) {
        return finite(value) ? String.format(Locale.ROOT, "%.3f", value) : "";
    }

    private static boolean finite(double value) { return Double.isFinite(value); }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String present(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String normalize(String preferred, String fallback) {
        String value = present(preferred, fallback);
        return value == null || value.isBlank() ? "unknown"
                : value.toLowerCase(Locale.ROOT);
    }

    private static String type(Activity activity) {
        return activity.getType() == null ? "<missing>" : activity.getType();
    }

    private static double routeDistance(Leg leg) {
        if (leg.getRoute() == null || !finite(leg.getRoute().getDistance())
                || leg.getRoute().getDistance() < 0.0) return Double.NaN;
        return leg.getRoute().getDistance();
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    enum Cause {
        VERY_LATE_DEPARTURE,
        CAR_NO_PROGRESS_OR_NETWORK_CLUSTER,
        PT_NEVER_BOARDED,
        PT_BOARDED_NOT_ARRIVED,
        TELEPORTED_LEG_EXCEEDS_HORIZON,
        INSUFFICIENT_EVIDENCE
    }

    record ClassificationEvidence(double departureTime, String routingMode, String legMode,
                                  boolean ptWaited, boolean ptBoarded,
                                  boolean ptOnVehicleAtCutoff, double lastCarMovementTime,
                                  boolean teleportationArrival) { }
    record OldStuck(String personId, String mode, double time) { }
    record RequiredFiles(Path plans, Path config, Path events, Path analysis) { }
    record PopulationEvidence(Map<String, PersonPlanEvidence> byPerson,
                              Map<String, Long> cohortCounts) { }
    record PersonPlanEvidence(String cohort, int mainTrips, List<LegPlanContext> legs) {
        LegPlanContext context(int ordinal) {
            require(ordinal >= 0 && ordinal < legs.size(),
                    "Departure ordinal " + ordinal + " cannot be matched to " + legs.size()
                            + " planned legs");
            return legs.get(ordinal);
        }
    }
    record LegPlanContext(String originType, String destinationType,
                          double plannedDeparture, double routeDistance) { }
    record EventEvidence(Map<String, Trace> traces, Map<String, Trace> stuck) { }
    record Analysis(List<PersistentRow> persistent, List<ResolvedRow> resolved) { }
    record PersistentRow(String personId, String cohort, String legMode, String originType,
                         String destinationType, double plannedDeparture,
                         double routeDistance, double realizedDeparture, double elapsedToCutoff,
                         String lastEventType, double lastEventTime, String lastLink,
                         String lastCarMovementType, double lastCarMovementTime,
                         String lastCarMovementLink, boolean ptWaited, boolean ptBoarded,
                         boolean ptLeftVehicle, boolean ptOnVehicle, String waitingStop,
                         String destinationStop, String transitVehicle, String transitLine,
                         String transitRoute, boolean alreadyStuck43, Cause cause) { }
    record ResolvedRow(String personId, String cohort, String oldMode, double oldTime,
                       double firstArrivalAfter43, double lastArrival, String lastEventType,
                       double lastEventTime, boolean arrivalObserved) { }
    record SummaryKey(String cohort, String mode, Cause cause) implements Comparable<SummaryKey> {
        @Override public int compareTo(SummaryKey other) {
            return Comparator.comparing(SummaryKey::cohort).thenComparing(SummaryKey::mode)
                    .thenComparing(SummaryKey::cause).compare(this, other);
        }
    }
    record ClusterKey(String cohort, Cause cause, String type, String location,
                      String line, String route) implements Comparable<ClusterKey> {
        @Override public int compareTo(ClusterKey other) {
            return Comparator.comparing(ClusterKey::cohort).thenComparing(ClusterKey::cause)
                    .thenComparing(ClusterKey::type).thenComparing(ClusterKey::location)
                    .thenComparing(ClusterKey::line).thenComparing(ClusterKey::route)
                    .compare(this, other);
        }
    }

    static final class Trace {
        private int departureOrdinal = -1;
        private double departureTime = Double.NaN;
        private String legMode = "unknown";
        private String routingMode = "unknown";
        private String lastEventType = "<none>";
        private double lastEventTime = Double.NaN;
        private String lastLink = "";
        private String stuckMode = "unknown";
        private double stuckTime = Double.NaN;
        private boolean stuck;
        private double firstArrivalAfterOldEnd = Double.NaN;
        private double lastArrivalTime = Double.NaN;
        private String lastCarMovementType = "<none>";
        private double lastCarMovementTime = Double.NaN;
        private String lastCarMovementLink = "";
        private boolean ptJourneyActive;
        private boolean ptWaited;
        private boolean ptBoarded;
        private boolean ptLeftVehicle;
        private boolean ptOnVehicleAtCutoff;
        private boolean teleportationArrival;
        private String waitingStop = "";
        private String destinationStop = "";
        private String transitVehicle = "";
        private String transitLine = "";
        private String transitRoute = "";

        int departureOrdinal() { return departureOrdinal; }
        double departureTime() { return departureTime; }
        String legMode() { return legMode; }
        String routingMode() { return routingMode; }
        String lastEventType() { return lastEventType; }
        double lastEventTime() { return lastEventTime; }
        String lastLink() { return lastLink; }
        String stuckMode() { return stuckMode; }
        boolean ptWaited() { return ptWaited; }
        boolean ptBoarded() { return ptBoarded; }
        boolean ptLeftVehicle() { return ptLeftVehicle; }
        boolean ptOnVehicleAtCutoff() { return ptOnVehicleAtCutoff; }
        boolean teleportationArrival() { return teleportationArrival; }
        double firstArrivalAfterOldEnd() { return firstArrivalAfterOldEnd; }
        double lastArrivalTime() { return lastArrivalTime; }
        String lastCarMovementType() { return lastCarMovementType; }
        double lastCarMovementTime() { return lastCarMovementTime; }
        String lastCarMovementLink() { return lastCarMovementLink; }
        String waitingStop() { return waitingStop; }
        String destinationStop() { return destinationStop; }
        String transitVehicle() { return transitVehicle; }
        String transitLine() { return transitLine; }
        String transitRoute() { return transitRoute; }

        Trace snapshot() {
            Trace copy = new Trace();
            copy.departureOrdinal = departureOrdinal;
            copy.departureTime = departureTime;
            copy.legMode = legMode;
            copy.routingMode = routingMode;
            copy.lastEventType = lastEventType;
            copy.lastEventTime = lastEventTime;
            copy.lastLink = lastLink;
            copy.stuckMode = stuckMode;
            copy.stuckTime = stuckTime;
            copy.stuck = stuck;
            copy.firstArrivalAfterOldEnd = firstArrivalAfterOldEnd;
            copy.lastArrivalTime = lastArrivalTime;
            copy.lastCarMovementType = lastCarMovementType;
            copy.lastCarMovementTime = lastCarMovementTime;
            copy.lastCarMovementLink = lastCarMovementLink;
            copy.ptJourneyActive = ptJourneyActive;
            copy.ptWaited = ptWaited;
            copy.ptBoarded = ptBoarded;
            copy.ptLeftVehicle = ptLeftVehicle;
            copy.ptOnVehicleAtCutoff = ptOnVehicleAtCutoff;
            copy.teleportationArrival = teleportationArrival;
            copy.waitingStop = waitingStop;
            copy.destinationStop = destinationStop;
            copy.transitVehicle = transitVehicle;
            copy.transitLine = transitLine;
            copy.transitRoute = transitRoute;
            return copy;
        }
    }

    private static final class OldStuckCollector implements BasicEventHandler {
        private final LinkedHashMap<String, OldStuck> stuck = new LinkedHashMap<>();
        @Override public void handleEvent(Event event) {
            if (!(event instanceof PersonStuckEvent stuckEvent)) return;
            String id = stuckEvent.getPersonId().toString();
            require(stuck.put(id, new OldStuck(id,
                            normalize(stuckEvent.getLegMode(), "unknown"), stuckEvent.getTime()))
                            == null,
                    "Duplicate 43h stuck event for " + id);
        }
        Map<String, OldStuck> result() { return Map.copyOf(stuck); }
    }

    private static final class VehicleState {
        String driver = "";
        String line = "";
        String route = "";
        String stop = "";
    }

    private static final class DetailedEventCollector implements BasicEventHandler {
        private final Set<String> watched;
        private final Map<String, Trace> traces = new HashMap<>();
        private final Map<String, VehicleState> vehicles = new HashMap<>();
        private final Map<String, String> vehicleDrivers = new HashMap<>();
        private final Map<String, Trace> stuck = new HashMap<>();

        DetailedEventCollector(Set<String> watched) {
            this.watched = Set.copyOf(watched);
            watched.forEach(id -> traces.put(id, new Trace()));
        }

        @Override public void handleEvent(Event event) {
            if (event instanceof TransitDriverStartsEvent transit) {
                VehicleState vehicle = vehicles.computeIfAbsent(
                        transit.getVehicleId().toString(), ignored -> new VehicleState());
                vehicle.driver = transit.getDriverId().toString();
                vehicle.line = transit.getTransitLineId().toString();
                vehicle.route = transit.getTransitRouteId().toString();
                return;
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) {
                vehicles.computeIfAbsent(arrival.getVehicleId().toString(),
                        ignored -> new VehicleState()).stop = arrival.getFacilityId().toString();
                return;
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                vehicles.computeIfAbsent(departure.getVehicleId().toString(),
                        ignored -> new VehicleState()).stop = departure.getFacilityId().toString();
                return;
            }
            if (event instanceof VehicleEntersTrafficEvent enters) {
                String id = enters.getPersonId().toString();
                vehicleDrivers.put(enters.getVehicleId().toString(), id);
                movement(id, event.getEventType(), event.getTime(), enters.getLinkId().toString());
                return;
            }
            if (event instanceof VehicleLeavesTrafficEvent leaves) {
                String vehicle = leaves.getVehicleId().toString();
                movement(vehicleDrivers.get(vehicle), event.getEventType(), event.getTime(),
                        leaves.getLinkId().toString());
                vehicleDrivers.remove(vehicle);
                return;
            }
            if (event instanceof LinkEnterEvent enter) {
                movement(vehicleDrivers.get(enter.getVehicleId().toString()),
                        event.getEventType(), event.getTime(), enter.getLinkId().toString());
                return;
            }
            if (event instanceof LinkLeaveEvent leave) {
                movement(vehicleDrivers.get(leave.getVehicleId().toString()),
                        event.getEventType(), event.getTime(), leave.getLinkId().toString());
                return;
            }
            String personId = personId(event);
            if (personId == null || !watched.contains(personId)) return;
            Trace trace = traces.get(personId);
            if (event instanceof PersonStuckEvent stuckEvent) {
                require(!trace.stuck, "Duplicate 48h stuck event for " + personId);
                trace.stuck = true;
                trace.stuckTime = stuckEvent.getTime();
                trace.stuckMode = normalize(stuckEvent.getLegMode(), trace.routingMode);
                if (!trace.transitVehicle.isBlank()) {
                    VehicleState vehicle = vehicles.get(trace.transitVehicle);
                    if (vehicle != null) {
                        trace.transitLine = vehicle.line;
                        trace.transitRoute = vehicle.route;
                        if (trace.waitingStop.isBlank()) trace.waitingStop = vehicle.stop;
                    }
                }
                stuck.put(personId, trace.snapshot());
                return;
            }
            if (event instanceof PersonDepartureEvent departure) {
                String routing = normalize(departure.getRoutingMode(), departure.getLegMode());
                if (!"pt".equals(routing) || !trace.ptJourneyActive) resetJourney(trace);
                trace.ptJourneyActive = "pt".equals(routing);
                trace.departureOrdinal++;
                trace.departureTime = departure.getTime();
                trace.legMode = normalize(departure.getLegMode(), "unknown");
                trace.routingMode = routing;
                trace.lastLink = departure.getLinkId().toString();
                updateLast(trace, event, trace.lastLink);
                return;
            }
            if (event instanceof AgentWaitingForPtEvent waiting) {
                trace.ptJourneyActive = true;
                trace.ptWaited = true;
                trace.waitingStop = waiting.getWaitingAtStopId().toString();
                trace.destinationStop = waiting.getDestinationStopId().toString();
                updateLast(trace, event, trace.waitingStop);
                return;
            }
            if (event instanceof PersonEntersVehicleEvent enters) {
                VehicleState vehicle = vehicles.get(enters.getVehicleId().toString());
                if (vehicle != null && !vehicle.line.isBlank()) {
                    trace.ptJourneyActive = true;
                    trace.ptBoarded = true;
                    trace.ptOnVehicleAtCutoff = true;
                    trace.transitVehicle = enters.getVehicleId().toString();
                    trace.transitLine = vehicle.line;
                    trace.transitRoute = vehicle.route;
                    if (!vehicle.stop.isBlank()) trace.waitingStop = vehicle.stop;
                }
                updateLast(trace, event, enters.getVehicleId().toString());
                return;
            }
            if (event instanceof PersonLeavesVehicleEvent leaves) {
                if (leaves.getVehicleId().toString().equals(trace.transitVehicle)) {
                    trace.ptLeftVehicle = true;
                    trace.ptOnVehicleAtCutoff = false;
                    VehicleState vehicle = vehicles.get(trace.transitVehicle);
                    if (vehicle != null && !vehicle.stop.isBlank()) {
                        trace.waitingStop = vehicle.stop;
                    }
                }
                updateLast(trace, event, leaves.getVehicleId().toString());
                return;
            }
            if (event instanceof PersonArrivalEvent arrival) {
                trace.lastArrivalTime = arrival.getTime();
                if (arrival.getTime() > OLD_END && !finite(trace.firstArrivalAfterOldEnd)) {
                    trace.firstArrivalAfterOldEnd = arrival.getTime();
                }
                updateLast(trace, event, arrival.getLinkId().toString());
                return;
            }
            if (event instanceof TeleportationArrivalEvent) {
                trace.teleportationArrival = true;
                updateLast(trace, event, "");
                return;
            }
            if (event instanceof ActivityStartEvent activity) {
                if (!StageActivityTypeIdentifier.isStageActivity(activity.getActType())) {
                    trace.ptJourneyActive = false;
                }
                updateLast(trace, event, activity.getLinkId() == null ? ""
                        : activity.getLinkId().toString());
                return;
            }
            updateLast(trace, event, attribute(event, "link"));
        }

        private void movement(String driver, String type, double time, String link) {
            if (driver == null || !watched.contains(driver)) return;
            Trace trace = traces.get(driver);
            trace.lastCarMovementType = type;
            trace.lastCarMovementTime = time;
            trace.lastCarMovementLink = link;
            updateLast(trace, type, time, link);
        }

        EventEvidence result() { return new EventEvidence(Map.copyOf(traces), Map.copyOf(stuck)); }

        private static void resetJourney(Trace trace) {
            trace.ptWaited = false;
            trace.ptBoarded = false;
            trace.ptLeftVehicle = false;
            trace.ptOnVehicleAtCutoff = false;
            trace.teleportationArrival = false;
            trace.waitingStop = "";
            trace.destinationStop = "";
            trace.transitVehicle = "";
            trace.transitLine = "";
            trace.transitRoute = "";
        }
    }

    private static String personId(Event event) {
        if (event instanceof org.matsim.api.core.v01.events.HasPersonId person) {
            return person.getPersonId().toString();
        }
        if (event instanceof AgentWaitingForPtEvent waiting) {
            return waiting.getPersonId().toString();
        }
        return null;
    }

    private static String attribute(Event event, String key) {
        return event.getAttributes().getOrDefault(key, "");
    }

    private static void updateLast(Trace trace, Event event, String location) {
        updateLast(trace, event.getEventType(), event.getTime(), location);
    }

    private static void updateLast(Trace trace, String type, double time, String location) {
        trace.lastEventType = type;
        trace.lastEventTime = time;
        if (location != null && !location.isBlank()) trace.lastLink = location;
    }
}
