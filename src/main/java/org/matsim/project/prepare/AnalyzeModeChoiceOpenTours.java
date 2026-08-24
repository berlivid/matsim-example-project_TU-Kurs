package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only aggregate diagnostic of open daily plans and MATSim 2025.0 subtour behaviour. */
public final class AnalyzeModeChoiceOpenTours {
    static final Path POPULATION = Path.of(
            "scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml");
    static final Path FINAL_SUMMARY = Path.of(
            "scenarios/munich_calibration_2019/output/mode-choice-initial/analysis/"
                    + "mode_choice_final_summary.csv");
    static final Path OUTPUT = Path.of("generated/mode_choice_open_tour_diagnostic");
    static final double RAW_COORDINATE_TOLERANCE_METRES = 1e-6;
    private static final Set<String> MODES = Set.of("car", "pt", "bike", "walk");
    private static final DefaultAnalysisMainModeIdentifier MAIN_MODE =
            new DefaultAnalysisMainModeIdentifier();

    private AnalyzeModeChoiceOpenTours() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only diagnostic accepts no arguments");
        var config = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.CONFIG.toString());
        ValidateModeChoiceCalibrationConfig.require(
                "fromSpecifiedModesToSpecifiedModes".equals(
                        config.subtourModeChoice().getBehavior().toString()),
                "Production calibration behaviour changed; diagnostic stopped");
        Result result = analyze(POPULATION, MunichMunicipalBoundary.loadDefault());
        FinalState finalState = FinalState.read(FINAL_SUMMARY);
        write(result, finalState, OUTPUT);
        System.out.print(result.consoleSummary(finalState));
    }

    static Result analyze(Path population, MunichMunicipalBoundary boundary) {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(population),
                "Population is missing: " + population);
        Counters counters = new Counters(boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            synchronized (counters) {
                counters.accept(person);
            }
        });
        reader.readFile(population.toString());
        return counters.result();
    }

    static Result analyzePeople(Collection<Person> people, MunichMunicipalBoundary boundary) {
        Counters counters = new Counters(boundary);
        people.stream().sorted(java.util.Comparator.comparing(person -> person.getId().toString()))
                .forEach(counters::accept);
        return counters.result();
    }

    private static void write(Result result, FinalState finalState, Path output) throws IOException {
        Files.createDirectories(output);
        writeAtomically(output.resolve("open_tour_summary.csv"), result.summaryCsv());
        writeAtomically(output.resolve("open_tour_reason_counts.csv"), result.reasonsCsv());
        writeAtomically(output.resolve("open_tour_mode_counts.csv"), result.modesCsv());
        writeAtomically(output.resolve("open_tour_diagnostic_report.md"),
                result.report(finalState));
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

    enum Eligibility {
        CURRENT_CLOSED_SUBTOUR,
        CURRENT_OPEN_PLAN,
        ALTERNATIVE_ADDITION
    }

    record ModeCount(long persons, long trips, long bothInsideTrips) { }

    record Result(long persons, long selectedPlans, long plansWithClosedSubtour,
                  long plansWithoutClosedSubtour, long currentEligibleTrips,
                  long currentOpenTrips, long currentEligibleBothInsideTrips,
                  long currentOpenBothInsideTrips, long alternativeAdditionalPersons,
                  long alternativeAdditionalTrips, long alternativeAdditionalBothInsideTrips,
                  Map<String, Long> primaryReasons, Map<String, Long> diagnosticFlags,
                  Map<Eligibility, Map<String, ModeCount>> modeCounts) {

        double immutableBothInsideShare() {
            long total = currentEligibleBothInsideTrips + currentOpenBothInsideTrips;
            return total == 0 ? Double.NaN : 100.0 * currentOpenBothInsideTrips / total;
        }

        String summaryCsv() {
            StringBuilder csv = new StringBuilder("metric,count,share_percent,notes\n");
            count(csv, "persons", persons, Double.NaN, "unchanged five-percent population");
            count(csv, "selected_plans", selectedPlans, share(selectedPlans, persons),
                    "selected plans read once");
            count(csv, "persons_with_closed_subtour", plansWithClosedSubtour,
                    share(plansWithClosedSubtour, persons), "current behaviour can select a subtour");
            count(csv, "persons_without_closed_subtour", plansWithoutClosedSubtour,
                    share(plansWithoutClosedSubtour, persons), "current behaviour cannot select a subtour");
            count(csv, "both_inside_trips_currently_mutable", currentEligibleBothInsideTrips,
                    share(currentEligibleBothInsideTrips,
                            currentEligibleBothInsideTrips + currentOpenBothInsideTrips),
                    "main trips in plans with at least one closed subtour");
            count(csv, "both_inside_trips_currently_not_mutable", currentOpenBothInsideTrips,
                    immutableBothInsideShare(), "main trips in open plans");
            count(csv, "additional_persons_under_betweenAllAndFewerConstraints",
                    alternativeAdditionalPersons, share(alternativeAdditionalPersons, persons),
                    "structurally eligible through the unclosed root subtour");
            count(csv, "additional_trips_under_betweenAllAndFewerConstraints",
                    alternativeAdditionalTrips, share(alternativeAdditionalTrips,
                            currentEligibleTrips + currentOpenTrips),
                    "all main trips in newly eligible open plans");
            count(csv, "additional_both_inside_trips_under_betweenAllAndFewerConstraints",
                    alternativeAdditionalBothInsideTrips,
                    share(alternativeAdditionalBothInsideTrips,
                            currentEligibleBothInsideTrips + currentOpenBothInsideTrips),
                    "primary-sample trips in newly eligible open plans");
            return csv.toString();
        }

        String reasonsCsv() {
            StringBuilder csv = new StringBuilder(
                    "classification_type,classification,persons,share_all_persons_percent,notes\n");
            primaryReasons.forEach((reason, count) -> csv.append("mutually_exclusive_primary,")
                    .append(reason).append(',').append(count).append(',')
                    .append(format(share(count, persons))).append(',')
                    .append("one primary classification per person\n"));
            diagnosticFlags.forEach((reason, count) -> csv.append("overlapping_diagnostic_flag,")
                    .append(reason).append(',').append(count).append(',')
                    .append(format(share(count, persons))).append(',')
                    .append("flags may overlap and must not be summed\n"));
            return csv.toString();
        }

        String modesCsv() {
            StringBuilder csv = new StringBuilder(
                    "eligibility,input_main_mode,persons_with_mode,main_trips,both_inside_main_trips\n");
            modeCounts.forEach((eligibility, modes) -> modes.forEach((mode, counts) -> csv
                    .append(eligibility).append(',').append(mode).append(',')
                    .append(counts.persons()).append(',').append(counts.trips()).append(',')
                    .append(counts.bothInsideTrips()).append('\n')));
            return csv.toString();
        }

        String report(FinalState finalState) {
            StringBuilder report = new StringBuilder("# Open-tour and calibration preflight\n\n")
                    .append("## Evidence and safeguards\n\n")
                    .append("This diagnostic streamed the unchanged five-percent population once and reused the unchanged Munich municipal-boundary filter. No person, plan, link reference, scenario configuration or simulation output was changed. The raw population contains coordinates but no runtime-assigned activity link IDs; exact repeated coordinates were therefore tested with the previously documented 0.000001 m numerical tolerance. MATSim 2025.0 uses assigned facility/link locations at runtime when `coordDistance=0.0`.\n\n")
                    .append("## Current open-tour limitation\n\n")
                    .append("Persons with at least one closed subtour: ").append(plansWithClosedSubtour)
                    .append("; without one: ").append(plansWithoutClosedSubtour).append(". The primary `BOTH_INSIDE` sample contains ")
                    .append(currentEligibleBothInsideTrips).append(" trips in currently mutable plans and ")
                    .append(currentOpenBothInsideTrips).append(" trips in open plans (")
                    .append(format(immutableBothInsideShare())).append("% currently not mutable). Cause labels in the CSV distinguish same/different endpoint locations, activity types, possible day-edge chains and location-data problems. `POSSIBLE_DAY_EDGE_OR_INCOMPLETE_CHAIN` is an interpretation flag, not an observed diary fact.\n\n")
                    .append("## MATSim 2025.0 alternative\n\n")
                    .append("The installed source defines `betweenAllAndFewerConstraints` as the current specified-mode behaviour plus eligibility for open subtours. It adds the unclosed root subtour when the complete trip chain is otherwise absent and relaxes chain-mode mass conservation for that root. Car and bike remain chain-based: they must be available at the first activity, but the alternative no longer requires the daily plan to return them to that location. In this canonical, initially monomodal population, ")
                    .append(alternativeAdditionalPersons).append(" persons and ")
                    .append(alternativeAdditionalBothInsideTrips).append(" primary trips become structurally selectable. The open final location creates an inter-day vehicle-position interpretation risk, and mixed nested subtours may trigger MATSim's explicit consistency exception in later iterations. Recommendation: test `betweenAllAndFewerConstraints` only in a short protected calibration run before any production decision.\n\n")
                    .append("## First-run final state and targets\n\n")
                    .append(finalState.markdownComparison())
                    .append("\n## Missing iteration history\n\n")
                    .append("The local `mode_choice_iteration_metrics.csv` and `mode_choice_final_summary.csv` are byte-identical and contain only iteration 20. The former postprocessor called the common writer with a one-result list, and that writer replaced the history before writing the final summary. This directly proves postprocessor overwrite. No standard score, mode-choice, plans or events history was copied into the immutable local input folder, so iterations 0–19 cannot be reconstructed and convergence cannot be claimed.\n");
            return report.toString();
        }

        String consoleSummary(FinalState finalState) {
            return String.format(Locale.ROOT,
                    "OPEN-TOUR DIAGNOSTIC PASS%npersons=%d closed=%d open=%d%n"
                            + "bothInsideMutable=%d bothInsideOpen=%d openShare=%.6f%%%n"
                            + "alternativeAdditionalPersons=%d alternativeAdditionalBothInsideTrips=%d%n%s",
                    persons, plansWithClosedSubtour, plansWithoutClosedSubtour,
                    currentEligibleBothInsideTrips, currentOpenBothInsideTrips,
                    immutableBothInsideShare(), alternativeAdditionalPersons,
                    alternativeAdditionalBothInsideTrips, finalState.consoleComparison());
        }

        private static void count(StringBuilder csv, String metric, long count,
                                  double share, String notes) {
            csv.append(metric).append(',').append(count).append(',').append(format(share))
                    .append(',').append(notes).append('\n');
        }
    }

    record FinalState(int iteration, Map<String, Double> tripShares,
                      Map<String, Double> pkmShares,
                      Map<String, Double> tripTargets,
                      Map<String, Double> pkmTargets) {
        static FinalState read(Path summary) throws IOException {
            ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(summary),
                    "Final summary is missing: " + summary);
            List<String> lines = Files.readAllLines(summary);
            ValidateModeChoiceCalibrationConfig.require(!lines.isEmpty(),
                    "Final summary is empty");
            List<String> header = ModeChoiceCalibrationTargets.parseLine(lines.getFirst());
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.size(); i++) columns.put(header.get(i), i);
            TreeMap<String, Double> shares = new TreeMap<>();
            TreeMap<String, Double> pkm = new TreeMap<>();
            Set<Integer> iterations = new HashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                List<String> row = ModeChoiceCalibrationTargets.parseLine(lines.get(i));
                if (!"BOTH_INSIDE".equals(row.get(columns.get("spatial_scope")))
                        || !"ALL_PLANS".equals(row.get(columns.get("plan_eligibility")))) continue;
                int iteration = Integer.parseInt(row.get(columns.get("iteration")));
                iterations.add(iteration);
                String metric = row.get(columns.get("metric"));
                String mode = row.get(columns.get("mode"));
                double value = Double.parseDouble(row.get(columns.get("value")));
                if ("trip_modal_share".equals(metric) && MODES.contains(mode)) {
                    shares.put(mode, value);
                }
                if ("main_mode_pkm_unscaled_5pct".equals(metric) && MODES.contains(mode)) {
                    pkm.put(mode, value);
                }
            }
            ValidateModeChoiceCalibrationConfig.require(iterations.equals(Set.of(20)),
                    "Expected only confirmed final iteration 20; found " + iterations);
            double pkmTotal = pkm.values().stream().mapToDouble(Double::doubleValue).sum();
            TreeMap<String, Double> pkmShares = new TreeMap<>();
            pkm.forEach((mode, value) -> pkmShares.put(mode, 100.0 * value / pkmTotal));
            TreeMap<String, Double> tripTargets = new TreeMap<>();
            TreeMap<String, Double> pkmTargets = new TreeMap<>();
            for (var target : ModeChoiceCalibrationTargets.read(
                    ModeChoiceCalibrationTargets.DEFAULT_FILE)) {
                if (target.numericValue() == null) continue;
                if ("trip_modal_share".equals(target.metric())) {
                    tripTargets.put(target.mode(), target.numericValue());
                }
                if ("annual_pkm_share".equals(target.metric())) {
                    pkmTargets.put(target.mode(), target.numericValue());
                }
            }
            ValidateModeChoiceCalibrationConfig.require(shares.keySet().equals(MODES)
                            && tripTargets.keySet().equals(MODES),
                    "Final or target four-mode shares are incomplete");
            return new FinalState(20, Map.copyOf(shares), Map.copyOf(pkmShares),
                    Map.copyOf(tripTargets), Map.copyOf(pkmTargets));
        }

        String markdownComparison() {
            StringBuilder out = new StringBuilder("Iteration 20 is the only preserved state.\n\n")
                    .append("| Mode | Simulated trip share | Primary target | Difference | Simulated Pkm share | Annual Pkm reference share |\n")
                    .append("|---|---:|---:|---:|---:|---:|\n");
            for (String mode : List.of("car", "pt", "bike", "walk")) {
                out.append("| ").append(mode).append(" | ")
                        .append(format(tripShares.get(mode))).append("% | ")
                        .append(format(tripTargets.get(mode))).append("% | ")
                        .append(format(tripShares.get(mode) - tripTargets.get(mode))).append(" pp | ")
                        .append(format(pkmShares.get(mode))).append("% | ")
                        .append(format(pkmTargets.get(mode))).append("% |\n");
            }
            out.append("\nTrip shares are the primary calibration targets. Annual Pkm shares are secondary plausibility references only; their source scope remains unconfirmed, and absolute annual Pkm are not compared with one simulated service day.\n");
            return out.toString();
        }

        String consoleComparison() {
            StringBuilder out = new StringBuilder("iteration20TripShares=");
            for (String mode : List.of("car", "pt", "bike", "walk")) {
                out.append(mode).append(':').append(format(tripShares.get(mode))).append('%')
                        .append(" target:").append(format(tripTargets.get(mode))).append("%;");
            }
            return out.append('\n').toString();
        }
    }

    private static final class Counters {
        private final MunichTripBoundaryFilter boundaryFilter;
        long persons;
        long selectedPlans;
        long closedPlans;
        long openPlans;
        long closedTrips;
        long openTrips;
        long closedInside;
        long openInside;
        long alternativePersons;
        long alternativeTrips;
        long alternativeInside;
        final TreeMap<String, Long> primaryReasons = new TreeMap<>();
        final TreeMap<String, Long> flags = new TreeMap<>();
        final EnumMap<Eligibility, TreeMap<String, MutableModeCount>> modes =
                new EnumMap<>(Eligibility.class);

        Counters(MunichMunicipalBoundary boundary) {
            boundaryFilter = new MunichTripBoundaryFilter(boundary);
            for (Eligibility eligibility : Eligibility.values()) {
                modes.put(eligibility, new TreeMap<>());
            }
            for (String reason : List.of("HAS_CLOSED_SUBTOUR",
                    "OPEN_DESPITE_SAME_FIRST_LAST_LOCATION",
                    "OPEN_SAME_ACTIVITY_TYPE_DIFFERENT_LOCATION",
                    "OPEN_DIFFERENT_ACTIVITY_TYPES",
                    "MISSING_OR_PROBLEMATIC_LOCATION_INFORMATION")) {
                primaryReasons.put(reason, 0L);
            }
            for (String flag : List.of("FIRST_LAST_SAME_LOCATION_OR_LINK",
                    "FIRST_LAST_DIFFERENT_LOCATION_OR_LINK",
                    "SAME_ACTIVITY_TYPE_DIFFERENT_LOCATION",
                    "DIFFERENT_ACTIVITY_TYPES", "POSSIBLE_DAY_EDGE_OR_INCOMPLETE_CHAIN",
                    "MISSING_OR_PROBLEMATIC_LOCATION_INFORMATION")) {
                flags.put(flag, 0L);
            }
        }

        void accept(Person person) {
            persons++;
            Plan plan = person.getSelectedPlan();
            if (plan == null) {
                openPlans++;
                reason("MISSING_SELECTED_PLAN");
                flag("MISSING_OR_PROBLEMATIC_LOCATION_INFORMATION");
                return;
            }
            selectedPlans++;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(
                    plan, StageActivityTypeIdentifier::isStageActivity);
            List<Activity> activities = mainActivities(plan);
            boolean closed = hasClosedSubtour(plan);
            boolean locationsValid = activities.size() >= 2
                    && validLocation(activities.getFirst()) && validLocation(activities.getLast());
            boolean allKnown = trips.stream().map(Counters::mode).allMatch(MODES::contains);
            boolean alternative = !closed && !trips.isEmpty() && locationsValid && allKnown;
            Eligibility current = closed ? Eligibility.CURRENT_CLOSED_SUBTOUR
                    : Eligibility.CURRENT_OPEN_PLAN;
            if (closed) closedPlans++; else openPlans++;
            if (alternative) alternativePersons++;

            Set<String> personModes = new HashSet<>();
            Set<String> alternativePersonModes = new HashSet<>();
            for (TripStructureUtils.Trip trip : trips) {
                String mode = mode(trip);
                boolean inside = boundaryFilter.classify(trip.getOriginActivity(),
                        trip.getDestinationActivity())
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE;
                addMode(current, mode, person.getId(), inside, personModes);
                if (closed) {
                    closedTrips++;
                    if (inside) closedInside++;
                } else {
                    openTrips++;
                    if (inside) openInside++;
                }
                if (alternative) {
                    alternativeTrips++;
                    if (inside) {
                        alternativeInside++;
                    }
                    addMode(Eligibility.ALTERNATIVE_ADDITION, mode, person.getId(), inside,
                            alternativePersonModes);
                }
            }
            classifyReason(activities, closed, locationsValid);
        }

        private void classifyReason(List<Activity> activities, boolean closed,
                                    boolean locationsValid) {
            if (activities.size() < 2 || !locationsValid) {
                reason("MISSING_OR_PROBLEMATIC_LOCATION_INFORMATION");
                flag("MISSING_OR_PROBLEMATIC_LOCATION_INFORMATION");
                return;
            }
            Activity first = activities.getFirst();
            Activity last = activities.getLast();
            boolean sameLocation = sameLocation(first, last);
            boolean sameType = java.util.Objects.equals(first.getType(), last.getType());
            if (sameLocation) flag("FIRST_LAST_SAME_LOCATION_OR_LINK");
            else flag("FIRST_LAST_DIFFERENT_LOCATION_OR_LINK");
            if (!sameLocation && sameType) flag("SAME_ACTIVITY_TYPE_DIFFERENT_LOCATION");
            if (!sameType) flag("DIFFERENT_ACTIVITY_TYPES");
            if (!closed && !sameLocation) flag("POSSIBLE_DAY_EDGE_OR_INCOMPLETE_CHAIN");
            if (closed) reason("HAS_CLOSED_SUBTOUR");
            else if (sameLocation) reason("OPEN_DESPITE_SAME_FIRST_LAST_LOCATION");
            else if (sameType) reason("OPEN_SAME_ACTIVITY_TYPE_DIFFERENT_LOCATION");
            else reason("OPEN_DIFFERENT_ACTIVITY_TYPES");
        }

        private void addMode(Eligibility eligibility, String mode, Id<Person> personId,
                             boolean inside, Set<String> seenModes) {
            MutableModeCount count = modes.get(eligibility)
                    .computeIfAbsent(mode, ignored -> new MutableModeCount());
            count.trips++;
            if (inside) count.inside++;
            if (seenModes.add(mode)) count.persons++;
        }

        private void reason(String value) { primaryReasons.merge(value, 1L, Long::sum); }
        private void flag(String value) { flags.merge(value, 1L, Long::sum); }

        Result result() {
            ValidateModeChoiceCalibrationConfig.require(closedPlans + openPlans == persons,
                    "Open-tour person classification is incomplete");
            ValidateModeChoiceCalibrationConfig.require(alternativeInside <= openInside,
                    "Alternative added more primary trips than open plans contain");
            EnumMap<Eligibility, Map<String, ModeCount>> immutable =
                    new EnumMap<>(Eligibility.class);
            modes.forEach((eligibility, values) -> {
                TreeMap<String, ModeCount> mapped = new TreeMap<>();
                values.forEach((mode, count) -> mapped.put(mode,
                        new ModeCount(count.persons, count.trips, count.inside)));
                immutable.put(eligibility, Collections.unmodifiableMap(mapped));
            });
            return new Result(persons, selectedPlans, closedPlans, openPlans,
                    closedTrips, openTrips, closedInside, openInside,
                    alternativePersons, alternativeTrips, alternativeInside,
                    Collections.unmodifiableMap(new TreeMap<>(primaryReasons)),
                    Collections.unmodifiableMap(new TreeMap<>(flags)),
                    Collections.unmodifiableMap(immutable));
        }

        private static boolean hasClosedSubtour(Plan plan) {
            return TripStructureUtils.getSubtours(plan.getPlanElements(),
                            StageActivityTypeIdentifier::isStageActivity,
                            RAW_COORDINATE_TOLERANCE_METRES).stream()
                    .anyMatch(TripStructureUtils.Subtour::isClosed);
        }

        private static List<Activity> mainActivities(Plan plan) {
            List<Activity> activities = new ArrayList<>();
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Activity activity
                        && !StageActivityTypeIdentifier.isStageActivity(activity.getType())) {
                    activities.add(activity);
                }
            }
            return activities;
        }

        private static String mode(TripStructureUtils.Trip trip) {
            try {
                String mode = MAIN_MODE.identifyMainMode(trip.getTripElements());
                return mode == null ? "unknown" : mode.toLowerCase(Locale.ROOT);
            } catch (RuntimeException exception) {
                return "unknown";
            }
        }

        private static boolean validLocation(Activity activity) {
            if (activity.getFacilityId() != null || activity.getLinkId() != null) return true;
            Coord coord = activity.getCoord();
            return coord != null && Double.isFinite(coord.getX()) && Double.isFinite(coord.getY());
        }

        private static boolean sameLocation(Activity first, Activity last) {
            if (first.getFacilityId() != null && last.getFacilityId() != null) {
                return first.getFacilityId().equals(last.getFacilityId());
            }
            if (first.getLinkId() != null && last.getLinkId() != null) {
                return first.getLinkId().equals(last.getLinkId());
            }
            if (!validLocation(first) || !validLocation(last)
                    || first.getCoord() == null || last.getCoord() == null) return false;
            return Math.hypot(first.getCoord().getX() - last.getCoord().getX(),
                    first.getCoord().getY() - last.getCoord().getY())
                    <= RAW_COORDINATE_TOLERANCE_METRES;
        }
    }

    private static final class MutableModeCount {
        long persons;
        long trips;
        long inside;
    }

    private static double share(long value, long total) {
        return total == 0 ? Double.NaN : 100.0 * value / total;
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }
}
