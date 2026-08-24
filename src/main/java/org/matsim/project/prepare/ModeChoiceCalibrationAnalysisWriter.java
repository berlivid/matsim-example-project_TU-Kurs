package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.AnalysisResult;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.DistanceSource;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.GroupKey;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.MetricSnapshot;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.PlanEligibility;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.SpatialScope;

/** Deterministic, atomic CSV and Markdown output for calibration analysis. */
public final class ModeChoiceCalibrationAnalysisWriter {
    private static final List<String> MAIN_MODES = List.of("car", "pt", "walk", "bike", "unknown");
    private static final List<String> PHYSICAL_MODES = List.of(
            "car", "walk", "bike", "bus", "tram", "subway", "rail", "ferry",
            "unknown_pt", "unknown_stage");
    private static final List<String> PT_SUBMODES = List.of(
            "bus", "tram", "subway", "rail", "ferry", "unknown_pt");

    private final Path analysisDirectory;
    private final Path targetFile;

    public ModeChoiceCalibrationAnalysisWriter(Path outputDirectory) {
        this(outputDirectory, ModeChoiceCalibrationTargets.DEFAULT_FILE);
    }

    ModeChoiceCalibrationAnalysisWriter(Path outputDirectory, Path targetFile) {
        this.analysisDirectory = outputDirectory.resolve("analysis");
        this.targetFile = targetFile;
    }

    public void write(List<AnalysisResult> results, boolean finalResult) throws IOException {
        if (results.isEmpty()) return;
        Files.createDirectories(analysisDirectory);
        List<AnalysisResult> ordered = validatedOrder(results);
        writeAtomically(analysisDirectory.resolve("mode_choice_iteration_metrics.csv"),
                iterationMetrics(ordered));
        if (!finalResult) return;
        writeFinalOutputs(ordered.getLast(), ordered, "complete listener history retained");
    }

    /**
     * Writes only the final-state products of a standalone postprocessing run.
     * An existing listener history is treated as immutable evidence and is never replaced.
     */
    public void writeStandaloneFinal(AnalysisResult result) throws IOException {
        Files.createDirectories(analysisDirectory);
        Path history = analysisDirectory.resolve("mode_choice_iteration_metrics.csv");
        String historyStatus = Files.isRegularFile(history)
                ? "existing listener history preserved without modification"
                : "iteration history unavailable; standalone analysis did not invent one";
        writeFinalOutputs(result, List.of(result), historyStatus);
    }

    private void writeFinalOutputs(AnalysisResult last, List<AnalysisResult> detailResults,
                                   String historyStatus) throws IOException {
        writeAtomically(analysisDirectory.resolve("mode_choice_final_summary.csv"),
                iterationMetrics(List.of(last)));
        writeAtomically(analysisDirectory.resolve("pt_passenger_km_by_submode.csv"),
                ptPassengerKilometres(detailResults));
        writeAtomically(analysisDirectory.resolve("distance_quality.csv"),
                distanceQuality(detailResults));
        List<ModeChoiceCalibrationTargets.Target> targets =
                ModeChoiceCalibrationTargets.read(targetFile);
        TargetOutput comparison = targetComparison(last, targets);
        writeAtomically(analysisDirectory.resolve("calibration_target_comparison.csv"),
                comparison.csv());
        writeAtomically(analysisDirectory.resolve("analysis_report.md"),
                report(last, comparison.numericTargets(), historyStatus));
    }

    private static List<AnalysisResult> validatedOrder(List<AnalysisResult> results) {
        List<AnalysisResult> ordered = results.stream()
                .sorted(Comparator.comparingInt(AnalysisResult::iteration)).toList();
        Set<Integer> iterations = new HashSet<>();
        for (AnalysisResult result : ordered) {
            if (!iterations.add(result.iteration())) {
                throw new IllegalArgumentException(
                        "Duplicate analysis iteration: " + result.iteration());
            }
        }
        return ordered;
    }

    static String iterationMetrics(List<AnalysisResult> results) {
        StringBuilder csv = new StringBuilder(
                "iteration,spatial_scope,plan_eligibility,metric,mode,value,unit\n");
        for (AnalysisResult result : results) {
            for (var entry : result.groups().entrySet()) {
                GroupKey key = entry.getKey();
                MetricSnapshot metrics = entry.getValue();
                row(csv, result.iteration(), key, "valid_persons", "all",
                        metrics.validPersons(), "persons");
                row(csv, result.iteration(), key, "valid_main_trips", "all",
                        metrics.mainTrips(), "trips");
                for (String mode : MAIN_MODES) {
                    row(csv, result.iteration(), key, "main_trips", mode,
                            metrics.mainTripsByMode().getOrDefault(mode, 0L), "trips");
                    rowFinite(csv, result.iteration(), key, "trip_modal_share", mode,
                            metrics.modalSharePercent(mode), "percent");
                    row(csv, result.iteration(), key, "main_mode_pkm_unscaled_5pct", mode,
                            metrics.mainModePkm(mode), "person_km");
                    row(csv, result.iteration(), key, "main_mode_pkm_population_scaled", mode,
                            metrics.mainModePkm(mode)
                                    * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR,
                            "person_km");
                    rowFinite(csv, result.iteration(), key, "mean_trip_distance", mode,
                            metrics.meanTripLengthKm(mode), "km");
                }
                row(csv, result.iteration(), key, "total_main_mode_pkm_unscaled_5pct", "all",
                        metrics.totalMainModePkm(), "person_km");
                row(csv, result.iteration(), key, "total_main_mode_pkm_population_scaled", "all",
                        metrics.totalMainModePkm()
                                * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR,
                        "person_km");
                for (String mode : PHYSICAL_MODES) {
                    row(csv, result.iteration(), key, "physical_stage_pkm_unscaled_5pct", mode,
                            metrics.physicalStagePkm(mode), "person_km");
                    row(csv, result.iteration(), key, "physical_stage_pkm_population_scaled", mode,
                            metrics.physicalStagePkm(mode)
                                    * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR,
                            "person_km");
                }
                row(csv, result.iteration(), key, "raw_matsim_car_km_unscaled_5pct", "car",
                        metrics.physicalStagePkm("car"), "vehicle_route_km_proxy");
                row(csv, result.iteration(), key, "raw_matsim_car_km_population_scaled", "car",
                        metrics.physicalStagePkm("car")
                                * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR,
                        "vehicle_route_km_proxy");
                row(csv, result.iteration(), key, "invalid_stage_distances", "all",
                        metrics.invalidStageDistances(), "stages");
                row(csv, result.iteration(), key, "invalid_main_trip_distances", "all",
                        metrics.invalidMainTripDistances(), "trips");
            }
            GroupKey global = new GroupKey(SpatialScope.ALL_TRIPS, PlanEligibility.ALL_PLANS);
            row(csv, result.iteration(), global, "plans_with_closed_subtour", "all",
                    result.plansWithClosedSubtour(), "plans");
            row(csv, result.iteration(), global, "plans_without_closed_subtour", "all",
                    result.plansWithoutClosedSubtour(), "plans");
        }
        return csv.toString();
    }

    private static String ptPassengerKilometres(List<AnalysisResult> results) {
        StringBuilder csv = new StringBuilder(
                "iteration,spatial_scope,plan_eligibility,pt_submode,unscaled_5pct_pkm,population_scaled_pkm\n");
        for (AnalysisResult result : results) {
            for (var entry : result.groups().entrySet()) {
                for (String mode : PT_SUBMODES) {
                    double unscaled = entry.getValue().physicalStagePkm(mode);
                    csv.append(result.iteration()).append(',')
                            .append(entry.getKey().spatialScope()).append(',')
                            .append(entry.getKey().planEligibility()).append(',')
                            .append(mode).append(',').append(number(unscaled)).append(',')
                            .append(number(unscaled
                                    * ModeChoiceCalibrationAnalysis.POPULATION_SCALE_FACTOR))
                            .append('\n');
                }
            }
        }
        return csv.toString();
    }

    private static String distanceQuality(List<AnalysisResult> results) {
        StringBuilder csv = new StringBuilder(
                "iteration,spatial_scope,plan_eligibility,distance_source,count\n");
        for (AnalysisResult result : results) {
            for (var entry : result.groups().entrySet()) {
                for (DistanceSource source : DistanceSource.values()) {
                    csv.append(result.iteration()).append(',')
                            .append(entry.getKey().spatialScope()).append(',')
                            .append(entry.getKey().planEligibility()).append(',')
                            .append(source).append(',')
                            .append(entry.getValue().distanceSources().getOrDefault(source, 0L))
                            .append('\n');
                }
            }
        }
        return csv.toString();
    }

    private static TargetOutput targetComparison(AnalysisResult result,
            List<ModeChoiceCalibrationTargets.Target> targets) {
        StringBuilder csv = new StringBuilder(
                "iteration,spatial_scope,metric,mode,simulated_value,target_value,absolute_difference,percentage_point_difference,compatible_definition,note\n");
        MetricSnapshot primary = result.metrics(
                SpatialScope.BOTH_INSIDE, PlanEligibility.ALL_PLANS);
        int numeric = 0;
        for (var target : targets) {
            Double simulated = simulatedValue(target, primary);
            boolean compatible = target.methodCompatible() && simulated != null
                    && Double.isFinite(simulated);
            Double signedDifference = compatible && target.numericValue() != null
                    ? simulated - target.numericValue() : null;
            if (signedDifference != null) numeric++;
            String note;
            if (target.numericValue() == null) note = "target_value is empty; no comparison made";
            else if ("secondary".equals(target.calibrationPriority())) {
                note = "secondary plausibility reference; not used to calibrate mode constants";
            } else if (!compatible) note = "method or metric is not compatible; no comparison made";
            else note = "compatible numeric target compared";
            csv.append(result.iteration()).append(',').append(SpatialScope.BOTH_INSIDE)
                    .append(',').append(value(target.metric())).append(',')
                    .append(value(target.mode())).append(',')
                    .append(simulated == null ? "" : number(simulated)).append(',')
                    .append(target.numericValue() == null ? "" : number(target.numericValue()))
                    .append(',').append(signedDifference == null ? ""
                            : number(Math.abs(signedDifference))).append(',')
                    .append("trip_modal_share".equals(target.metric())
                            && signedDifference != null ? number(signedDifference) : "")
                    .append(',').append(compatible).append(',').append(value(note)).append('\n');
        }
        if (targets.isEmpty()) {
            csv.append(result.iteration()).append(',').append(SpatialScope.BOTH_INSIDE)
                    .append(",,,,,,,false,")
                    .append(value("target schema missing or contains no rows; analysis continues"))
                    .append('\n');
        }
        return new TargetOutput(csv.toString(), numeric);
    }

    private static Double simulatedValue(ModeChoiceCalibrationTargets.Target target,
                                         MetricSnapshot primary) {
        String mode = target.mode();
        return switch (target.metric()) {
            case "trip_modal_share" -> primary.modalSharePercent(mode);
            case "mean_trip_distance" -> primary.meanTripLengthKm(mode);
            case "annual_pkm_share" -> {
                double total = List.of("car", "pt", "bike", "walk").stream()
                        .mapToDouble(primary::mainModePkm).sum();
                yield total == 0.0 ? Double.NaN : 100.0 * primary.mainModePkm(mode) / total;
            }
            default -> null;
        };
    }

    private static String report(AnalysisResult result, int numericTargets,
                                 String historyStatus) {
        MetricSnapshot primary = result.metrics(
                SpatialScope.BOTH_INSIDE, PlanEligibility.ALL_PLANS);
        StringBuilder report = new StringBuilder("# Mode-choice calibration analysis\n\n")
                .append("Iteration: ").append(result.iteration()).append(". During a controller run, metrics use the complete selected and routed plan snapshot after mobsim and before the next iteration's replanning. These are planned/route-based metrics; a separate stuck-event record identifies plans that were not fully executed. The primary sample contains main trips with both main-activity endpoints inside or on the City of Munich boundary. Stage activities are skipped when constructing main trips.\n\n")
                .append("## Primary main-mode metrics\n\n")
                .append("| Mode | Trips | Modal share | Unscaled 5-% Pkm | Mean trip length |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (String mode : List.of("car", "pt", "walk", "bike")) {
            report.append("| ").append(mode).append(" | ")
                    .append(primary.mainTripsByMode().getOrDefault(mode, 0L)).append(" | ")
                    .append(number(primary.modalSharePercent(mode))).append("% | ")
                    .append(number(primary.mainModePkm(mode))).append(" | ")
                    .append(number(primary.meanTripLengthKm(mode))).append(" km |\n");
        }
        report.append("\nUnknown main modes: ").append(result.unknownMainModes())
                .append(". Invalid stage distances: ").append(primary.invalidStageDistances())
                .append("; invalid main-trip distances: ")
                .append(primary.invalidMainTripDistances()).append(".\n\n")
                .append("Physical-stage PT passenger-kilometres are assigned from the used TransitRoute transport mode, not line-name heuristics. Main-mode PT Pkm assign the complete door-to-door main trip to PT; these two perspectives must not be added together.\n\n")
                .append("`raw_matsim_car_km` is a model route-distance diagnostic. It is not occupancy-adjusted external-cost vehicle-kilometres. No occupancy factor or annualisation is applied.\n\n")
                .append("Iteration history status: ").append(historyStatus).append(".\n\n")
                .append("Compatible numeric targets compared: ").append(numericTargets)
                .append(". The analyzer measures outcomes and never changes calibration parameters.\n");
        return report.toString();
    }

    private static void row(StringBuilder csv, int iteration, GroupKey key,
                            String metric, String mode, long value, String unit) {
        row(csv, iteration, key, metric, mode, (double) value, unit);
    }

    private static void row(StringBuilder csv, int iteration, GroupKey key,
                            String metric, String mode, double value, String unit) {
        csv.append(iteration).append(',').append(key.spatialScope()).append(',')
                .append(key.planEligibility()).append(',').append(metric).append(',')
                .append(mode).append(',').append(number(value)).append(',').append(unit)
                .append('\n');
    }

    private static void rowFinite(StringBuilder csv, int iteration, GroupKey key,
                                  String metric, String mode, double value, String unit) {
        if (Double.isFinite(value)) row(csv, iteration, key, metric, mode, value, unit);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String value(String text) {
        if (text == null) return "";
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record TargetOutput(String csv, int numericTargets) { }
}
