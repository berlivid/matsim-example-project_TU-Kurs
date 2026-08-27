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

/** Versioned and executable specification for the second productive resident update. */
final class ResidentModeChoiceRound2Specification {
    static final Path ROUND_1_ANALYSIS =
            ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT.resolve("analysis");
    static final Path SPECIFICATION = Path.of(
            "docs/calibration/resident_mode_choice_calibration_round_2.csv");
    static final String REFERENCE_MODE = "car";
    static final double DAMPING_FACTOR = 0.5;
    static final Map<String, Double> ROUND_1_SHARES = orderedMap(
            53.544423440, 15.002908245, 27.834811691, 3.617856624);
    static final Map<String, Double> ROUND_1_CONSTANTS = orderedMap(
            0.0, 0.391817, -0.104735, 0.583522);
    static final Map<String, Double> ROUND_2_CONSTANTS = orderedMap(
            0.0, 0.853797, -0.095617, 1.756684);
    static final int EXPECTED_INNOVATION_DISABLE_ITERATION = 48;
    private static final double SHARE_TOLERANCE = 0.0000000005;
    private static final double CSV_UPDATE_TOLERANCE = 0.0000000000005;
    private static final double CONSTANT_ROUNDING_TOLERANCE = 0.0000005;

    private ResidentModeChoiceRound2Specification() { }

    static Validation validate() throws IOException {
        validateFormulaAndSpecification();
        return validateRound1Analysis();
    }

    static double undampedUpdate(String mode) {
        double modeTerm = Math.log(ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(mode) / ROUND_1_SHARES.get(mode));
        double referenceTerm = Math.log(ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(REFERENCE_MODE)
                / ROUND_1_SHARES.get(REFERENCE_MODE));
        return modeTerm - referenceTerm;
    }

    static double dampedUpdate(String mode) {
        return DAMPING_FACTOR * undampedUpdate(mode);
    }

    static double cumulativeConstant(String mode) {
        return ROUND_1_CONSTANTS.get(mode) + dampedUpdate(mode);
    }

    private static void validateFormulaAndSpecification() throws IOException {
        require(Files.isRegularFile(SPECIFICATION),
                "Missing Round-2 calibration specification: " + SPECIFICATION);
        Csv csv = Csv.read(SPECIFICATION);
        require(csv.rows().size() == 4,
                "Round-2 specification must contain exactly four modes");
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, String> row : csv.rows()) {
            String mode = row.get("mode");
            require(ResidentModeChoiceCalibrationTargets.MODES.contains(mode)
                            && seen.add(mode),
                    "Unexpected or duplicate mode in Round-2 specification: " + mode);
            requireClose(number(row, "round_1_constant"), ROUND_1_CONSTANTS.get(mode),
                    1e-12, "Round-1 constant differs for " + mode);
            requireClose(number(row, "round_1_final_physical_trip_share_percent"),
                    ROUND_1_SHARES.get(mode), SHARE_TOLERANCE,
                    "Round-1 final physical share differs for " + mode);
            requireClose(number(row, "target_trip_share_percent"),
                    ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Trip target differs for " + mode);
            requireClose(number(row, "undamped_reference_log_ratio_update"),
                    undampedUpdate(mode), CSV_UPDATE_TOLERANCE,
                    "Undamped Round-2 update differs for " + mode);
            requireClose(number(row, "damping_factor"), DAMPING_FACTOR, 1e-12,
                    "Damping factor differs for " + mode);
            requireClose(number(row, "damped_update"), dampedUpdate(mode),
                    CSV_UPDATE_TOLERANCE, "Damped Round-2 update differs for " + mode);
            requireClose(number(row, "cumulative_round_2_constant"),
                    ROUND_2_CONSTANTS.get(mode), 1e-12,
                    "Applied cumulative Round-2 constant differs for " + mode);
            requireClose(cumulativeConstant(mode), ROUND_2_CONSTANTS.get(mode),
                    CONSTANT_ROUNDING_TOLERANCE,
                    "Formula does not reproduce approved rounded Round-2 constant for " + mode);
            require(REFERENCE_MODE.equals(row.get("reference_mode")),
                    "Reference mode differs for " + mode);
            require(!row.get("round_1_analysis_provenance").isBlank()
                            && !row.get("method_provenance").isBlank(),
                    "Missing Round-2 provenance for " + mode);
        }
        require(seen.equals(Set.copyOf(ResidentModeChoiceCalibrationTargets.MODES)),
                "Round-2 specification mode set is incomplete: " + seen);
    }

    private static Validation validateRound1Analysis() throws IOException {
        Csv metrics = Csv.read(ROUND_1_ANALYSIS.resolve(
                "resident_mode_choice_iteration_metrics.csv"));
        TreeMap<Integer, Integer> rowsByIteration = new TreeMap<>();
        TreeMap<Key, Map<String, String>> indexed = new TreeMap<>();
        for (Map<String, String> row : metrics.rows()) {
            int iteration = Integer.parseInt(row.get("iteration"));
            rowsByIteration.merge(iteration, 1, Integer::sum);
            Key key = new Key(iteration, row.get("metric"), row.get("dimension"));
            require(indexed.put(key, row) == null,
                    "Duplicate Round-1 analysis row: " + key);
        }
        Set<Integer> expectedIterations = IntStream.rangeClosed(0, 40).boxed()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        require(rowsByIteration.keySet().equals(expectedIterations),
                "Round-1 analysis must contain exactly iterations 0..40: "
                        + rowsByIteration.keySet());
        require(rowsByIteration.values().stream().allMatch(count -> count == 57),
                "Round-1 analysis has incomplete iteration rows: " + rowsByIteration);
        for (int iteration : expectedIterations) {
            requireClose(value(indexed, iteration, "resident_persons", "all"),
                    68_770, 0.0, "Resident count changed at iteration " + iteration);
            requireClose(value(indexed, iteration, "resident_main_trips", "all"),
                    137_540, 0.0, "Resident trip count changed at iteration " + iteration);
        }
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            requireClose(value(indexed, 40, "resident_physical_trip_share", mode),
                    ROUND_1_SHARES.get(mode), SHARE_TOLERANCE,
                    "Authoritative Round-1 iteration-40 share changed for " + mode);
            Map<String, String> pkm = indexed.get(new Key(40, "resident_pkm_share", mode));
            require(pkm != null, "Missing Round-1 iteration-40 Pkm share for " + mode);
            requireClose(Double.parseDouble(pkm.get("target_value")),
                    ResidentModeChoiceCalibrationTargets.NORMALIZED_PKM_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Pkm target changed for " + mode);
        }

        Csv primary = Csv.read(ROUND_1_ANALYSIS.resolve(
                "resident_mode_choice_final_primary.csv"));
        Csv sensitivity = Csv.read(ROUND_1_ANALYSIS.resolve(
                "resident_mode_choice_final_stuck_sensitivity.csv"));
        long primaryTrips = sumTrips(primary);
        long sensitivityTrips = sumTrips(sensitivity);
        require(primaryTrips == 137_540 && primaryTrips - sensitivityTrips == 7,
                "Round-1 sensitivity must exclude exactly 7 of 137,540 resident trips");

        Csv stuck = Csv.read(ROUND_1_ANALYSIS.resolve(
                "resident_stuck_events_by_iteration_and_mode.csv"));
        List<Map<String, String>> finalAll = stuck.rows().stream()
                .filter(row -> "40".equals(row.get("iteration")))
                .filter(row -> "all".equals(row.get("routing_mode"))).toList();
        require(finalAll.size() == 1,
                "Round-1 stuck history must contain one all-mode iteration-40 row");
        long affectedTrips = Long.parseLong(finalAll.getFirst()
                .get("affected_resident_main_trips"));
        require(affectedTrips == 7,
                "Round-1 iteration 40 must contain exactly 7 stuck-affected trips");
        return new Validation(41, 68_770, primaryTrips, affectedTrips);
    }

    private static long sumTrips(Csv csv) {
        return csv.rows().stream().mapToLong(
                row -> Long.parseLong(row.get("trip_count"))).sum();
    }

    private static double value(Map<Key, Map<String, String>> rows, int iteration,
                                String metric, String dimension) {
        Map<String, String> row = rows.get(new Key(iteration, metric, dimension));
        require(row != null, "Missing Round-1 analysis row: " + iteration + "/"
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
                      long affectedTrips) { }

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
            require(Files.isRegularFile(path), "Missing required Round-2 evidence: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty required Round-2 evidence: " + path);
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
