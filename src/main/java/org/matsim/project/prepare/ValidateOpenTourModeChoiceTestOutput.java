package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Read-only, fail-closed validation of the completed five-iteration server test. */
public final class ValidateOpenTourModeChoiceTestOutput {
    public static final Path OUTPUT = Path.of(ValidateOpenTourModeChoiceTestConfig.OUTPUT_DIRECTORY);
    private static final Set<Integer> EXPECTED_ITERATIONS = Set.of(0, 1, 2, 3, 4, 5);
    private static final Pattern LOG_FAILURE = Pattern.compile(
            "(?i)(\\bERROR\\b|Exception|Mobsim did not complete normally|unexpected shutdown)");

    private ValidateOpenTourModeChoiceTestOutput() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only output validator accepts no arguments");
        Result result = validate(OUTPUT);
        System.out.printf(Locale.ROOT,
                "OPEN-TOUR MODE-CHOICE TEST OUTPUT VALIDATION PASS%n"
                        + "iterations=0..5 openPersons=%d capable=%d unresolved=%d "
                        + "capableBothInsideTrips=%d unresolvedBothInsideTrips=%d "
                        + "changedModeSignatures=%d%n"
                        + "baselineModes=%s%nfinalModes=%s%n"
                        + "chainEndAway=car:%d bike:%d (reported methodological effect)%n",
                result.openPersons(), result.capablePersons(), result.notCapablePersons(),
                result.capableBothInsideTrips(), result.notCapableBothInsideTrips(),
                result.changedModeSignatures(),
                result.baselineModes(), result.finalModes(),
                result.carEndsAway(), result.bikeEndsAway());
    }

    static Result validate(Path output) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(output),
                "Open-tour test output is missing: " + output);
        Path analysis = output.resolve("analysis");
        CsvTable history = readLongCsv(analysis.resolve("mode_choice_iteration_metrics.csv"),
                List.of("iteration", "spatial_scope", "plan_eligibility", "metric", "mode", "value"));
        requireCompleteHistory(history, "calibration metric history");
        CsvTable summary = readLongCsv(analysis.resolve("mode_choice_final_summary.csv"),
                List.of("iteration", "spatial_scope", "plan_eligibility", "metric", "mode", "value"));
        ValidateModeChoiceCalibrationConfig.require(summary.iterations().equals(Set.of(5)),
                "Final summary must contain iteration 5 only: " + summary.iterations());
        requireUniqueMetricKeys(summary, "final summary");
        requireZeroMetric(history, "invalid_stage_distances", null);
        requireZeroMetric(history, "invalid_main_trip_distances", null);
        requireZeroMetric(history, "trip_modal_share", "unknown");

        CsvTable diagnostic = readLongCsv(
                analysis.resolve("open_tour_iteration_diagnostic.csv"),
                List.of("iteration", "metric", "mode", "value"));
        requireCompleteHistory(diagnostic, "open-tour diagnostic history");
        requireZeroMetric(diagnostic, "unknown_main_modes", null);
        requireZeroMetric(diagnostic, "stuck_events_cumulative", null);
        requireZeroMetric(diagnostic, "chain_resource_jump_persons", null);
        requireZeroMetric(diagnostic, "chain_location_unverifiable_persons", null);
        requireZeroMetric(diagnostic, "capability_location_unverifiable", null);

        Completion completion = readCompletion(
                analysis.resolve("open_tour_test_completion.csv"));
        ValidateModeChoiceCalibrationConfig.require(completion.lastIteration() == 5,
                "Controller did not finish iteration 5");
        ValidateModeChoiceCalibrationConfig.require(!completion.unexpectedShutdown(),
                "Controller reported an unexpected shutdown");
        ValidateModeChoiceCalibrationConfig.require(completion.stuckEvents() == 0,
                "PersonStuck events occurred: " + completion.stuckEvents());
        validateLogs(output);

        long open = value(diagnostic, 5, "original_open_persons", "all");
        long capable = value(diagnostic, 5, "mode_choice_capable_persons", "all");
        long notCapable = value(diagnostic, 5,
                "still_not_mode_choice_capable_persons", "all");
        long capableBothInside = value(diagnostic, 5,
                "mode_choice_capable_both_inside_main_trips", "all");
        long notCapableTrips = value(diagnostic, 5,
                "still_not_mode_choice_capable_main_trips", "all");
        long notCapableBothInside = value(diagnostic, 5,
                "still_not_mode_choice_capable_both_inside_main_trips", "all");
        long changed = value(diagnostic, 5, "persons_with_changed_mode_signature", "all");
        ValidateModeChoiceCalibrationConfig.require(
                open == OpenTourModeChoiceTestDiagnostics.EXPECTED_OPEN_PERSONS,
                "Original open-person cohort changed: " + open);
        ValidateModeChoiceCalibrationConfig.require(
                capable == OpenTourModeChoiceTestDiagnostics.EXPECTED_OPEN_PERSONS,
                "Not all originally open persons have a valid MATSim choice set: " + capable);
        ValidateModeChoiceCalibrationConfig.require(notCapable == 0,
                "Some originally open persons remain without a choice set: " + notCapable);
        ValidateModeChoiceCalibrationConfig.require(notCapableTrips == 0
                        && notCapableBothInside == 0,
                "Trips remain outside valid mode-choice sets: all=" + notCapableTrips
                        + " BOTH_INSIDE=" + notCapableBothInside);
        ValidateModeChoiceCalibrationConfig.require(
                capableBothInside == OpenTourModeChoiceTestDiagnostics.EXPECTED_OPEN_BOTH_INSIDE_TRIPS,
                "Not all originally open BOTH_INSIDE trips are in capable plans: "
                        + capableBothInside);
        ValidateModeChoiceCalibrationConfig.require(changed > 0,
                "No originally open person has a changed main-mode signature after five iterations");
        ValidateModeChoiceCalibrationConfig.require(
                value(diagnostic, 0, "baseline_both_inside_main_trips", "car")
                        + value(diagnostic, 0, "baseline_both_inside_main_trips", "pt")
                        + value(diagnostic, 0, "baseline_both_inside_main_trips", "walk")
                        + value(diagnostic, 0, "baseline_both_inside_main_trips", "bike")
                        == OpenTourModeChoiceTestDiagnostics.EXPECTED_OPEN_BOTH_INSIDE_TRIPS,
                "Original open-plan BOTH_INSIDE cohort changed");

        Map<String, Long> baselineModes = modes(diagnostic, 5, "baseline_main_trips");
        Map<String, Long> finalModes = modes(diagnostic, 5, "current_main_trips");
        return new Result(open, capable, notCapable, capableBothInside,
                notCapableBothInside, changed,
                baselineModes, finalModes,
                value(diagnostic, 5, "chain_end_away_from_initial_persons", "car"),
                value(diagnostic, 5, "chain_end_away_from_initial_persons", "bike"));
    }

    private static void requireCompleteHistory(CsvTable table, String description) {
        ValidateModeChoiceCalibrationConfig.require(table.iterations().equals(EXPECTED_ITERATIONS),
                description + " must contain exactly iterations 0..5: " + table.iterations());
        Set<String> reference = null;
        for (int iteration = 0; iteration <= 5; iteration++) {
            Set<String> keys = metricKeys(table, iteration);
            ValidateModeChoiceCalibrationConfig.require(!keys.isEmpty(),
                    description + " is empty at iteration " + iteration);
            if (reference == null) reference = keys;
            else ValidateModeChoiceCalibrationConfig.require(reference.equals(keys),
                    description + " does not contain one complete, consistent metric set at iteration "
                            + iteration);
        }
    }

    private static void requireUniqueMetricKeys(CsvTable table, String description) {
        for (int iteration : table.iterations()) metricKeys(table, iteration);
        ValidateModeChoiceCalibrationConfig.require(!table.duplicateKey(),
                description + " contains duplicate metric rows");
    }

    private static Set<String> metricKeys(CsvTable table, int iteration) {
        Set<String> keys = new HashSet<>();
        for (Map<String, String> row : table.rows()) {
            if (Integer.parseInt(row.get("iteration")) != iteration) continue;
            String key = row.getOrDefault("spatial_scope", "") + "|"
                    + row.getOrDefault("plan_eligibility", "") + "|"
                    + row.get("metric") + "|" + row.get("mode");
            ValidateModeChoiceCalibrationConfig.require(keys.add(key),
                    "Duplicate metric row at iteration " + iteration + ": " + key);
        }
        return keys;
    }

    private static void requireZeroMetric(CsvTable table, String metric, String mode) {
        long matches = 0;
        for (Map<String, String> row : table.rows()) {
            if (!metric.equals(row.get("metric"))
                    || mode != null && !mode.equals(row.get("mode"))) continue;
            matches++;
            double value = Double.parseDouble(row.get("value"));
            ValidateModeChoiceCalibrationConfig.require(value == 0.0,
                    metric + " is non-zero at iteration " + row.get("iteration")
                            + " mode " + row.get("mode") + ": " + value);
        }
        ValidateModeChoiceCalibrationConfig.require(matches > 0,
                "Required validation metric is missing: " + metric);
    }

    private static long value(CsvTable table, int iteration, String metric, String mode) {
        List<Map<String, String>> matches = table.rows().stream()
                .filter(row -> Integer.parseInt(row.get("iteration")) == iteration)
                .filter(row -> metric.equals(row.get("metric")))
                .filter(row -> mode.equals(row.get("mode"))).toList();
        ValidateModeChoiceCalibrationConfig.require(matches.size() == 1,
                "Expected one " + metric + "/" + mode + " row at iteration " + iteration);
        double value = Double.parseDouble(matches.getFirst().get("value"));
        ValidateModeChoiceCalibrationConfig.require(Double.isFinite(value)
                        && value == Math.rint(value),
                "Expected integer diagnostic value for " + metric + "/" + mode);
        return (long) value;
    }

    private static Map<String, Long> modes(CsvTable table, int iteration, String metric) {
        TreeMap<String, Long> result = new TreeMap<>();
        for (String mode : List.of("car", "pt", "walk", "bike")) {
            result.put(mode, value(table, iteration, metric, mode));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Completion readCompletion(Path file) throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(file),
                "Completion evidence is missing: " + file);
        List<String> lines = Files.readAllLines(file);
        ValidateModeChoiceCalibrationConfig.require(lines.size() == 2,
                "Completion evidence must contain exactly one data row");
        List<String> values = ModeChoiceCalibrationTargets.parseLine(lines.get(1));
        return new Completion(Integer.parseInt(values.get(0)),
                Boolean.parseBoolean(values.get(1)), Long.parseLong(values.get(2)));
    }

    private static void validateLogs(Path output) throws IOException {
        List<Path> logs;
        try (var stream = Files.list(output)) {
            logs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("logfile"))
                    .sorted().toList();
        }
        ValidateModeChoiceCalibrationConfig.require(!logs.isEmpty(),
                "No MATSim logfile is available for exception/abort validation");
        for (Path log : logs) {
            for (String line : Files.readAllLines(log)) {
                ValidateModeChoiceCalibrationConfig.require(!LOG_FAILURE.matcher(line).find(),
                        "MATSim logfile contains failure evidence in " + log.getFileName()
                                + ": " + line);
            }
        }
    }

    private static CsvTable readLongCsv(Path file, List<String> requiredColumns)
            throws IOException {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(file),
                "Required output is missing: " + file);
        List<String> lines = Files.readAllLines(file);
        ValidateModeChoiceCalibrationConfig.require(lines.size() > 1,
                "Required output is empty: " + file);
        List<String> header = ModeChoiceCalibrationTargets.parseLine(lines.getFirst());
        requiredColumns.forEach(column -> ValidateModeChoiceCalibrationConfig.require(
                header.contains(column), "Missing column " + column + " in " + file));
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        Set<Integer> iterations = new HashSet<>();
        boolean duplicate = false;
        Set<String> rawKeys = new HashSet<>();
        for (int line = 1; line < lines.size(); line++) {
            List<String> values = ModeChoiceCalibrationTargets.parseLine(lines.get(line));
            ValidateModeChoiceCalibrationConfig.require(values.size() == header.size(),
                    "Malformed CSV row " + (line + 1) + " in " + file);
            Map<String, String> row = new HashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), values.get(column));
            }
            int iteration = Integer.parseInt(row.get("iteration"));
            iterations.add(iteration);
            String rawKey = iteration + "|" + row.getOrDefault("spatial_scope", "") + "|"
                    + row.getOrDefault("plan_eligibility", "") + "|"
                    + row.get("metric") + "|" + row.get("mode");
            if (!rawKeys.add(rawKey)) duplicate = true;
            rows.add(Map.copyOf(row));
        }
        return new CsvTable(List.copyOf(rows), Set.copyOf(iterations), duplicate);
    }

    record Result(long openPersons, long capablePersons, long notCapablePersons,
                  long capableBothInsideTrips, long notCapableBothInsideTrips,
                  long changedModeSignatures,
                  Map<String, Long> baselineModes, Map<String, Long> finalModes,
                  long carEndsAway, long bikeEndsAway) { }

    private record CsvTable(List<Map<String, String>> rows, Set<Integer> iterations,
                            boolean duplicateKey) { }

    private record Completion(int lastIteration, boolean unexpectedShutdown,
                              long stuckEvents) { }
}
