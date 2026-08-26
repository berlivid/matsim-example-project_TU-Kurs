package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Versioned and executable specification for the first productive resident update. */
final class ResidentModeChoiceRound1Specification {
    static final Path INITIAL_ANALYSIS = ValidateResidentModeChoiceCalibrationConfig.OUTPUT
            .resolve("analysis");
    static final Path SPECIFICATION = Path.of(
            "docs/calibration/resident_mode_choice_calibration_round_1.csv");
    static final String REFERENCE_MODE = "car";
    static final double DAMPING_FACTOR = 0.5;
    static final Map<String, Double> INITIAL_SHARES = orderedMap(
            45.559837138, 14.688817798, 29.740439145, 10.010905918);
    static final Map<String, Double> APPLIED_CONSTANTS = orderedMap(
            0.0, 0.391817, -0.104735, 0.583522);
    private static final double SHARE_TOLERANCE = 0.0000000005;
    private static final double CONSTANT_ROUNDING_TOLERANCE = 0.0000005;

    private ResidentModeChoiceRound1Specification() { }

    static Validation validate() throws IOException {
        validateFormulaAndSpecification();
        return validateInitialAnalysis();
    }

    static double undampedUpdate(String mode) {
        double modeTerm = Math.log(ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(mode) / INITIAL_SHARES.get(mode));
        double referenceTerm = Math.log(ResidentModeChoiceCalibrationTargets
                .TRIP_SHARE_PERCENT.get(REFERENCE_MODE)
                / INITIAL_SHARES.get(REFERENCE_MODE));
        return modeTerm - referenceTerm;
    }

    static double dampedUpdate(String mode) {
        return DAMPING_FACTOR * undampedUpdate(mode);
    }

    private static void validateFormulaAndSpecification() throws IOException {
        require(Files.isRegularFile(SPECIFICATION),
                "Missing Round-1 calibration specification: " + SPECIFICATION);
        Csv csv = Csv.read(SPECIFICATION);
        require(csv.rows().size() == 4, "Round-1 specification must contain four modes");
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, String> row : csv.rows()) {
            String mode = row.get("mode");
            require(ResidentModeChoiceCalibrationTargets.MODES.contains(mode)
                            && seen.add(mode),
                    "Unexpected or duplicate mode in Round-1 specification: " + mode);
            requireClose(number(row, "initial_physical_trip_share_percent"),
                    INITIAL_SHARES.get(mode), SHARE_TOLERANCE,
                    "Initial physical share differs for " + mode);
            requireClose(number(row, "target_trip_share_percent"),
                    ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Trip target differs for " + mode);
            requireClose(number(row, "undamped_reference_log_ratio_update"),
                    undampedUpdate(mode), 0.0000000000005,
                    "Undamped log-ratio update differs for " + mode);
            requireClose(number(row, "damping_factor"), DAMPING_FACTOR, 1e-12,
                    "Damping factor differs for " + mode);
            requireClose(number(row, "applied_constant"), APPLIED_CONSTANTS.get(mode),
                    1e-12, "Applied constant differs for " + mode);
            requireClose(dampedUpdate(mode), APPLIED_CONSTANTS.get(mode),
                    CONSTANT_ROUNDING_TOLERANCE,
                    "Formula does not reproduce approved rounded constant for " + mode);
            require(REFERENCE_MODE.equals(row.get("reference_mode")),
                    "Reference mode differs for " + mode);
            require(!row.get("primary_provenance").isBlank()
                            && !row.get("secondary_target_provenance").isBlank(),
                    "Missing provenance for " + mode);
        }
        require(seen.equals(Set.copyOf(ResidentModeChoiceCalibrationTargets.MODES)),
                "Round-1 specification mode set is incomplete: " + seen);
    }

    private static Validation validateInitialAnalysis() throws IOException {
        Path metricsFile = INITIAL_ANALYSIS.resolve(
                "resident_mode_choice_iteration_metrics.csv");
        Csv metrics = Csv.read(metricsFile);
        TreeMap<Integer, Integer> rowsByIteration = new TreeMap<>();
        TreeMap<Key, Map<String, String>> indexed = new TreeMap<>();
        for (Map<String, String> row : metrics.rows()) {
            int iteration = Integer.parseInt(row.get("iteration"));
            rowsByIteration.merge(iteration, 1, Integer::sum);
            Key key = new Key(iteration, row.get("metric"), row.get("dimension"));
            require(indexed.put(key, row) == null,
                    "Duplicate initial analysis row: " + key);
        }
        Set<Integer> expectedIterations = IntStream.rangeClosed(0, 20).boxed()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        require(rowsByIteration.keySet().equals(expectedIterations),
                "Initial analysis must contain exactly iterations 0..20: "
                        + rowsByIteration.keySet());
        require(rowsByIteration.values().stream().allMatch(count -> count == 57),
                "Initial analysis has incomplete iteration rows: " + rowsByIteration);
        for (int iteration : expectedIterations) {
            requireClose(value(indexed, iteration, "resident_persons", "all"),
                    68_770, 0.0, "Resident count changed at iteration " + iteration);
            requireClose(value(indexed, iteration, "resident_main_trips", "all"),
                    137_540, 0.0, "Resident trip count changed at iteration " + iteration);
        }
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            requireClose(value(indexed, 20, "resident_physical_trip_share", mode),
                    INITIAL_SHARES.get(mode), SHARE_TOLERANCE,
                    "Authoritative iteration-20 share changed for " + mode);
            Map<String, String> pkm = indexed.get(new Key(
                    20, "resident_pkm_share", mode));
            require(pkm != null, "Missing iteration-20 Pkm share for " + mode);
            requireClose(Double.parseDouble(pkm.get("target_value")),
                    ResidentModeChoiceCalibrationTargets.NORMALIZED_PKM_SHARE_PERCENT.get(mode),
                    SHARE_TOLERANCE, "Pkm target changed for " + mode);
        }

        Csv primary = Csv.read(INITIAL_ANALYSIS.resolve(
                "resident_mode_choice_final_primary.csv"));
        Csv sensitivity = Csv.read(INITIAL_ANALYSIS.resolve(
                "resident_mode_choice_final_stuck_sensitivity.csv"));
        long primaryTrips = primary.rows().stream().mapToLong(
                row -> Long.parseLong(row.get("trip_count"))).sum();
        long sensitivityTrips = sensitivity.rows().stream().mapToLong(
                row -> Long.parseLong(row.get("trip_count"))).sum();
        require(primaryTrips == 137_540 && primaryTrips - sensitivityTrips == 54,
                "Initial sensitivity must exclude exactly 54 of 137,540 resident trips");

        Csv comparison = Csv.read(INITIAL_ANALYSIS.resolve(
                "resident_mode_choice_final_sensitivity_comparison.csv"));
        double maximumModalDifference = 0.0;
        double totalPkmDifference = Double.NaN;
        double stuckTripShare = Double.NaN;
        for (Map<String, String> row : comparison.rows()) {
            require("PASS".equals(row.get("status")),
                    "Initial sensitivity contains a non-PASS row: " + row);
            switch (row.get("metric")) {
                case "trip_modal_share" -> maximumModalDifference = Math.max(
                        maximumModalDifference, Math.abs(number(row, "difference")));
                case "total_main_mode_pkm" -> totalPkmDifference = Math.abs(
                        number(row, "difference"));
                case "resident_stuck_main_trip_share" -> stuckTripShare = number(
                        row, "primary_value");
                default -> throw new IllegalStateException(
                        "Unexpected initial sensitivity metric: " + row.get("metric"));
            }
        }
        requireClose(stuckTripShare, 0.039261306, 0.0000000005,
                "Initial stuck-trip share changed");
        require(maximumModalDifference <= 0.0336,
                "Initial modal sensitivity exceeds authoritative 0.0336 pp: "
                        + maximumModalDifference);
        requireClose(totalPkmDifference, 0.0771, 0.0001,
                "Initial total-Pkm sensitivity changed");
        return new Validation(21, primaryTrips, primaryTrips - sensitivityTrips,
                stuckTripShare, maximumModalDifference, totalPkmDifference);
    }

    private static double value(Map<Key, Map<String, String>> rows, int iteration,
                                String metric, String dimension) {
        Map<String, String> row = rows.get(new Key(iteration, metric, dimension));
        require(row != null, "Missing initial analysis row: " + iteration + "/"
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

    record Validation(int iterations, long residentTrips, long affectedTrips,
                      double stuckTripSharePercent, double maximumModalSensitivityPp,
                      double totalPkmSensitivityPercent) { }

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

    private record Csv(List<String> header, List<Map<String, String>> rows) {
        static Csv read(Path path) throws IOException {
            require(Files.isRegularFile(path), "Missing required CSV: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty required CSV: " + path);
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
            return new Csv(List.copyOf(header), List.copyOf(rows));
        }
    }
}
