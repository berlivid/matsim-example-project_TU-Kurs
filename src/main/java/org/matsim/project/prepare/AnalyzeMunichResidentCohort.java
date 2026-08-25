package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Streaming, read-only preflight of the Munich-resident analysis cohort. */
public final class AnalyzeMunichResidentCohort {
    static final Path OUTPUT = Path.of("generated/munich_resident_cohort_preflight");
    static final int UNRESOLVED_DIAGNOSTIC_LIMIT = 100;
    // Raw plans use coordinates but no activity link IDs. This tolerance only
    // detects exact repeated-coordinate subtour closure; it never affects residence.
    static final double CLOSED_SUBTOUR_COORDINATE_TOLERANCE_METRES = 1e-6;

    private AnalyzeMunichResidentCohort() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only resident-cohort preflight accepts no arguments");
        Config config = ValidateModeChoiceCalibrationConfig.loadAndValidate();
        Path population = resolvePopulation(config);
        Result result = analyze(population, MunichMunicipalBoundary.loadDefault());
        write(result, OUTPUT);
        System.out.print(result.consoleSummary());
    }

    static Path resolvePopulation(Config config) {
        String input = config.plans().getInputFile();
        ValidateModeChoiceCalibrationConfig.require(input != null && !input.isBlank(),
                "The authoritative calibration config has no population input");
        return ValidateModeChoiceCalibrationConfig.CONFIG.getParent()
                .resolve(input).normalize();
    }

    static Result analyze(Path population, MunichMunicipalBoundary boundary) {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(population),
                "Population is missing: " + population.toAbsolutePath());
        Counters counters = new Counters(boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            synchronized (counters) {
                counters.accept(person);
            }
        });
        reader.readFile(population.toString());
        return counters.result(population.normalize());
    }

    static Result analyzePersons(Iterable<Person> persons, MunichMunicipalBoundary boundary) {
        Counters counters = new Counters(boundary);
        for (Person person : persons) counters.accept(person);
        return counters.result(Path.of("in-memory-test-population"));
    }

    static void write(Result result, Path output) throws IOException {
        Files.createDirectories(output);
        writeAtomically(output.resolve("resident_classification_summary.csv"),
                result.classificationCsv());
        writeAtomically(output.resolve("resident_trip_scope_summary.csv"),
                result.tripScopeCsv());
        writeAtomically(output.resolve("resident_input_mode_summary.csv"),
                result.inputModeCsv());
        writeAtomically(output.resolve("unresolved_residents.csv"),
                result.unresolvedCsv());
        writeAtomically(output.resolve("preflight_report.md"), result.report());
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

    record Result(Path population, Path config, Path boundaryFile, String boundarySha256,
                  long persons, long selectedPlans, long totalMainTrips,
                  Map<MunichResidentClassifier.Classification, Long> classifications,
                  long residentMainTrips,
                  Map<MunichTripBoundaryFilter.SpatialCategory, Long> residentTripsByScope,
                  Map<String, Long> residentTripsByInputMode,
                  long residentsWithClosedSubtour, long residentsWithoutClosedSubtour,
                  List<UnresolvedPerson> unresolvedSample) {

        long classification(MunichResidentClassifier.Classification classification) {
            return classifications.getOrDefault(classification, 0L);
        }

        long residents() {
            return classification(MunichResidentClassifier.Classification.MUNICH_RESIDENT);
        }

        long nonResidents() {
            return classification(MunichResidentClassifier.Classification.NON_MUNICH_RESIDENT);
        }

        long unresolvedPersons() {
            return persons - residents() - nonResidents();
        }

        long spatialCategorySum() {
            return residentTripsByScope.values().stream().mapToLong(Long::longValue).sum();
        }

        String classificationCsv() {
            StringBuilder out = new StringBuilder("classification,persons,share_percent\n");
            for (var classification : MunichResidentClassifier.Classification.values()) {
                long count = classification(classification);
                out.append(classification).append(',').append(count).append(',')
                        .append(formatShare(share(count, persons))).append('\n');
            }
            out.append("TOTAL,").append(persons).append(',')
                    .append(formatShare(share(persons, persons))).append('\n');
            return out.toString();
        }

        String tripScopeCsv() {
            StringBuilder out = new StringBuilder("spatial_category,trips,share_percent\n");
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                long count = residentTripsByScope.getOrDefault(category, 0L);
                out.append(category).append(',').append(count).append(',')
                        .append(formatShare(share(count, residentMainTrips))).append('\n');
            }
            out.append("ALL_RESIDENT_TRIPS,").append(residentMainTrips).append(',')
                    .append(formatShare(share(residentMainTrips, residentMainTrips))).append('\n');
            return out.toString();
        }

        String inputModeCsv() {
            StringBuilder out = new StringBuilder("input_main_mode,trips,share_percent\n");
            residentTripsByInputMode.forEach((mode, count) -> out.append(csv(mode)).append(',')
                    .append(count).append(',')
                    .append(formatShare(share(count, residentMainTrips))).append('\n'));
            out.append("TOTAL,").append(residentMainTrips).append(',')
                    .append(formatShare(share(residentMainTrips, residentMainTrips))).append('\n');
            return out.toString();
        }

        String unresolvedCsv() {
            StringBuilder out = new StringBuilder(
                    "person_id,classification,reason,main_activity_types,home_coordinates_epsg31468\n");
            for (UnresolvedPerson person : unresolvedSample) {
                out.append(csv(person.personId())).append(',').append(person.classification())
                        .append(',').append(csv(person.reason())).append(',')
                        .append(csv(person.activityTypes())).append(',')
                        .append(csv(person.homeCoordinates())).append('\n');
            }
            return out.toString();
        }

        String report() {
            StringBuilder out = new StringBuilder("# Munich-resident cohort preflight\n\n")
                    .append("## Purpose and method\n\n")
                    .append("This read-only preflight identifies the cohort for the future 2019 calibration and primary thesis analysis. A person is a Munich resident when the selected plan contains at least one exact `home` main activity and all valid `home` coordinates give the same inside result under the City of Munich municipal boundary. JTS `covers` includes points on the boundary. MATSim stage activities are excluded with its official stage-activity predicate. The inspected selected plans contained the main activity types `home`, `work`, `education`, `shopping` and `other`; only exact, case-sensitive `home` is accepted. No substring or inferred trip-end rule is used.\n\n")
                    .append("The residence rule uses no distance tolerance. Multiple valid home coordinates may differ spatially when they all imply the same inside/outside result. Contradictory inside/outside results are unresolved. The separate 1e-6 metre tolerance reported for closed-subtour readiness only recognizes repeated raw-plan coordinates and does not affect residence.\n\n")
                    .append("Residence-based analysis includes every main trip made by a classified Munich resident, including boundary-crossing and entirely external trips. The former `BOTH_INSIDE` scope remains a secondary territorial indicator. Regional non-residents remain in the simulation as background traffic because they contribute to congestion, public-transport demand and network conditions.\n\n")
                    .append("## Sources\n\n")
                    .append("- Authoritative calibration config: `").append(config).append("`\n")
                    .append("- Population resolved from that config: `").append(population).append("`\n")
                    .append("- Municipal boundary: `").append(boundaryFile).append("`\n")
                    .append("- Boundary SHA-256: `").append(boundarySha256).append("`\n")
                    .append("- Coordinate reference system: EPSG:31468\n\n")
                    .append("## Classification results\n\n")
                    .append("Persons: ").append(persons).append("; selected plans: ")
                    .append(selectedPlans).append("; total main trips in the complete regional population: ")
                    .append(totalMainTrips).append(".\n\n")
                    .append("| Classification | Persons | Share |\n|---|---:|---:|\n");
            for (var classification : MunichResidentClassifier.Classification.values()) {
                long count = classification(classification);
                out.append("| ").append(classification).append(" | ").append(count).append(" | ")
                        .append(formatShare(share(count, persons))).append("% |\n");
            }
            out.append("\nMunich residents: ").append(residents())
                    .append("; non-residents: ").append(nonResidents())
                    .append("; unresolved persons: ").append(unresolvedPersons()).append(".\n\n")
                    .append("## Trips made by Munich residents\n\n")
                    .append("All main trips made by Munich residents: ").append(residentMainTrips)
                    .append(".\n\n| Spatial category | Trips | Share |\n|---|---:|---:|\n");
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                long count = residentTripsByScope.getOrDefault(category, 0L);
                out.append("| ").append(category).append(" | ").append(count).append(" | ")
                        .append(formatShare(share(count, residentMainTrips))).append("% |\n");
            }
            out.append("\nSpatial-category completeness check: ")
                    .append(spatialCategorySum()).append(" = ").append(residentMainTrips)
                    .append(" all resident main trips (`")
                    .append(spatialCategorySum() == residentMainTrips ? "PASS" : "FAIL")
                    .append("`).\n\n")
                    .append("| Current input main mode | Trips | Share |\n|---|---:|---:|\n");
            residentTripsByInputMode.forEach((mode, count) -> out.append("| ").append(mode)
                    .append(" | ").append(count).append(" | ")
                    .append(formatShare(share(count, residentMainTrips))).append("% |\n"));
            out.append("\n## Closed-subtour readiness\n\n")
                    .append("Munich residents with at least one closed subtour: ")
                    .append(residentsWithClosedSubtour).append("; without a closed subtour: ")
                    .append(residentsWithoutClosedSubtour).append(". This is a technical readiness diagnostic, not a cohort restriction.\n\n")
                    .append("## Unresolved cases and non-intervention\n\n")
                    .append("Missing, non-finite or contradictory home information is never classified silently. `unresolved_residents.csv` contains at most ")
                    .append(UNRESOLVED_DIAGNOSTIC_LIMIT)
                    .append(" lexicographically smallest person IDs with their reason, relevant main activity types and home coordinates. The classification totals above cover every person exactly once.\n\n")
                    .append("This step did not filter or write a population and did not change a config, network, schedule, vehicle file, scenario input, calibration constant, behavioral parameter or mode-choice setting.\n");
            return out.toString();
        }

        String consoleSummary() {
            return String.format(Locale.ROOT,
                    "MUNICH RESIDENT COHORT PREFLIGHT PASS%n"
                            + "persons=%d selectedPlans=%d totalMainTrips=%d%n"
                            + "residents=%d nonResidents=%d unresolved=%d residentMainTrips=%d%n"
                            + "residentTripScopes=%s%nresidentInputModes=%s%n"
                            + "closedSubtourResidents=%d withoutClosedSubtour=%d%n"
                            + "spatialCategorySum=%d equalsResidentTrips=%s%n",
                    persons, selectedPlans, totalMainTrips, residents(), nonResidents(),
                    unresolvedPersons(), residentMainTrips, residentTripsByScope,
                    residentTripsByInputMode, residentsWithClosedSubtour,
                    residentsWithoutClosedSubtour, spatialCategorySum(),
                    spatialCategorySum() == residentMainTrips);
        }
    }

    record UnresolvedPerson(String personId,
                            MunichResidentClassifier.Classification classification,
                            String reason, String activityTypes, String homeCoordinates) { }

    private static final class Counters {
        private final MunichResidentClassifier classifier;
        private final MunichTripBoundaryFilter tripFilter;
        private final MunichMunicipalBoundary boundary;
        private final EnumMap<MunichResidentClassifier.Classification, Long> classifications =
                new EnumMap<>(MunichResidentClassifier.Classification.class);
        private final EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> residentScopes =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        private final TreeMap<String, Long> residentModes = new TreeMap<>();
        private final TreeMap<String, UnresolvedPerson> unresolved = new TreeMap<>();
        private long persons;
        private long selectedPlans;
        private long totalMainTrips;
        private long residentMainTrips;
        private long residentsWithClosedSubtour;
        private long residentsWithoutClosedSubtour;

        Counters(MunichMunicipalBoundary boundary) {
            this.boundary = boundary;
            classifier = new MunichResidentClassifier(boundary);
            tripFilter = new MunichTripBoundaryFilter(boundary);
            for (var value : MunichResidentClassifier.Classification.values()) {
                classifications.put(value, 0L);
            }
            for (var value : MunichTripBoundaryFilter.SpatialCategory.values()) {
                residentScopes.put(value, 0L);
            }
        }

        void accept(Person person) {
            persons++;
            Plan plan = person == null ? null : person.getSelectedPlan();
            if (plan != null) selectedPlans++;
            MunichResidentClassifier.Result result = classifier.classify(person);
            classifications.merge(result.classification(), 1L, Long::sum);

            List<MunichTripBoundaryFilter.ClassifiedTrip> trips = plan == null
                    || result.classification()
                    == MunichResidentClassifier.Classification.INVALID_SELECTED_PLAN
                    ? List.of() : tripFilter.classify(plan);
            totalMainTrips += trips.size();

            if (result.classification().isUnresolved()) {
                addUnresolved(person, result);
                return;
            }
            if (result.classification()
                    != MunichResidentClassifier.Classification.MUNICH_RESIDENT) return;

            residentMainTrips += trips.size();
            for (var trip : trips) {
                residentScopes.merge(trip.category(), 1L, Long::sum);
                residentModes.merge(trip.inputMainMode(), 1L, Long::sum);
            }
            if (hasClosedSubtour(plan)) residentsWithClosedSubtour++;
            else residentsWithoutClosedSubtour++;
        }

        private boolean hasClosedSubtour(Plan plan) {
            return TripStructureUtils.getSubtours(plan.getPlanElements(),
                            StageActivityTypeIdentifier::isStageActivity,
                            CLOSED_SUBTOUR_COORDINATE_TOLERANCE_METRES).stream()
                    .anyMatch(TripStructureUtils.Subtour::isClosed);
        }

        private void addUnresolved(Person person, MunichResidentClassifier.Result result) {
            String id = person == null ? "<null-person>" : person.getId().toString();
            unresolved.put(id, new UnresolvedPerson(id, result.classification(),
                    result.reason(), result.activityTypesDiagnostic(),
                    result.homeCoordinatesDiagnostic()));
            while (unresolved.size() > UNRESOLVED_DIAGNOSTIC_LIMIT) {
                unresolved.pollLastEntry();
            }
        }

        Result result(Path population) {
            long classificationSum = classifications.values().stream()
                    .mapToLong(Long::longValue).sum();
            long scopeSum = residentScopes.values().stream().mapToLong(Long::longValue).sum();
            long modeSum = residentModes.values().stream().mapToLong(Long::longValue).sum();
            long residents = classifications.get(
                    MunichResidentClassifier.Classification.MUNICH_RESIDENT);
            ValidateModeChoiceCalibrationConfig.require(classificationSum == persons,
                    "Each person must receive exactly one resident classification");
            ValidateModeChoiceCalibrationConfig.require(scopeSum == residentMainTrips,
                    "Resident spatial trip categories do not sum to all resident trips");
            ValidateModeChoiceCalibrationConfig.require(modeSum == residentMainTrips,
                    "Resident input modes do not sum to all resident trips");
            ValidateModeChoiceCalibrationConfig.require(
                    residentsWithClosedSubtour + residentsWithoutClosedSubtour == residents,
                    "Closed-subtour readiness does not cover every Munich resident");
            return new Result(population,
                    ValidateModeChoiceCalibrationConfig.CONFIG.normalize(), boundary.source(),
                    boundary.sha256(), persons, selectedPlans, totalMainTrips,
                    Collections.unmodifiableMap(new EnumMap<>(classifications)),
                    residentMainTrips,
                    Collections.unmodifiableMap(new EnumMap<>(residentScopes)),
                    Collections.unmodifiableMap(new TreeMap<>(residentModes)),
                    residentsWithClosedSubtour, residentsWithoutClosedSubtour,
                    List.copyOf(unresolved.values()));
        }
    }

    private static double share(long count, long total) {
        return total == 0 ? Double.NaN : 100.0 * count / total;
    }

    private static String formatShare(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
