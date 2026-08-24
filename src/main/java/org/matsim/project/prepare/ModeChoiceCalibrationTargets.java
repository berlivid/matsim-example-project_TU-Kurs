package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Optional empirical targets; blank values are valid and never stop analysis. */
public final class ModeChoiceCalibrationTargets {
    public static final Path DEFAULT_FILE = Path.of(
            "original-input-data/calibration/mode_choice_targets_2019.csv");
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "metric", "mode", "target_value", "unit", "spatial_definition",
            "trip_definition", "reference_year", "source", "notes");

    private ModeChoiceCalibrationTargets() { }

    public static List<Target> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) throw new IllegalArgumentException("Target schema is empty: " + path);
        List<String> header = parseLine(lines.getFirst());
        if (!header.equals(REQUIRED_COLUMNS)) {
            throw new IllegalArgumentException("Unexpected target columns: " + header);
        }
        List<Target> targets = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            if (lines.get(row).isBlank()) continue;
            List<String> values = parseLine(lines.get(row));
            if (values.size() != header.size()) {
                throw new IllegalArgumentException("Invalid target row " + (row + 1));
            }
            Map<String, String> value = java.util.stream.IntStream.range(0, header.size())
                    .boxed().collect(java.util.stream.Collectors.toMap(header::get, values::get));
            targets.add(new Target(value.get("metric"), value.get("mode"),
                    numeric(value.get("target_value")), value.get("target_value"),
                    value.get("unit"), value.get("spatial_definition"),
                    value.get("trip_definition"), value.get("reference_year"),
                    value.get("source"), value.get("notes")));
        }
        return List.copyOf(targets);
    }

    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in target CSV");
        fields.add(field.toString());
        return fields;
    }

    private static Double numeric(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Non-numeric target_value: " + value, exception);
        }
    }

    public record Target(String metric, String mode, Double numericValue, String rawValue,
                         String unit, String spatialDefinition, String tripDefinition,
                         String referenceYear, String source, String notes) {
        boolean methodCompatible() {
            return ModeChoiceCalibrationAnalysis.PRIMARY_SPATIAL_DEFINITION
                    .equals(spatialDefinition)
                    && ModeChoiceCalibrationAnalysis.MAIN_TRIP_DEFINITION.equals(tripDefinition)
                    && "2019".equals(referenceYear)
                    && expectedUnit(metric).equals(unit);
        }

        private static String expectedUnit(String metric) {
            return switch (metric.toLowerCase(Locale.ROOT)) {
                case "trip_modal_share" -> "percent";
                case "mean_trip_distance" -> "km";
                case "total_or_daily_pkm", "observed_car_pkm" ->
                        "person_km_per_service_day";
                case "observed_car_fkm" -> "vehicle_km_per_service_day";
                case "car_occupancy_factor" -> "persons_per_car";
                default -> "";
            };
        }
    }
}
