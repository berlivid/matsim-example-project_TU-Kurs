package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Versioned, executable Round-4 update derived from preserved Round-3 late means. */
final class ResidentModeChoiceRound4Specification {
    static final Path ROUND_3_ANALYSIS =
            ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT.resolve("analysis");
    static final Path SPECIFICATION = Path.of(
            "docs/calibration/resident_mode_choice_calibration_round_4.csv");
    static final String REFERENCE_MODE = "car";
    static final double DAMPING_FACTOR = 0.5;
    static final Map<String, Double> ROUND_3_LATE_MEANS = orderedMap(
            27.950996074, 58.556565363, 8.107023411, 5.385415152);
    static final Map<String, Double> ROUND_3_CONSTANTS = orderedMap(
            0.0, 0.724680779, 0.267435138, 2.803360913);
    static final Map<String, Double> ROUND_4_CONSTANTS = orderedMap(
            0.0, 0.180757242, 0.568301786, 3.452586783);
    static final int EXPECTED_INNOVATION_DISABLE_AFTER_ITERATION = 48;
    private static final double SHARE_TOLERANCE = 0.0000000005;
    private static final double UPDATE_TOLERANCE = 0.0000000000006;
    private static final double CONSTANT_ROUNDING_TOLERANCE = 0.0000000005;

    private ResidentModeChoiceRound4Specification() { }

    static Validation validate() throws IOException {
        validateFormulaAndSpecification();
        return validateRound3Analysis();
    }

    static double relativeLogRatioUpdate(String mode) {
        double modeRatio = ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(mode)
                / ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(REFERENCE_MODE);
        double observedRatio = ROUND_3_LATE_MEANS.get(mode)
                / ROUND_3_LATE_MEANS.get(REFERENCE_MODE);
        return Math.log(modeRatio / observedRatio);
    }

    static double dampedUpdate(String mode) {
        return DAMPING_FACTOR * relativeLogRatioUpdate(mode);
    }

    static double cumulativeConstant(String mode) {
        return ROUND_3_CONSTANTS.get(mode) + dampedUpdate(mode);
    }

    private static void validateFormulaAndSpecification() throws IOException {
        require(Files.isRegularFile(SPECIFICATION),
                "Missing Round-4 calibration specification: " + SPECIFICATION);
        Csv csv = Csv.read(SPECIFICATION);
        require(csv.rows().size() == 4,
                "Round-4 specification must contain exactly four modes");
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, String> row : csv.rows()) {
            String mode = row.get("mode");
            require(ResidentModeChoiceCalibrationTargets.MODES.contains(mode)
                            && seen.add(mode),
                    "Unexpected or duplicate mode in Round-4 specification: " + mode);
            requireClose(number(row, "round_3_constant"), ROUND_3_CONSTANTS.get(mode),
                    1e-12, "Round-3 constant differs for " + mode);
            requireClose(number(row, "round_3_late_mean_physical_trip_share_percent"),
                    ROUND_3_LATE_MEANS.get(mode), SHARE_TOLERANCE,
                    "Round-3 late mean differs for " + mode);
            requireClose(number(row, "target_trip_share_percent"),
                    ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Trip target differs for " + mode);
            requireClose(number(row, "relative_log_ratio_update"),
                    relativeLogRatioUpdate(mode), UPDATE_TOLERANCE,
                    "Relative log-ratio update differs for " + mode);
            requireClose(number(row, "damping_factor"), DAMPING_FACTOR, 1e-12,
                    "Damping factor differs for " + mode);
            requireClose(number(row, "damped_update"), dampedUpdate(mode),
                    UPDATE_TOLERANCE, "Damped update differs for " + mode);
            requireClose(number(row, "cumulative_round_4_constant"),
                    ROUND_4_CONSTANTS.get(mode), 1e-12,
                    "Applied Round-4 constant differs for " + mode);
            requireClose(cumulativeConstant(mode), ROUND_4_CONSTANTS.get(mode),
                    CONSTANT_ROUNDING_TOLERANCE,
                    "Formula does not reproduce the rounded Round-4 constant for " + mode);
            require(REFERENCE_MODE.equals(row.get("reference_mode")),
                    "Reference mode differs for " + mode);
            require(!row.get("round_3_analysis_provenance").isBlank()
                            && !row.get("method_provenance").isBlank(),
                    "Missing Round-4 provenance for " + mode);
        }
        require(seen.equals(Set.copyOf(ResidentModeChoiceCalibrationTargets.MODES)),
                "Round-4 specification mode set is incomplete: " + seen);
    }

    private static Validation validateRound3Analysis() throws IOException {
        Csv late = Csv.read(ROUND_3_ANALYSIS.resolve(
                "resident_mode_choice_late_iteration_statistics.csv"));
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            List<Map<String, String>> rows = late.rows().stream()
                    .filter(row -> "resident_trip_share".equals(row.get("metric")))
                    .filter(row -> mode.equals(row.get("mode"))).toList();
            require(rows.size() == 1,
                    "Expected one Round-3 late trip-share row for " + mode);
            Map<String, String> row = rows.getFirst();
            require("51".equals(row.get("first_iteration"))
                            && "60".equals(row.get("last_iteration"))
                            && "10".equals(row.get("iterations")),
                    "Round-3 late window must be exactly iterations 51..60");
            requireClose(number(row, "mean"), ROUND_3_LATE_MEANS.get(mode),
                    SHARE_TOLERANCE, "Preserved Round-3 late mean changed for " + mode);
            requireClose(number(row, "target_value"),
                    ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Preserved Round-3 target changed for " + mode);
        }

        Csv review = Csv.read(ROUND_3_ANALYSIS.resolve(
                "resident_mode_choice_round_3_calibration_review.csv"));
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            List<Map<String, String>> rows = review.rows().stream()
                    .filter(row -> mode.equals(row.get("mode"))).toList();
            require(rows.size() == 1,
                    "Expected one Round-3 calibration-review row for " + mode);
            String expectedConvergence = "walk".equals(mode)
                    ? "CONVERGED" : "NOT_CONVERGED";
            require(expectedConvergence.equals(rows.getFirst().get("convergence_status"))
                            && "OUTSIDE_TARGET_TOLERANCE".equals(
                            rows.getFirst().get("target_fit_status")),
                    "Preserved Round-3 convergence/target-fit evidence changed for "
                            + mode);
        }

        Csv metrics = Csv.read(ROUND_3_ANALYSIS.resolve(
                "resident_mode_choice_iteration_metrics.csv"));
        TreeMap<Integer, Integer> rowsByIteration = new TreeMap<>();
        TreeMap<Key, Map<String, String>> indexed = new TreeMap<>();
        for (Map<String, String> row : metrics.rows()) {
            int iteration = Integer.parseInt(row.get("iteration"));
            rowsByIteration.merge(iteration, 1, Integer::sum);
            Key key = new Key(iteration, row.get("metric"), row.get("dimension"));
            require(indexed.put(key, row) == null,
                    "Duplicate Round-3 analysis row: " + key);
        }
        Set<Integer> expectedIterations = IntStream.rangeClosed(0, 60).boxed()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        require(rowsByIteration.keySet().equals(expectedIterations),
                "Round-3 analysis must contain exactly iterations 0..60");
        require(rowsByIteration.values().stream().allMatch(count -> count == 57),
                "Round-3 analysis contains incomplete iteration rows");
        for (int iteration : expectedIterations) {
            requireClose(value(indexed, iteration, "resident_persons", "all"),
                    68_770, 0.0, "Resident count changed at iteration " + iteration);
            requireClose(value(indexed, iteration, "resident_main_trips", "all"),
                    137_540, 0.0, "Resident trip count changed at iteration " + iteration);
        }
        return new Validation(61, 68_770, 137_540, 51, 60);
    }

    private static double value(Map<Key, Map<String, String>> rows, int iteration,
                                String metric, String dimension) {
        Map<String, String> row = rows.get(new Key(iteration, metric, dimension));
        require(row != null, "Missing Round-3 analysis row: " + iteration + "/"
                + metric + "/" + dimension);
        return Double.parseDouble(row.get("value"));
    }

    private static double number(Map<String, String> row, String field) {
        String value = row.get(field);
        require(value != null && !value.isBlank(), "Missing numeric field " + field);
        return Double.parseDouble(value);
    }

    private static Map<String, Double> orderedMap(double car, double pt,
                                                   double bike, double walk) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<>();
        values.put("car", car);
        values.put("pt", pt);
        values.put("bike", bike);
        values.put("walk", walk);
        return Map.copyOf(values);
    }

    private static void requireClose(double actual, double expected, double tolerance,
                                     String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": " + actual + " != " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Validation(int iterations, long residents, long residentTrips,
                      int lateFirstIteration, int lateLastIteration) { }

    private record Key(int iteration, String metric, String dimension)
            implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byIteration = Integer.compare(iteration, other.iteration);
            if (byIteration != 0) return byIteration;
            int byMetric = metric.compareTo(other.metric);
            return byMetric != 0 ? byMetric : dimension.compareTo(other.dimension);
        }
    }

    private record Csv(List<Map<String, String>> rows) {
        static Csv read(Path path) throws IOException {
            require(Files.isRegularFile(path), "Missing required Round-4 evidence: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty required Round-4 evidence: " + path);
            List<String> header = List.of(lines.getFirst().split(",", -1));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).isBlank()) continue;
                String[] fields = lines.get(line).split(",", -1);
                require(fields.length == header.size(),
                        "Malformed CSV row " + (line + 1) + " in " + path);
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                for (int column = 0; column < fields.length; column++) {
                    row.put(header.get(column), fields[column]);
                }
                rows.add(Map.copyOf(row));
            }
            return new Csv(List.copyOf(rows));
        }
    }
}
