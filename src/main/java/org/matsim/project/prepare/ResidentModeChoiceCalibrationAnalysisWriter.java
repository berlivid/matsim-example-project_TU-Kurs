package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.AnalysisResult;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.MetricSnapshot;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.PlanEligibility;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.SpatialScope;

/** Deterministic outputs for the single productive resident calibration pipeline. */
public final class ResidentModeChoiceCalibrationAnalysisWriter {
    public static final double SAMPLE_TO_POPULATION_FACTOR = 20.0;
    public static final double DAYS_PER_YEAR_DIAGNOSTIC = 365.0;
    public static final int LATE_ITERATION_WINDOW = 10;
    public static final double LATE_TREND_REVIEW_THRESHOLD_PP_PER_ITERATION = 0.10;
    public static final double LATE_RANGE_REVIEW_THRESHOLD_PP = 1.0;
    private static final List<SpatialScope> SPATIAL_CATEGORIES = List.of(
            SpatialScope.BOTH_INSIDE,
            SpatialScope.ORIGIN_ONLY,
            SpatialScope.DESTINATION_ONLY,
            SpatialScope.BOTH_OUTSIDE,
            SpatialScope.INVALID_OR_MISSING_COORDINATE);

    private final Path analysisDirectory;

    public ResidentModeChoiceCalibrationAnalysisWriter(Path outputDirectory) {
        analysisDirectory = outputDirectory.resolve("analysis");
    }

    public void write(List<AnalysisResult> results, boolean finalResult) throws IOException {
        if (results.isEmpty()) return;
        List<AnalysisResult> ordered = validatedOrder(results);
        Files.createDirectories(analysisDirectory);
        writeAtomically(analysisDirectory.resolve(
                "resident_mode_choice_iteration_metrics.csv"), iterationMetrics(ordered));
        writeAtomically(analysisDirectory.resolve(
                "resident_mode_choice_late_iteration_statistics.csv"),
                lateIterationStatistics(ordered));
        if (finalResult) writeFinal(ordered.getLast(),
                "complete AfterMobsim selected-plan history",
                Math.min(LATE_ITERATION_WINDOW, ordered.size()));
    }

    public void writeStandaloneFinal(AnalysisResult result) throws IOException {
        Files.createDirectories(analysisDirectory);
        Path history = analysisDirectory.resolve("resident_mode_choice_iteration_metrics.csv");
        String status = Files.isRegularFile(history)
                ? "existing listener history preserved"
                : "iteration history unavailable; standalone analysis did not invent one";
        writeFinal(result, status,
                lateIterationsRecorded(analysisDirectory.resolve(
                        "resident_mode_choice_late_iteration_statistics.csv")));
    }

    private void writeFinal(AnalysisResult result, String historyStatus,
                            int lateIterationsUsed) throws IOException {
        writeAtomically(analysisDirectory.resolve("resident_mode_choice_final_summary.csv"),
                iterationMetrics(List.of(result)));
        writeAtomically(analysisDirectory.resolve("resident_mode_choice_report.md"),
                report(result, historyStatus, lateIterationsUsed));
    }

    static String iterationMetrics(List<AnalysisResult> results) {
        StringBuilder out = new StringBuilder(
                "iteration,metric,dimension,value,unit,target_value,difference_to_target\n");
        for (AnalysisResult result : validatedOrder(results)) {
            MetricSnapshot primary = primary(result);
            row(out, result.iteration(), "resident_persons", "all",
                    primary.validPersons(), "persons", null, null);
            row(out, result.iteration(), "resident_main_trips", "all",
                    primary.mainTrips(), "trips", null, null);
            row(out, result.iteration(), "resident_physical_main_trips", "all",
                    primary.mainTrips(), "trips", null, null);
            for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
                long trips = primary.mainTripsByMode().getOrDefault(mode, 0L);
                long choiceTrips = primary.choiceMainTripsByMode().getOrDefault(mode, 0L);
                double tripShare = primary.modalSharePercent(mode);
                double choiceShare = primary.choiceModalSharePercent(mode);
                double rawPkm = primary.mainModePkm(mode);
                double pkmShare = pkmShare(primary, mode);
                double tripTarget = ResidentModeChoiceCalibrationTargets
                        .TRIP_SHARE_PERCENT.get(mode);
                double pkmTarget = ResidentModeChoiceCalibrationTargets
                        .NORMALIZED_PKM_SHARE_PERCENT.get(mode);
                row(out, result.iteration(), "resident_main_trips", mode,
                        trips, "trips", null, null);
                row(out, result.iteration(), "resident_trip_share", mode,
                        tripShare, "percent", tripTarget, tripShare - tripTarget);
                row(out, result.iteration(), "resident_physical_main_trips", mode,
                        trips, "trips", null, null);
                row(out, result.iteration(), "resident_physical_trip_share", mode,
                        tripShare, "percent", tripTarget, tripShare - tripTarget);
                row(out, result.iteration(), "resident_choice_main_trips", mode,
                        choiceTrips, "trips", null, null);
                row(out, result.iteration(), "resident_choice_trip_share", mode,
                        choiceShare, "percent", null, null);
                row(out, result.iteration(), "raw_simulated_daily_sample_pkm", mode,
                        rawPkm, "person_km_per_simulated_day", null, null);
                row(out, result.iteration(),
                        "five_percent_annualised_pkm_diagnostic", mode,
                        annualisedMillion(rawPkm), "million_person_km_per_year",
                        ResidentModeChoiceCalibrationTargets.ANNUAL_PKM_MILLION.get(mode), null);
                row(out, result.iteration(), "resident_pkm_share", mode,
                        pkmShare, "percent", pkmTarget, pkmShare - pkmTarget);
            }
            for (var transition : primary.physicalChoiceTransitions().entrySet()) {
                row(out, result.iteration(), "resident_physical_choice_transition",
                        transition.getKey().physicalMode() + "->"
                                + transition.getKey().choiceMode(),
                        transition.getValue(), "trips", null, null);
            }
            row(out, result.iteration(), "resident_pt_request_walk_only_physical_route",
                    "physical_walk_choice_pt",
                    primary.ptRequestsWithWalkOnlyPhysicalRoute(), "trips", null, null);
            for (SpatialScope scope : SPATIAL_CATEGORIES) {
                long trips = metrics(result, scope).mainTrips();
                row(out, result.iteration(), "resident_spatial_main_trips", scope.name(),
                        trips, "trips", null, null);
                row(out, result.iteration(), "resident_spatial_trip_share", scope.name(),
                        primary.mainTrips() == 0 ? Double.NaN
                                : 100.0 * trips / primary.mainTrips(),
                        "percent", null, null);
            }
            row(out, result.iteration(), "background_persons_excluded_from_targets",
                    ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
                    ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                    "persons", null, null);
            row(out, result.iteration(), "background_persons_excluded_from_targets",
                    ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND,
                    ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                    "persons", null, null);
        }
        return out.toString();
    }

    static String lateIterationStatistics(List<AnalysisResult> results) {
        List<AnalysisResult> ordered = validatedOrder(results);
        int start = Math.max(0, ordered.size() - LATE_ITERATION_WINDOW);
        List<AnalysisResult> late = ordered.subList(start, ordered.size());
        StringBuilder out = new StringBuilder(
                "metric,mode,first_iteration,last_iteration,iterations,mean,minimum,maximum,range,linear_trend_per_iteration,final_value,target_value,difference_to_target,trend_review_threshold,range_review_threshold,review_status,unit\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            statistics(out, "resident_trip_share", mode, late,
                    result -> primary(result).modalSharePercent(mode),
                    ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode), true,
                    "percentage_points");
            statistics(out, "resident_pkm_share", mode, late,
                    result -> pkmShare(primary(result), mode),
                    ResidentModeChoiceCalibrationTargets.NORMALIZED_PKM_SHARE_PERCENT.get(mode),
                    false, "percentage_points");
        }
        return out.toString();
    }

    private static void statistics(StringBuilder out, String metric, String mode,
                                   List<AnalysisResult> results,
                                   java.util.function.ToDoubleFunction<AnalysisResult> value,
                                   double target, boolean applyConvergenceCriteria,
                                   String unit) {
        List<Point> points = new ArrayList<>();
        for (AnalysisResult result : results) {
            double measured = value.applyAsDouble(result);
            if (Double.isFinite(measured)) points.add(new Point(result.iteration(), measured));
        }
        if (points.isEmpty()) return;
        double mean = points.stream().mapToDouble(Point::value).average().orElseThrow();
        double min = points.stream().mapToDouble(Point::value).min().orElseThrow();
        double max = points.stream().mapToDouble(Point::value).max().orElseThrow();
        double range = max - min;
        double trend = linearTrend(points);
        double finalValue = points.getLast().value();
        String status = applyConvergenceCriteria
                && (Math.abs(trend) > LATE_TREND_REVIEW_THRESHOLD_PP_PER_ITERATION
                || range > LATE_RANGE_REVIEW_THRESHOLD_PP)
                ? "REVIEW_REQUIRED" : applyConvergenceCriteria ? "PASS" : "SECONDARY_ONLY";
        out.append(metric).append(',').append(mode).append(',')
                .append(points.getFirst().iteration()).append(',')
                .append(points.getLast().iteration()).append(',').append(points.size())
                .append(',').append(number(mean)).append(',').append(number(min))
                .append(',').append(number(max)).append(',').append(number(range)).append(',')
                .append(number(trend)).append(',').append(number(finalValue)).append(',')
                .append(number(target)).append(',').append(number(finalValue - target)).append(',')
                .append(applyConvergenceCriteria
                        ? number(LATE_TREND_REVIEW_THRESHOLD_PP_PER_ITERATION) : "")
                .append(',').append(applyConvergenceCriteria
                        ? number(LATE_RANGE_REVIEW_THRESHOLD_PP) : "")
                .append(',').append(status).append(',').append(unit).append('\n');
    }

    private static double linearTrend(List<Point> points) {
        if (points.size() < 2) return 0.0;
        double meanX = points.stream().mapToDouble(Point::iteration).average().orElseThrow();
        double meanY = points.stream().mapToDouble(Point::value).average().orElseThrow();
        double numerator = 0.0;
        double denominator = 0.0;
        for (Point point : points) {
            numerator += (point.iteration() - meanX) * (point.value() - meanY);
            denominator += Math.pow(point.iteration() - meanX, 2);
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private static String report(AnalysisResult result, String historyStatus,
                                 int lateIterationsUsed) {
        MetricSnapshot primary = primary(result);
        StringBuilder out = new StringBuilder("# Resident mode-choice calibration analysis\n\n")
                .append("Iteration ").append(result.iteration())
                .append(" uses the complete selected scenario-plan snapshot at `AfterMobsim`, before the next replanning step. It does not use `ExperiencedPlansService`. Only persons labelled `munich_resident` enter the target metrics; regional and unresolved background persons are explicitly excluded.\n\n")
                .append("## Primary physical trip-share and secondary physical Pkm-share metrics\n\n")
                .append("The empirical targets are compared only with realized physical main modes. Choice/routing modes are reported separately below and are not target metrics.\n\n")
                .append("| Mode | Physical resident trips | Physical trip share | Trip target | Difference | Raw daily 5% physical Pkm | Annualised diagnostic | Physical Pkm share | Pkm target | Difference |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double tripShare = primary.modalSharePercent(mode);
            double pkmShare = pkmShare(primary, mode);
            double tripTarget = ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode);
            double pkmTarget = ResidentModeChoiceCalibrationTargets
                    .NORMALIZED_PKM_SHARE_PERCENT.get(mode);
            out.append("| ").append(mode).append(" | ")
                    .append(primary.mainTripsByMode().getOrDefault(mode, 0L)).append(" | ")
                    .append(number(tripShare)).append("% | ").append(number(tripTarget))
                    .append("% | ").append(number(tripShare - tripTarget)).append(" pp | ")
                    .append(number(primary.mainModePkm(mode))).append(" | ")
                    .append(number(annualisedMillion(primary.mainModePkm(mode))))
                    .append(" million/year | ").append(number(pkmShare)).append("% | ")
                    .append(number(pkmTarget)).append("% | ")
                    .append(number(pkmShare - pkmTarget)).append(" pp |\n");
        }
        out.append("\nPhysical trip shares are the primary calibration targets. Exact normalized physical passenger-kilometre shares are secondary plausibility targets. Constants are never adjusted automatically. Raw Pkm are route-distance passenger-kilometres in the simulated 5% sample day. The annualised diagnostic multiplies by 20 and 365 and divides by one million; comparison with Schröder's absolute totals is limited because the model population universe is not the full observed population total.\n\n")
                .append("## Choice/routing-mode diagnostics\n\n")
                .append("| Choice mode | Resident trips | Share |\n")
                .append("|---|---:|---:|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            out.append("| ").append(mode).append(" | ")
                    .append(primary.choiceMainTripsByMode().getOrDefault(mode, 0L))
                    .append(" | ").append(number(primary.choiceModalSharePercent(mode)))
                    .append("% |\n");
        }
        out.append("\nPhysical-versus-choice transitions:\n\n")
                .append("| Physical mode | Choice mode | Resident trips |\n")
                .append("|---|---|---:|\n");
        for (var transition : primary.physicalChoiceTransitions().entrySet()) {
            out.append("| ").append(transition.getKey().physicalMode()).append(" | ")
                    .append(transition.getKey().choiceMode()).append(" | ")
                    .append(transition.getValue()).append(" |\n");
        }
        out.append("\nPT routing requests with a walk-only realized physical route: ")
                .append(primary.ptRequestsWithWalkOnlyPhysicalRoute())
                .append(". This is a routing outcome, not by itself an endogenous mode-choice change. Choice-mode shares are diagnostic and are not compared with the empirical targets.\n\n")
                .append("## Secondary territorial breakdown\n\n")
                .append("| Category | Resident trips |\n|---|---:|\n");
        long spatialSum = 0;
        for (SpatialScope scope : SPATIAL_CATEGORIES) {
            long trips = metrics(result, scope).mainTrips();
            spatialSum += trips;
            out.append("| ").append(scope).append(" | ").append(trips).append(" |\n");
        }
        out.append("\nSpatial-category check: ").append(spatialSum).append(" = ")
                .append(primary.mainTrips()).append(" (`")
                .append(spatialSum == primary.mainTrips() ? "PASS" : "FAIL")
                .append("`). `BOTH_INSIDE` is a secondary territorial indicator, not the calibration cohort.\n\n")
                .append("## Late-iteration stability\n\n")
                .append("Late means, ranges and linear trends use the last ")
                .append(lateIterationsUsed)
                .append(" available iterations and are written to `resident_mode_choice_late_iteration_statistics.csv`. History status: ")
                .append(historyStatus).append(".\n\n")
                .append("Resident persons represented in primary metrics: ")
                .append(primary.validPersons()).append(". Regional background excluded: ")
                .append(ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND)
                .append("; unresolved no-home background excluded: ")
                .append(ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND)
                .append(". Resident stuck events by iteration and mode are written separately by the event listener.\n");
        return out.toString();
    }

    private static int lateIterationsRecorded(Path statistics) throws IOException {
        if (!Files.isRegularFile(statistics)) return 1;
        try (var lines = Files.lines(statistics, StandardCharsets.UTF_8)) {
            String first = lines.skip(1).filter(line -> !line.isBlank()).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Late-iteration statistics contain no data: " + statistics));
            String[] fields = first.split(",", -1);
            if (fields.length < 5) {
                throw new IllegalStateException(
                        "Malformed late-iteration statistics: " + statistics);
            }
            int iterations = Integer.parseInt(fields[4]);
            if (iterations < 1 || iterations > LATE_ITERATION_WINDOW) {
                throw new IllegalStateException(
                        "Invalid late-iteration count in " + statistics + ": " + iterations);
            }
            return iterations;
        }
    }

    private static List<AnalysisResult> validatedOrder(List<AnalysisResult> results) {
        List<AnalysisResult> ordered = results.stream()
                .sorted(Comparator.comparingInt(AnalysisResult::iteration)).toList();
        Set<Integer> iterations = new HashSet<>();
        for (AnalysisResult result : ordered) {
            if (!iterations.add(result.iteration())) {
                throw new IllegalArgumentException(
                        "Duplicate resident analysis iteration: " + result.iteration());
            }
        }
        return ordered;
    }

    private static MetricSnapshot primary(AnalysisResult result) {
        return metrics(result, SpatialScope.ALL_TRIPS);
    }

    private static MetricSnapshot metrics(AnalysisResult result, SpatialScope scope) {
        return result.metrics(scope, PlanEligibility.ALL_PLANS);
    }

    private static double pkmShare(MetricSnapshot metrics, String mode) {
        double total = ResidentModeChoiceCalibrationTargets.MODES.stream()
                .mapToDouble(metrics::mainModePkm).sum();
        return total == 0.0 ? Double.NaN : 100.0 * metrics.mainModePkm(mode) / total;
    }

    private static double annualisedMillion(double dailySamplePkm) {
        return dailySamplePkm * SAMPLE_TO_POPULATION_FACTOR
                * DAYS_PER_YEAR_DIAGNOSTIC / 1_000_000.0;
    }

    private static void row(StringBuilder out, int iteration, String metric,
                            String dimension, long value, String unit,
                            Double target, Double difference) {
        row(out, iteration, metric, dimension, (double) value, unit, target, difference);
    }

    private static void row(StringBuilder out, int iteration, String metric,
                            String dimension, double value, String unit,
                            Double target, Double difference) {
        out.append(iteration).append(',').append(metric).append(',').append(dimension)
                .append(',').append(number(value)).append(',').append(unit).append(',')
                .append(target == null ? "" : number(target)).append(',')
                .append(difference == null ? "" : number(difference)).append('\n');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(),
                "." + target.getFileName(), ".tmp");
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

    private record Point(int iteration, double value) { }
}
