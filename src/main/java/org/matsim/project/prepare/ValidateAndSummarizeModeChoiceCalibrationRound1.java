package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Validates and summarizes an existing round-1 server output without running MATSim. */
public final class ValidateAndSummarizeModeChoiceCalibrationRound1 {
    public static final Path OUTPUT = Path.of(
            ValidateModeChoiceCalibrationRound1Config.OUTPUT_DIRECTORY);
    static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    static final Set<Integer> EXPECTED_ITERATIONS = java.util.stream.IntStream.rangeClosed(0, 20)
            .boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Pattern LOG_FAILURE = Pattern.compile(
            "(?i)(\\bERROR\\b|Exception|Mobsim did not complete normally|unexpected shutdown)");
    private static final String REGULAR_SHUTDOWN = "S H U T D O W N   ---   shutdown completed.";

    private ValidateAndSummarizeModeChoiceCalibrationRound1() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only output validator accepts no arguments");
        Summary summary = validateAndSummarize(OUTPUT, true);
        System.out.printf(Locale.ROOT,
                "MODE-CHOICE ROUND-1 OUTPUT VALIDATION PASS%noutput=%s iterations=0..20%n"
                        + "persons=%d mainTrips=%d bothInsideTrips=%d%n"
                        + "lateMeanShares=%s%nlateMeanMinusTargetPp=%s%n"
                        + "finalPkmShares=%s%nstuckEvents16to20=%s%n",
                OUTPUT, summary.persons(), summary.mainTrips(), summary.bothInsideTrips(),
                summary.lateMean(), summary.lateDifference(), summary.finalPkmShares(),
                summary.stuckEvents());
    }

    static Summary validateAndSummarize(Path output, boolean writeSummary) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(output),
                "Round-1 output is missing: " + output);
        Path analysis = output.resolve("analysis");
        Csv history = readCsv(analysis.resolve("mode_choice_iteration_metrics.csv"));
        requireIterations(history, EXPECTED_ITERATIONS, "iteration history");
        requireConsistentMetricKeys(history);
        Csv finalSummary = readCsv(analysis.resolve("mode_choice_final_summary.csv"));
        requireIterations(finalSummary, Set.of(20), "final summary");
        requireNoDuplicateRows(finalSummary, "final summary");

        long persons = integerValue(history, 20, "ALL_TRIPS", "ALL_PLANS",
                "valid_persons", "all");
        long trips = integerValue(history, 20, "ALL_TRIPS", "ALL_PLANS",
                "valid_main_trips", "all");
        long insideTrips = integerValue(history, 20, "BOTH_INSIDE", "ALL_PLANS",
                "valid_main_trips", "all");
        ValidateModeChoiceCalibrationConfig.require(
                persons == ModeChoiceCalibrationIterationListener.EXPECTED_PERSONS,
                "Unexpected round-1 person count: " + persons);
        ValidateModeChoiceCalibrationConfig.require(
                trips == ModeChoiceCalibrationIterationListener.EXPECTED_MAIN_TRIPS,
                "Unexpected round-1 main-trip count: " + trips);
        ValidateModeChoiceCalibrationConfig.require(
                insideTrips == ModeChoiceCalibrationIterationListener.EXPECTED_BOTH_INSIDE_TRIPS,
                "Unexpected round-1 BOTH_INSIDE count: " + insideTrips);
        requireZero(history, "trip_modal_share", "unknown");
        requireZero(history, "invalid_stage_distances", null);
        requireZero(history, "invalid_main_trip_distances", null);

        Map<Integer, Map<String, Double>> shares = iterationShares(history);
        Map<String, Double> lateMean = new LinkedHashMap<>();
        Map<String, Double> lateMin = new LinkedHashMap<>();
        Map<String, Double> lateMax = new LinkedHashMap<>();
        Map<String, Double> difference = new LinkedHashMap<>();
        for (String mode : MODES) {
            var values = java.util.stream.IntStream.rangeClosed(16, 20)
                    .mapToObj(iteration -> shares.get(iteration).get(mode)).toList();
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            lateMean.put(mode, mean);
            lateMin.put(mode, values.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
            lateMax.put(mode, values.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
            difference.put(mode, mean
                    - ValidateModeChoiceCalibrationRound1Config.TARGETS.get(mode));
        }

        Map<String, Double> finalPkmShares = finalPkmShares(finalSummary);
        Csv stuck = readCsv(analysis.resolve("stuck_events_iteration_metrics.csv"));
        requireIterations(stuck, EXPECTED_ITERATIONS, "stuck-event history");
        Map<Integer, Long> stuckEvents = new LinkedHashMap<>();
        Map<Integer, Long> stuckPersons = new LinkedHashMap<>();
        for (int iteration = 16; iteration <= 20; iteration++) {
            Map<String, String> row = unique(stuck, iteration, null, null,
                    null, "all", Map.of(), "overall stuck-event row",
                    candidate -> "ALL_WINDOWS".equals(candidate.get("time_window"))
                            || "NO_EVENTS".equals(candidate.get("time_window")));
            stuckEvents.put(iteration, parseLong(row, "event_count"));
            stuckPersons.put(iteration, parseLong(row, "unique_persons"));
        }
        validateLogs(output);

        Summary summary = new Summary(persons, trips, insideTrips, shares,
                Map.copyOf(lateMean), Map.copyOf(lateMin), Map.copyOf(lateMax),
                Map.copyOf(difference), Map.copyOf(finalPkmShares),
                Map.copyOf(stuckEvents), Map.copyOf(stuckPersons));
        if (writeSummary) writeNewSummaries(analysis, summary);
        return summary;
    }

    private static Map<Integer, Map<String, Double>> iterationShares(Csv history) {
        Map<Integer, Map<String, Double>> result = new LinkedHashMap<>();
        for (int iteration = 0; iteration <= 20; iteration++) {
            Map<String, Double> modes = new LinkedHashMap<>();
            for (String mode : MODES) modes.put(mode, value(history, iteration,
                    "BOTH_INSIDE", "ALL_PLANS", "trip_modal_share", mode));
            double sum = modes.values().stream().mapToDouble(Double::doubleValue).sum();
            ValidateModeChoiceCalibrationConfig.require(Math.abs(sum - 100.0) < 1e-6,
                    "Four-mode shares do not sum to 100 at iteration " + iteration + ": " + sum);
            result.put(iteration, Map.copyOf(modes));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Double> finalPkmShares(Csv summary) {
        Map<String, Double> pkm = new LinkedHashMap<>();
        for (String mode : MODES) pkm.put(mode, value(summary, 20, "BOTH_INSIDE",
                "ALL_PLANS", "main_mode_pkm_unscaled_5pct", mode));
        double total = pkm.values().stream().mapToDouble(Double::doubleValue).sum();
        ValidateModeChoiceCalibrationConfig.require(total > 0.0,
                "Final main-mode Pkm total is not positive");
        Map<String, Double> shares = new LinkedHashMap<>();
        pkm.forEach((mode, amount) -> shares.put(mode, 100.0 * amount / total));
        return shares;
    }

    private static void writeNewSummaries(Path analysis, Summary summary) throws IOException {
        Path csv = analysis.resolve("mode_choice_round_1_summary.csv");
        Path report = analysis.resolve("mode_choice_round_1_report.md");
        ValidateModeChoiceCalibrationConfig.require(!Files.exists(csv) && !Files.exists(report),
                "Round-1 summary already exists; nothing was overwritten");
        Files.writeString(csv, summaryCsv(summary), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(report, summaryReport(summary), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    static String summaryCsv(Summary summary) {
        StringBuilder csv = new StringBuilder("section,iteration,mode,metric,value,unit\n");
        for (int iteration = 0; iteration <= 20; iteration++) for (String mode : MODES)
            row(csv, "iteration_modal_split", Integer.toString(iteration), mode,
                    "trip_modal_share", summary.shares().get(iteration).get(mode), "percent");
        for (String mode : MODES) {
            row(csv, "late_window", "16-20", mode, "mean_trip_modal_share",
                    summary.lateMean().get(mode), "percent");
            row(csv, "late_window", "16-20", mode, "minimum_trip_modal_share",
                    summary.lateMin().get(mode), "percent");
            row(csv, "late_window", "16-20", mode, "maximum_trip_modal_share",
                    summary.lateMax().get(mode), "percent");
            row(csv, "late_window", "16-20", mode, "target_trip_modal_share",
                    ValidateModeChoiceCalibrationRound1Config.TARGETS.get(mode), "percent");
            row(csv, "late_window", "16-20", mode, "mean_minus_target",
                    summary.lateDifference().get(mode), "percentage_points");
            row(csv, "secondary_validation", "20", mode, "final_main_mode_pkm_share",
                    summary.finalPkmShares().get(mode), "percent");
        }
        for (int iteration = 16; iteration <= 20; iteration++) {
            row(csv, "stuck_events", Integer.toString(iteration), "all", "event_count",
                    summary.stuckEvents().get(iteration), "events");
            row(csv, "stuck_events", Integer.toString(iteration), "all", "unique_persons",
                    summary.stuckPersons().get(iteration), "persons");
        }
        return csv.toString();
    }

    private static String summaryReport(Summary summary) {
        StringBuilder report = new StringBuilder("# Mode-choice calibration round 1 summary\n\n")
                .append("The run passed structural output validation for iterations 0--20, ")
                .append(summary.persons()).append(" persons, ").append(summary.mainTrips())
                .append(" main trips and ").append(summary.bothInsideTrips())
                .append(" primary `BOTH_INSIDE` trips. Positive stuck-event counts are reported descriptively and are not, by themselves, a validation failure.\n\n")
                .append("| Mode | Mean 16--20 | Minimum | Maximum | Target | Mean minus target | Final Pkm share |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (String mode : MODES) report.append("| ").append(mode).append(" | ")
                .append(number(summary.lateMean().get(mode))).append("% | ")
                .append(number(summary.lateMin().get(mode))).append("% | ")
                .append(number(summary.lateMax().get(mode))).append("% | ")
                .append(number(ValidateModeChoiceCalibrationRound1Config.TARGETS.get(mode))).append("% | ")
                .append(number(summary.lateDifference().get(mode))).append(" pp | ")
                .append(number(summary.finalPkmShares().get(mode))).append("% |\n");
        report.append("\nPkm shares are secondary plausibility indicators. No annualisation or comparison with annual Pkm is performed. Stuck events for iterations 16--20: ")
                .append(summary.stuckEvents()).append("; unique persons: ")
                .append(summary.stuckPersons()).append(".\n");
        return report.toString();
    }

    private static void validateLogs(Path output) throws IOException {
        List<Path> logs;
        try (var files = Files.list(output)) {
            logs = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("logfile"))
                    .sorted().toList();
        }
        ValidateModeChoiceCalibrationConfig.require(!logs.isEmpty(),
                "No MATSim logfile is available for shutdown validation");
        boolean shutdown = false;
        for (Path log : logs) {
            try (var lines = Files.lines(log)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    ValidateModeChoiceCalibrationConfig.require(
                            !LOG_FAILURE.matcher(line).find(),
                            "MATSim logfile contains failure evidence in " + log.getFileName()
                                    + ": " + line);
                    if (line.contains(REGULAR_SHUTDOWN)) shutdown = true;
                }
            }
        }
        ValidateModeChoiceCalibrationConfig.require(shutdown,
                "Regular MATSim shutdown marker is missing");
    }

    private static void requireConsistentMetricKeys(Csv csv) {
        Set<String> reference = null;
        for (int iteration = 0; iteration <= 20; iteration++) {
            Set<String> keys = new TreeSet<>();
            for (Map<String, String> row : csv.rows()) {
                if (iteration(row) != iteration) continue;
                ValidateModeChoiceCalibrationConfig.require(keys.add(metricKey(row)),
                        "Duplicate iteration metric at iteration " + iteration + ": "
                                + metricKey(row));
            }
            if (reference == null) reference = keys;
            else ValidateModeChoiceCalibrationConfig.require(reference.equals(keys),
                    "Iteration metric set changed at iteration " + iteration);
        }
    }

    private static void requireNoDuplicateRows(Csv csv, String description) {
        Set<String> keys = new HashSet<>();
        for (Map<String, String> row : csv.rows())
            ValidateModeChoiceCalibrationConfig.require(keys.add(iteration(row) + "|" + metricKey(row)),
                    description + " contains duplicate metric rows");
    }

    private static void requireZero(Csv csv, String metric, String mode) {
        long matches = 0;
        for (Map<String, String> row : csv.rows()) {
            if (!metric.equals(row.get("metric"))
                    || mode != null && !mode.equals(row.get("mode"))) continue;
            matches++;
            ValidateModeChoiceCalibrationConfig.require(
                    Double.parseDouble(row.get("value")) == 0.0,
                    metric + " is non-zero at iteration " + row.get("iteration"));
        }
        ValidateModeChoiceCalibrationConfig.require(matches > 0,
                "Required zero-check metric is missing: " + metric);
    }

    private static long integerValue(Csv csv, int iteration, String scope,
                                     String eligibility, String metric, String mode) {
        double value = value(csv, iteration, scope, eligibility, metric, mode);
        ValidateModeChoiceCalibrationConfig.require(value == Math.rint(value),
                "Expected integer metric: " + metric);
        return (long) value;
    }

    private static double value(Csv csv, int iteration, String scope,
                                String eligibility, String metric, String mode) {
        Map<String, String> row = unique(csv, iteration, scope, eligibility, metric, mode,
                Map.of(), metric + "/" + mode, ignored -> true);
        double value = Double.parseDouble(row.get("value"));
        ValidateModeChoiceCalibrationConfig.require(Double.isFinite(value),
                "Non-finite metric: " + metric + "/" + mode);
        return value;
    }

    private static Map<String, String> unique(Csv csv, int iteration, String scope,
            String eligibility, String metric, String mode, Map<String, String> extra,
            String description, java.util.function.Predicate<Map<String, String>> predicate) {
        List<Map<String, String>> matches = csv.rows().stream()
                .filter(row -> iteration(row) == iteration)
                .filter(row -> scope == null || scope.equals(row.get("spatial_scope")))
                .filter(row -> eligibility == null || eligibility.equals(row.get("plan_eligibility")))
                .filter(row -> metric == null || metric.equals(row.get("metric")))
                .filter(row -> mode == null || mode.equals(row.get("mode"))
                        || mode.equals(row.get("leg_mode")))
                .filter(row -> extra.entrySet().stream().allMatch(
                        entry -> entry.getValue().equals(row.get(entry.getKey()))))
                .filter(predicate).toList();
        ValidateModeChoiceCalibrationConfig.require(matches.size() == 1,
                "Expected one " + description + " row at iteration " + iteration
                        + "; found " + matches.size());
        return matches.getFirst();
    }

    private static void requireIterations(Csv csv, Set<Integer> expected, String description) {
        Set<Integer> actual = csv.rows().stream().map(
                ValidateAndSummarizeModeChoiceCalibrationRound1::iteration)
                .collect(java.util.stream.Collectors.toSet());
        ValidateModeChoiceCalibrationConfig.require(actual.equals(expected),
                description + " must contain exactly " + expected + "; found " + actual);
    }

    private static Csv readCsv(Path file) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(file),
                "Required round-1 output is missing: " + file);
        List<String> lines = Files.readAllLines(file);
        ValidateModeChoiceCalibrationConfig.require(lines.size() > 1,
                "Required round-1 output is empty: " + file);
        List<String> header = ModeChoiceCalibrationTargets.parseLine(lines.getFirst());
        ValidateModeChoiceCalibrationConfig.require(header.contains("iteration"),
                "CSV has no iteration column: " + file);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            List<String> values = ModeChoiceCalibrationTargets.parseLine(lines.get(line));
            ValidateModeChoiceCalibrationConfig.require(values.size() == header.size(),
                    "Malformed CSV row " + (line + 1) + " in " + file);
            Map<String, String> row = new HashMap<>();
            for (int column = 0; column < header.size(); column++)
                row.put(header.get(column), values.get(column));
            rows.add(Map.copyOf(row));
        }
        return new Csv(List.copyOf(rows));
    }

    private static int iteration(Map<String, String> row) {
        return Integer.parseInt(row.get("iteration"));
    }
    private static String metricKey(Map<String, String> row) {
        return row.getOrDefault("spatial_scope", "") + "|"
                + row.getOrDefault("plan_eligibility", "") + "|"
                + row.getOrDefault("metric", "") + "|" + row.getOrDefault("mode", "");
    }
    private static long parseLong(Map<String, String> row, String column) {
        double value = Double.parseDouble(row.get(column));
        ValidateModeChoiceCalibrationConfig.require(Double.isFinite(value)
                        && value == Math.rint(value), "Expected integer " + column);
        return (long) value;
    }
    private static void row(StringBuilder csv, String section, String iteration, String mode,
                            String metric, double value, String unit) {
        csv.append(section).append(',').append(iteration).append(',').append(mode).append(',')
                .append(metric).append(',').append(number(value)).append(',').append(unit)
                .append('\n');
    }
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    record Csv(List<Map<String, String>> rows) { }
    record Summary(long persons, long mainTrips, long bothInsideTrips,
                   Map<Integer, Map<String, Double>> shares,
                   Map<String, Double> lateMean, Map<String, Double> lateMin,
                   Map<String, Double> lateMax, Map<String, Double> lateDifference,
                   Map<String, Double> finalPkmShares,
                   Map<Integer, Long> stuckEvents, Map<Integer, Long> stuckPersons) { }
}
