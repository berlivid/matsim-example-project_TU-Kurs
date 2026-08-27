package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Read-only resident-cohort reanalysis of the two protected legacy
 * {@code BOTH_INSIDE} mode-choice calibration outputs.
 */
public final class ReanalyzeLegacy2019ModeChoiceOutputsForResidents {
    static final Path OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "legacy-mode-choice-resident-reanalysis");
    static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    static final Map<String, Double> TARGETS = Map.of(
            "car", 34.0, "pt", 24.0, "bike", 18.0, "walk", 24.0);
    private static final String REGULAR_SHUTDOWN =
            "S H U T D O W N   ---   shutdown completed.";
    private static final Pattern LOG_FAILURE = Pattern.compile(
            "(?i)(\\bERROR\\b|Exception|Mobsim did not complete normally|unexpected shutdown)");

    private static final LegacySpec ROUND_1 = new LegacySpec(
            "LEGACY_ROUND_1", ValidateModeChoiceCalibrationRound1Config.CONFIG,
            Path.of(ValidateModeChoiceCalibrationRound1Config.OUTPUT_DIRECTORY),
            ValidateModeChoiceCalibrationRound1Config.RUN_ID, "BOTH_INSIDE");
    private static final LegacySpec ROUND_2 = new LegacySpec(
            "LEGACY_ROUND_2", ValidateModeChoiceCalibrationRound2Config.CONFIG,
            Path.of(ValidateModeChoiceCalibrationRound2Config.OUTPUT_DIRECTORY),
            ValidateModeChoiceCalibrationRound2Config.RUN_ID, "BOTH_INSIDE");

    private ReanalyzeLegacy2019ModeChoiceOutputsForResidents() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0,
                "This read-only reanalysis accepts no arguments");
        requireOutputAbsent(OUTPUT);

        // Reuse the productive validator's raw protected-input hashes and canonical
        // UTF-8/LF municipal-boundary hash without requiring its output to be absent.
        Config residentConfig = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();

        Config round1Config = ValidateModeChoiceCalibrationRound1Config
                .loadAndValidate(false);
        Config round2Config = ValidateModeChoiceCalibrationRound2Config
                .loadAndValidate(false);
        LegacyAudit round1Audit = inspectLegacyOutput(ROUND_1, round1Config);
        LegacyAudit round2Audit = inspectLegacyOutput(ROUND_2, round2Config);

        Path round1Population = resolveInput(ROUND_1.config(),
                round1Config.plans().getInputFile());
        Path round2Population = resolveInput(ROUND_2.config(),
                round2Config.plans().getInputFile());
        require(round1Population.equals(round2Population),
                "Legacy rounds do not reference the same original population: "
                        + round1Population + " != " + round2Population);
        Path authoritativePopulation = AnalyzeMunichResidentCohort.resolvePopulation(
                residentConfig);
        require(round1Population.equals(authoritativePopulation),
                "Legacy population differs from the authoritative resident-calibration "
                        + "population: " + round1Population + " != "
                        + authoritativePopulation);

        CohortDefinition cohort = readCohort(round1Population, boundary);
        cohort.requireAuthoritative();

        LegacyResult round1 = analyze(ROUND_1, round1Config, round1Audit, cohort, boundary);
        LegacyResult round2 = analyze(ROUND_2, round2Config, round2Audit, cohort, boundary);
        require(round1Audit.sourceSnapshot().equals(captureSnapshot(ROUND_1.output())),
                "Legacy Round-1 output changed during read-only analysis");
        require(round2Audit.sourceSnapshot().equals(captureSnapshot(ROUND_2.output())),
                "Legacy Round-2 output changed during read-only analysis");

        List<ComparisonRow> comparison = comparisonRows(round1, round2);
        requireOutputAbsent(OUTPUT);
        Files.createDirectory(OUTPUT);
        write(OUTPUT.resolve("legacy_round_1_resident_summary.csv"), summaryCsv(round1));
        write(OUTPUT.resolve("legacy_round_2_resident_summary.csv"), summaryCsv(round2));
        write(OUTPUT.resolve("legacy_resident_mode_choice_comparison.csv"),
                comparisonCsv(comparison));
        write(OUTPUT.resolve("legacy_resident_reanalysis_report.md"),
                report(round1, round2, comparison, cohort, boundary));

        System.out.printf(Locale.ROOT,
                "LEGACY RESIDENT MODE-CHOICE REANALYSIS PASS%n"
                        + "residents=%d residentTrips=%d%nround1Deviation=%.9f pp "
                        + "round2Deviation=%.9f pp%noutput=%s%n"
                        + "No Controller or QSim was started; both legacy outputs "
                        + "were read only.%n",
                cohort.residentIds().size(), cohort.residentMainTrips(),
                round1.sumAbsoluteDeviation(), round2.sumAbsoluteDeviation(), OUTPUT);
    }

    static LegacyAudit inspectLegacyOutput(LegacySpec spec, Config expected)
            throws Exception {
        RequiredEvidence evidence = locateRequiredEvidence(
                spec.output(), spec.runId());
        Config actual = ConfigUtils.loadConfig(evidence.outputConfig().toString());
        ResidentOutputConfigSemanticComparison.requireEquivalent(expected, actual);
        require(actual.controller().getLastIteration()
                        == expected.controller().getLastIteration(),
                spec.name() + " output config has the wrong final iteration");
        requireNormalShutdown(evidence.log());
        Optional<Path> events = optionalFinalEvents(spec.output(),
                actual.controller().getLastIteration());
        List<Path> scoreStatistics = locateScoreStatistics(spec.output());
        return new LegacyAudit(evidence, actual, events, scoreStatistics,
                captureSnapshot(spec.output()));
    }

    static RequiredEvidence locateRequiredEvidence(Path output, String runId)
            throws Exception {
        require(Files.isDirectory(output), "Legacy output is missing: " + output);
        Path outputConfig = output.resolve(runId + ".output_config.xml");
        Path log = output.resolve(runId + ".logfile.log");
        require(Files.isRegularFile(outputConfig),
                "Legacy output config is missing: " + outputConfig);
        require(Files.isRegularFile(log),
                "Legacy normal-shutdown log is missing: " + log);
        Path plans = AnalyzeInitial2019ResidentModeChoiceOutput.finalPlans(output);
        return new RequiredEvidence(outputConfig, log, plans);
    }

    static void requireNormalShutdown(Path log) throws IOException {
        boolean regularShutdown = false;
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                require(!LOG_FAILURE.matcher(line).find(),
                        "Controller log contains failure evidence: " + line);
                if (line.contains(REGULAR_SHUTDOWN)) regularShutdown = true;
            }
        }
        require(regularShutdown,
                "Regular MATSim shutdown marker is missing from " + log);
    }

    static Optional<Path> optionalFinalEvents(Path output, int finalIteration)
            throws IOException {
        Path iteration = output.resolve("ITERS").resolve("it." + finalIteration);
        List<Path> iterationEvents = List.of();
        if (Files.isDirectory(iteration)) {
            try (var paths = Files.walk(iteration)) {
                iterationEvents = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .endsWith("." + finalIteration + ".events.xml.gz"))
                        .sorted().toList();
            }
        }
        require(iterationEvents.size() <= 1,
                "Multiple final-iteration event files found in " + iteration
                        + ": " + iterationEvents);
        if (iterationEvents.size() == 1) return Optional.of(iterationEvents.getFirst());
        try (var paths = Files.list(output)) {
            List<Path> rootEvents = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".output_events.xml.gz"))
                    .sorted().toList();
            require(rootEvents.size() <= 1,
                    "Multiple root output-event files found in " + output + ": "
                            + rootEvents);
            return rootEvents.isEmpty() ? Optional.empty()
                    : Optional.of(rootEvents.getFirst());
        }
    }

    static List<Path> locateScoreStatistics(Path output) throws IOException {
        try (var paths = Files.walk(output)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains("scorestats"))
                    .map(output::relativize).sorted().toList();
        }
    }

    static CohortDefinition readCohort(Path population,
                                       MunichMunicipalBoundary boundary) {
        require(Files.isRegularFile(population),
                "Original population is missing: " + population);
        CohortCollector collector = new CohortCollector(boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(collector::accept);
        reader.readFile(population.toString());
        return collector.result(population);
    }

    static CohortDefinition readCohort(Iterable<Person> persons,
                                       MunichMunicipalBoundary boundary) {
        CohortCollector collector = new CohortCollector(boundary);
        persons.forEach(collector::accept);
        return collector.result(Path.of("in-memory-population"));
    }

    static LegacyResult analyze(LegacySpec spec, Config sourceConfig,
                                LegacyAudit audit, CohortDefinition cohort,
                                MunichMunicipalBoundary boundary) throws Exception {
        Config analysisConfig = ConfigUtils.loadConfig(spec.config().toString());
        analysisConfig.plans().setInputFile(audit.evidence().finalPlans()
                .toAbsolutePath().normalize().toString());
        Scenario scenario = ScenarioUtils.loadScenario(analysisConfig);
        require(scenario.getPopulation().getPersons().size()
                        == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                spec.name() + " final population count changed: "
                        + scenario.getPopulation().getPersons().size());
        require(scenario.getPopulation().getPersons().keySet()
                        .equals(cohort.allPersonIds()),
                spec.name() + " final population person IDs differ from the original input");
        Map<Id<Person>, Plan> residentPlans = selectedResidentPlans(
                scenario.getPopulation(), cohort.residentIds());
        ModeChoiceCalibrationAnalysis.AnalysisResult result =
                new ModeChoiceCalibrationAnalysis(scenario, boundary).analyze(
                        sourceConfig.controller().getLastIteration(), residentPlans);
        ResidentModeChoiceCalibrationIterationListener.validateResidentStructure(
                result, residentPlans.size(), sourceConfig.controller().getLastIteration());
        ModeChoiceCalibrationAnalysis.MetricSnapshot all = result.metrics(
                ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
        requireFourModeTotals(spec.name(), all);
        StuckSummary stuck = audit.events().isPresent()
                ? readResidentStuckEvents(audit.events().orElseThrow(),
                cohort.residentIds()) : StuckSummary.unavailable();
        return new LegacyResult(spec, sourceConfig, audit, result, all, stuck);
    }

    static Map<Id<Person>, Plan> selectedResidentPlans(Population outputPopulation,
                                                       Set<Id<Person>> residentIds) {
        TreeMap<Id<Person>, Plan> plans = new TreeMap<>();
        for (Id<Person> id : residentIds) {
            Person person = outputPopulation.getPersons().get(id);
            require(person != null, "Resident is missing from final plans: " + id);
            require(person.getSelectedPlan() != null,
                    "Resident has no selected final plan: " + id);
            plans.put(id, person.getSelectedPlan());
        }
        require(plans.size() == residentIds.size(),
                "Resident final-plan selection is incomplete");
        return Map.copyOf(plans);
    }

    static void requireFourModeTotals(String run,
                                      ModeChoiceCalibrationAnalysis.MetricSnapshot metrics) {
        long physical = MODES.stream().mapToLong(mode ->
                metrics.mainTripsByMode().getOrDefault(mode, 0L)).sum();
        long choice = MODES.stream().mapToLong(mode ->
                metrics.choiceMainTripsByMode().getOrDefault(mode, 0L)).sum();
        require(physical == metrics.mainTrips(), run
                + " contains a non-calibration physical mode: "
                + metrics.mainTripsByMode());
        require(choice == metrics.mainTrips(), run
                + " contains a missing, inconsistent or non-calibration choice mode: "
                + metrics.choiceMainTripsByMode());
    }

    static StuckSummary readResidentStuckEvents(Path events,
                                                Set<Id<Person>> residents) {
        StuckCollector collector = new StuckCollector(residents);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(collector);
        new MatsimEventsReader(manager).readFile(events.toString());
        return collector.result();
    }

    static List<ComparisonRow> comparisonRows(LegacyResult round1,
                                              LegacyResult round2) throws IOException {
        ArrayList<ComparisonRow> rows = new ArrayList<>();
        rows.add(ComparisonRow.fromLegacy(round1));
        rows.add(ComparisonRow.fromLegacy(round2));
        rows.add(readResidentComparison("RESIDENT_INITIAL",
                ValidateResidentModeChoiceCalibrationConfig.CONFIG,
                ValidateResidentModeChoiceCalibrationConfig.OUTPUT.resolve("analysis")));
        rows.add(readResidentComparison("RESIDENT_ROUND_2",
                ValidateResidentModeChoiceCalibrationRound2Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT.resolve("analysis")));
        rows.add(readResidentComparison("RESIDENT_ROUND_3",
                ValidateResidentModeChoiceCalibrationRound3Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT.resolve("analysis")));
        rows.add(readResidentComparison("RESIDENT_ROUND_4",
                ValidateResidentModeChoiceCalibrationRound4Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT.resolve("analysis")));

        double bestLegacy = Math.min(round1.sumAbsoluteDeviation(),
                round2.sumAbsoluteDeviation());
        ArrayList<ComparisonRow> labelled = new ArrayList<>();
        for (ComparisonRow row : rows) {
            if (row.name().startsWith("LEGACY_") && row.available()) {
                String status = Math.abs(row.sumAbsoluteDeviation() - bestLegacy) < 1e-9
                        ? "YES_BEST_LEGACY_BY_SUM_ABSOLUTE_DEVIATION_REQUIRES_48H_RESIDENT_RERUN"
                        : "NO_NOT_BEST_OF_THE_TWO_LEGACY_RESULTS";
                labelled.add(row.withCandidateStatus(status));
            } else {
                labelled.add(row);
            }
        }
        return List.copyOf(labelled);
    }

    static ComparisonRow readResidentComparison(String name, Path configPath,
                                                Path analysis) throws IOException {
        Path summary = analysis.resolve("resident_mode_choice_final_summary.csv");
        if (!Files.isRegularFile(configPath) || !Files.isRegularFile(summary)) {
            return ComparisonRow.unavailable(name,
                    "resident_result_not_available_locally_or_on_server");
        }
        Config config = ConfigUtils.loadConfig(configPath.toString());
        List<Map<String, String>> rows = readSimpleCsv(summary);
        long persons = roundedMetric(rows, "resident_persons", "all");
        long trips = roundedMetric(rows, "resident_main_trips", "all");
        require(persons == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                name + " comparison summary has the wrong resident count: " + persons);
        require(trips == ResidentModeChoiceCalibrationIterationListener
                        .EXPECTED_RESIDENT_MAIN_TRIPS,
                name + " comparison summary has the wrong resident trip count: " + trips);
        LinkedHashMap<String, Double> shares = new LinkedHashMap<>();
        for (String mode : MODES) {
            shares.put(mode, metric(rows, "resident_physical_trip_share", mode));
        }
        return ComparisonRow.available(name, constants(config),
                config.controller().getLastIteration(), qsimEnd(config),
                "MUNICH_RESIDENT_ALL_TRIPS", shares, persons, trips,
                "NOT_APPLICABLE_EXISTING_RESIDENT_RESULT", summary.toString());
    }

    static double absoluteDeviation(Map<String, Double> shares) {
        return MODES.stream().mapToDouble(mode ->
                Math.abs(shares.get(mode) - TARGETS.get(mode))).sum();
    }

    static void requireOutputAbsent(Path output) {
        require(!Files.exists(output),
                "Protected reanalysis output already exists; nothing was overwritten: "
                        + output);
    }

    static Map<Path, FileState> captureSnapshot(Path root) throws IOException {
        require(Files.isDirectory(root), "Source output is missing: " + root);
        TreeMap<Path, FileState> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class);
                result.put(root.relativize(path), new FileState(
                        attributes.size(), attributes.lastModifiedTime().toMillis()));
            }
        }
        return Map.copyOf(result);
    }

    private static String summaryCsv(LegacyResult result) {
        StringBuilder csv = new StringBuilder(
                "section,metric,dimension,value,unit,target_value,difference_to_target,status\n");
        append(csv, "cohort", "resident_persons", "all",
                result.all().validPersons(), "persons", "", "", "");
        append(csv, "cohort", "resident_main_trips", "all",
                result.all().mainTrips(), "trips", "", "", "");
        for (String mode : MODES) {
            double share = result.all().modalSharePercent(mode);
            double choice = result.all().choiceModalSharePercent(mode);
            double pkmShare = pkmShare(result.all(), mode);
            append(csv, "physical_mode", "trip_count", mode,
                    result.all().mainTripsByMode().getOrDefault(mode, 0L),
                    "trips", "", "", "");
            append(csv, "physical_mode", "trip_share", mode, share,
                    "percent", TARGETS.get(mode), share - TARGETS.get(mode), "");
            append(csv, "choice_mode", "trip_count", mode,
                    result.all().choiceMainTripsByMode().getOrDefault(mode, 0L),
                    "trips", "", "", "");
            append(csv, "choice_mode", "trip_share", mode, choice,
                    "percent", "", "", "diagnostic_not_empirical_target");
            append(csv, "distance", "raw_daily_sample_pkm", mode,
                    result.all().mainModePkm(mode), "person_km_per_simulated_day",
                    "", "", "");
            append(csv, "distance", "normalized_pkm_share", mode, pkmShare,
                    "percent", "", "", "secondary_plausibility_indicator");
            append(csv, "distance", "mean_trip_distance", mode,
                    result.all().meanTripLengthKm(mode), "km", "", "", "");
        }
        result.all().physicalChoiceTransitions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> append(csv,
                        "physical_choice_transition", "trip_count",
                        entry.getKey().physicalMode() + "->" + entry.getKey().choiceMode(),
                        entry.getValue(), "trips", "", "", ""));
        append(csv, "routing_diagnostic", "pt_request_walk_only_physical_route",
                "pt->walk", result.all().ptRequestsWithWalkOnlyPhysicalRoute(),
                "trips", "", "", "");
        for (ModeChoiceCalibrationAnalysis.SpatialScope scope : List.of(
                ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_OUTSIDE)) {
            append(csv, "territorial_breakdown", "resident_main_trips", scope,
                    metrics(result.analysis(), scope).mainTrips(), "trips", "", "", "");
        }
        append(csv, "distance_quality", "invalid_main_trip_distances", "all",
                result.all().invalidMainTripDistances(), "trips", "", "", "");
        append(csv, "events", "resident_stuck_events", "all",
                result.stuck().available() ? result.stuck().events() : "",
                "events", "", "", result.stuck().available() ? "AVAILABLE" : "UNAVAILABLE");
        append(csv, "events", "unique_residents_with_stuck_event", "all",
                result.stuck().available() ? result.stuck().uniquePersons() : "",
                "persons", "", "", result.stuck().available() ? "AVAILABLE" : "UNAVAILABLE");
        result.stuck().byMode().forEach((mode, count) -> append(csv, "events",
                "resident_stuck_events", mode, count, "events", "", "", "AVAILABLE"));
        return csv.toString();
    }

    private static String comparisonCsv(List<ComparisonRow> rows) {
        StringBuilder csv = new StringBuilder("calibration_run,available,car_constant,"
                + "pt_constant,bike_constant,walk_constant,final_iteration,qsim_horizon,"
                + "original_calibration_cohort,new_comparison_cohort,resident_persons,"
                + "resident_main_trips,resident_car_share,resident_pt_share,"
                + "resident_bike_share,resident_walk_share,car_absolute_deviation_pp,"
                + "pt_absolute_deviation_pp,bike_absolute_deviation_pp,"
                + "walk_absolute_deviation_pp,sum_absolute_modal_share_deviation_pp,"
                + "candidate_for_one_final_48h_resident_validation,evidence\n");
        for (ComparisonRow row : rows) {
            csv.append(csv(row.name())).append(',').append(row.available()).append(',')
                    .append(number(row.constantValue("car"))).append(',')
                    .append(number(row.constantValue("pt"))).append(',')
                    .append(number(row.constantValue("bike"))).append(',')
                    .append(number(row.constantValue("walk"))).append(',')
                    .append(row.available() ? row.finalIteration() : "").append(',')
                    .append(csv(row.horizon())).append(',')
                    .append(csv(row.originalCohort())).append(',')
                    .append(csv(row.available() ? "MUNICH_RESIDENT_ALL_TRIPS" : ""))
                    .append(',').append(row.available() ? row.residentPersons() : "")
                    .append(',').append(row.available() ? row.residentTrips() : "");
            for (String mode : MODES) csv.append(',').append(number(row.share(mode)));
            for (String mode : MODES) csv.append(',').append(number(row.deviation(mode)));
            csv.append(',').append(number(row.sumAbsoluteDeviation())).append(',')
                    .append(csv(row.candidateStatus())).append(',')
                    .append(csv(row.evidence())).append('\n');
        }
        return csv.toString();
    }

    private static String report(LegacyResult round1, LegacyResult round2,
                                 List<ComparisonRow> comparison,
                                 CohortDefinition cohort,
                                 MunichMunicipalBoundary boundary) {
        StringBuilder report = new StringBuilder(
                "# Legacy 2019 mode-choice outputs: Munich-resident reanalysis\n\n");
        report.append("## Purpose and method\n\n")
                .append("This is a read-only reanalysis of the final selected plans from two ")
                .append("preserved technical calibration runs. Those runs originally used ")
                .append("`BOTH_INSIDE` trips as their calibration cohort. This comparison does ")
                .append("not reproduce that territorial analysis: it reconstructs residence ")
                .append("from the exact `home` activity in the unchanged original daily plan, ")
                .append("uses the approved Munich municipal boundary with `covers`, skips MATSim ")
                .append("stage activities, and includes every main trip made by a classified ")
                .append("Munich resident. Regional and unresolved persons are excluded.\n\n")
                .append("Boundary: `").append(boundary.source()).append("`; canonical UTF-8/LF ")
                .append("SHA-256 `").append(boundary.sha256()).append("`. Resident cohort: ")
                .append(cohort.residentIds().size()).append(" persons and ")
                .append(cohort.residentMainTrips()).append(" original main trips. No simulation ")
                .append("was started and neither source output was modified.\n\n")
                .append("## Legacy-output evidence\n\n");
        appendAudit(report, round1);
        appendAudit(report, round2);
        report.append("## Resident results\n\n")
                .append("Physical/realized mode is the empirical comparison metric. Choice/")
                .append("routing mode is reported separately; PT requests realized as walk-only ")
                .append("routes remain visible and are not conflated with physical PT. Raw Pkm ")
                .append("are daily five-percent-sample person-kilometres and normalized Pkm shares ")
                .append("are secondary plausibility indicators.\n\n")
                .append("| Run | Car | PT | Bike | Walk | Sum absolute target deviation | PT requests physically walk-only |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (LegacyResult result : List.of(round1, round2)) {
            report.append('|').append(result.spec().name()).append('|');
            for (String mode : MODES) report.append(number(result.all().modalSharePercent(mode)))
                    .append("%|");
            report.append(number(result.sumAbsoluteDeviation())).append(" pp|")
                    .append(result.all().ptRequestsWithWalkOnlyPhysicalRoute()).append("|\n");
        }
        report.append("\nTerritorial counts (new resident cohort):\n\n")
                .append("| Run | BOTH_INSIDE | ORIGIN_ONLY | DESTINATION_ONLY | BOTH_OUTSIDE |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (LegacyResult result : List.of(round1, round2)) {
            report.append('|').append(result.spec().name()).append('|');
            for (var scope : List.of(ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_INSIDE,
                    ModeChoiceCalibrationAnalysis.SpatialScope.ORIGIN_ONLY,
                    ModeChoiceCalibrationAnalysis.SpatialScope.DESTINATION_ONLY,
                    ModeChoiceCalibrationAnalysis.SpatialScope.BOTH_OUTSIDE)) {
                report.append(metrics(result.analysis(), scope).mainTrips()).append('|');
            }
            report.append('\n');
        }
        report.append("\n## Decision comparison\n\n")
                .append("| Run | Original cohort | Resident car/PT/bike/walk | Sum absolute deviation | Candidate status |\n")
                .append("|---|---|---|---:|---|\n");
        for (ComparisonRow row : comparison) {
            report.append('|').append(row.name()).append('|').append(row.originalCohort())
                    .append('|');
            if (row.available()) {
                report.append(number(row.share("car"))).append(" / ")
                        .append(number(row.share("pt"))).append(" / ")
                        .append(number(row.share("bike"))).append(" / ")
                        .append(number(row.share("walk"))).append("|")
                        .append(number(row.sumAbsoluteDeviation())).append(" pp|");
            } else {
                report.append("unavailable|unavailable|");
            }
            report.append(row.candidateStatus()).append("|\n");
        }
        report.append("\nThe legacy run with the smaller sum of absolute four-mode deviations ")
                .append("is labelled only as the best of these two legacy candidates. This is ")
                .append("not a declaration of calibration. Both legacy runs used a 43-hour ")
                .append("horizon and a territorial calibration cohort, so any selected constants ")
                .append("must still be tested once with the final 48-hour resident configuration. ")
                .append("The comparison does not create an arbitrary acceptance threshold.\n\n")
                .append("## Stuck-event availability\n\n");
        for (LegacyResult result : List.of(round1, round2)) {
            report.append("- `").append(result.spec().name()).append("`: ")
                    .append(result.stuck().available()
                            ? result.stuck().events() + " resident StuckEvents affecting "
                            + result.stuck().uniquePersons() + " residents."
                            : "UNAVAILABLE (no final-iteration or root output event file found; zero was not inferred).")
                    .append('\n');
        }
        return report.toString();
    }

    private static void appendAudit(StringBuilder report, LegacyResult result) {
        Config config = result.config();
        report.append("### ").append(result.spec().name()).append("\n\n")
                .append("- Final plans: `").append(result.audit().evidence().finalPlans())
                .append("`\n- Output config: `").append(result.audit().evidence().outputConfig())
                .append("` (semantically equal to the versioned source config)\n")
                .append("- Normal shutdown: confirmed in `")
                .append(result.audit().evidence().log()).append("`\n")
                .append("- Final iteration: ").append(config.controller().getLastIteration())
                .append("; QSim horizon: ").append(qsimEnd(config)).append("\n")
                .append("- Inputs: population `").append(config.plans().getInputFile())
                .append("`, network `").append(config.network().getInputFile())
                .append("`, schedule `").append(config.transit().getTransitScheduleFile())
                .append("`, vehicles `").append(config.transit().getVehiclesFile()).append("`\n")
                .append("- Constants: ").append(constants(config)).append("\n")
                .append("- Strategies: ").append(ValidateModeChoiceCalibrationConfig
                        .strategyMap(config)).append("; modes ")
                .append(List.of(config.subtourModeChoice().getModes()))
                .append("; chain-based modes ")
                .append(Set.of(config.subtourModeChoice().getChainBasedModes())).append("\n")
                .append("- Final events: ")
                .append(result.audit().events().map(Path::toString).orElse("unavailable"))
                .append("; score-statistics files: ")
                .append(result.audit().scoreStatistics().isEmpty()
                        ? "unavailable" : result.audit().scoreStatistics())
                .append("\n\n");
    }

    private static ModeChoiceCalibrationAnalysis.MetricSnapshot metrics(
            ModeChoiceCalibrationAnalysis.AnalysisResult result,
            ModeChoiceCalibrationAnalysis.SpatialScope scope) {
        return result.metrics(scope,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
    }

    private static double pkmShare(ModeChoiceCalibrationAnalysis.MetricSnapshot metrics,
                                   String mode) {
        double total = metrics.totalMainModePkm();
        return total == 0.0 ? Double.NaN : 100.0 * metrics.mainModePkm(mode) / total;
    }

    private static Map<String, Double> constants(Config config) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (String mode : MODES) {
            var params = config.scoring().getModes().get(mode);
            require(params != null, "Missing scoring mode parameters for " + mode);
            result.put(mode, params.getConstant());
        }
        return Map.copyOf(result);
    }

    private static String qsimEnd(Config config) {
        require(config.qsim().getEndTime().isDefined(), "QSim end time is undefined");
        long seconds = Math.round(config.qsim().getEndTime().seconds());
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    private static Path resolveInput(Path config, String input) {
        require(input != null && !input.isBlank(),
                "Config has no population input: " + config);
        return config.getParent().resolve(input).normalize();
    }

    private static List<Map<String, String>> readSimpleCsv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        require(lines.size() >= 2, "Comparison CSV is empty: " + file);
        String[] header = lines.getFirst().split(",", -1);
        ArrayList<Map<String, String>> result = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) continue;
            String[] values = lines.get(index).split(",", -1);
            require(values.length == header.length,
                    "Unexpected CSV column count at " + file + ":" + (index + 1));
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.length; column++) {
                row.put(header[column], values[column]);
            }
            result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    private static double metric(List<Map<String, String>> rows,
                                 String metric, String dimension) {
        List<Map<String, String>> matches = rows.stream()
                .filter(row -> metric.equals(row.get("metric"))
                        && dimension.equals(row.get("dimension"))).toList();
        require(matches.size() == 1,
                "Expected one " + metric + "/" + dimension + " row, found "
                        + matches.size());
        return Double.parseDouble(matches.getFirst().get("value"));
    }

    private static long roundedMetric(List<Map<String, String>> rows,
                                      String metric, String dimension) {
        return Math.round(metric(rows, metric, dimension));
    }

    private static void append(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            Object value = values[index];
            csv.append(value instanceof Number number ? number(number.doubleValue())
                    : csv(value == null ? "" : value.toString()));
        }
        csv.append('\n');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    record LegacySpec(String name, Path config, Path output, String runId,
                      String originalCohort) { }

    record RequiredEvidence(Path outputConfig, Path log, Path finalPlans) { }

    record LegacyAudit(RequiredEvidence evidence, Config outputConfig,
                       Optional<Path> events, List<Path> scoreStatistics,
                       Map<Path, FileState> sourceSnapshot) { }

    record FileState(long size, long lastModifiedMillis) { }

    record CohortDefinition(Path population, Set<Id<Person>> allPersonIds,
                            Set<Id<Person>> residentIds,
                            Map<MunichResidentClassifier.Classification, Long> counts,
                            long residentMainTrips) {
        void requireAuthoritative() {
            require(allPersonIds.size()
                            == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                    "Original total-person count changed: " + allPersonIds.size());
            require(residentIds.size()
                            == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                    "Original Munich-resident count changed: " + residentIds.size());
            require(counts.getOrDefault(
                            MunichResidentClassifier.Classification.NON_MUNICH_RESIDENT, 0L)
                            == ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                    "Original regional-background count changed: " + counts);
            long unresolved = counts.entrySet().stream()
                    .filter(entry -> entry.getKey().isUnresolved())
                    .mapToLong(Map.Entry::getValue).sum();
            require(unresolved
                            == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                    "Original unresolved-background count changed: " + unresolved);
            require(residentMainTrips == ResidentModeChoiceCalibrationIterationListener
                            .EXPECTED_RESIDENT_MAIN_TRIPS,
                    "Original resident main-trip count changed: " + residentMainTrips);
        }
    }

    private static final class CohortCollector {
        private final MunichResidentClassifier classifier;
        private final Set<Id<Person>> all = new HashSet<>();
        private final Set<Id<Person>> residents = new HashSet<>();
        private final EnumMap<MunichResidentClassifier.Classification, Long> counts =
                new EnumMap<>(MunichResidentClassifier.Classification.class);
        private long residentTrips;

        private CohortCollector(MunichMunicipalBoundary boundary) {
            classifier = new MunichResidentClassifier(boundary);
        }

        private void accept(Person person) {
            require(all.add(person.getId()), "Duplicate person ID in original population: "
                    + person.getId());
            MunichResidentClassifier.Result result = classifier.classify(person);
            counts.merge(result.classification(), 1L, Long::sum);
            if (result.classification()
                    == MunichResidentClassifier.Classification.MUNICH_RESIDENT) {
                residents.add(person.getId());
                residentTrips += TripStructureUtils.getTrips(person.getSelectedPlan(),
                        StageActivityTypeIdentifier::isStageActivity).size();
            }
        }

        private CohortDefinition result(Path population) {
            return new CohortDefinition(population, Set.copyOf(all), Set.copyOf(residents),
                    Map.copyOf(counts), residentTrips);
        }
    }

    record StuckSummary(boolean available, long events, long uniquePersons,
                        Map<String, Long> byMode) {
        static StuckSummary unavailable() {
            return new StuckSummary(false, 0, 0, Map.of());
        }
    }

    private static final class StuckCollector implements PersonStuckEventHandler {
        private final Set<Id<Person>> residents;
        private final Set<Id<Person>> persons = new TreeSet<>();
        private final TreeMap<String, Long> byMode = new TreeMap<>();
        private long events;

        private StuckCollector(Set<Id<Person>> residents) {
            this.residents = residents;
        }

        @Override
        public void handleEvent(PersonStuckEvent event) {
            if (!residents.contains(event.getPersonId())) return;
            events++;
            persons.add(event.getPersonId());
            byMode.merge(event.getLegMode() == null ? "unknown" : event.getLegMode(),
                    1L, Long::sum);
        }

        private StuckSummary result() {
            return new StuckSummary(true, events, persons.size(), Map.copyOf(byMode));
        }
    }

    record LegacyResult(LegacySpec spec, Config config, LegacyAudit audit,
                        ModeChoiceCalibrationAnalysis.AnalysisResult analysis,
                        ModeChoiceCalibrationAnalysis.MetricSnapshot all,
                        StuckSummary stuck) {
        double sumAbsoluteDeviation() {
            LinkedHashMap<String, Double> shares = new LinkedHashMap<>();
            MODES.forEach(mode -> shares.put(mode, all.modalSharePercent(mode)));
            return absoluteDeviation(shares);
        }
    }

    record ComparisonRow(String name, boolean available, Map<String, Double> constants,
                         int finalIteration, String horizon, String originalCohort,
                         Map<String, Double> shares, long residentPersons,
                         long residentTrips, double sumAbsoluteDeviation,
                         String candidateStatus, String evidence) {
        static ComparisonRow fromLegacy(LegacyResult result) {
            LinkedHashMap<String, Double> shares = new LinkedHashMap<>();
            MODES.forEach(mode -> shares.put(mode, result.all().modalSharePercent(mode)));
            return available(result.spec().name(),
                    ReanalyzeLegacy2019ModeChoiceOutputsForResidents.constants(result.config()),
                    result.config().controller().getLastIteration(), qsimEnd(result.config()),
                    result.spec().originalCohort(), shares, result.all().validPersons(),
                    result.all().mainTrips(), "PENDING_LEGACY_RANKING",
                    result.audit().evidence().finalPlans().toString());
        }

        static ComparisonRow available(String name, Map<String, Double> constants,
                                       int finalIteration, String horizon,
                                       String originalCohort, Map<String, Double> shares,
                                       long persons, long trips, String candidateStatus,
                                       String evidence) {
            return new ComparisonRow(name, true, Map.copyOf(constants), finalIteration,
                    horizon, originalCohort, Map.copyOf(shares), persons, trips,
                    absoluteDeviation(shares), candidateStatus, evidence);
        }

        static ComparisonRow unavailable(String name, String evidence) {
            return new ComparisonRow(name, false, Map.of(), 0, "", "MUNICH_RESIDENT_ALL_TRIPS",
                    Map.of(), 0, 0, Double.NaN, "UNAVAILABLE", evidence);
        }

        ComparisonRow withCandidateStatus(String status) {
            return new ComparisonRow(name, available, constants, finalIteration, horizon,
                    originalCohort, shares, residentPersons, residentTrips,
                    sumAbsoluteDeviation, status, evidence);
        }

        double constantValue(String mode) {
            return constants.getOrDefault(mode, Double.NaN);
        }

        double share(String mode) {
            return shares.getOrDefault(mode, Double.NaN);
        }

        double deviation(String mode) {
            return available ? Math.abs(share(mode) - TARGETS.get(mode)) : Double.NaN;
        }
    }
}
