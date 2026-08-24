package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Streaming, read-only readiness audit of the unchanged public five-percent population. */
public final class AnalyzeModeChoicePopulationReadiness {
    static final Path POPULATION = Path.of(
            "scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml");
    private static final Set<String> OFFERED_MODES = Set.of("car", "pt", "walk", "bike");
    // The raw plans have coordinates but no activity link IDs. A microscopic
    // tolerance reproduces exact repeated-coordinate closure for this audit;
    // the controller assigns link references before runtime replanning.
    private static final double RAW_PLAN_COORDINATE_TOLERANCE_METRES = 1e-6;
    private static final Set<String> CAR_AVAILABILITY_KEYS = Set.of(
            "carAvail", "carAvailability");
    private static final Set<String> LICENCE_KEYS = Set.of(
            "license", "licence", "drivingLicense", "hasLicense");
    private static final Set<String> VEHICLE_KEYS = Set.of(
            "vehicleAvailability", "vehicleAvail", "vehicleId", "vehicles");
    private static final DefaultAnalysisMainModeIdentifier MAIN_MODE_IDENTIFIER =
            new DefaultAnalysisMainModeIdentifier();

    private AnalyzeModeChoicePopulationReadiness() { }

    public static void main(String[] args) {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only audit accepts no arguments");
        Result result = analyze(POPULATION);
        System.out.print(result.report());
    }

    static Result analyze(Path population) {
        ValidateModeChoiceCalibrationConfig.require(Files.isRegularFile(population),
                "Population is missing: " + population);
        Counters counters = new Counters();
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

    public record Result(long persons, long selectedPlans, long mainTrips,
                         long closedSubtours, long plansWithClosedSubtour,
                         long plansWithoutClosedSubtour, long likelyModifiablePersons,
                         long personsWithCarAvailability, long personsWithLicence,
                         long personsWithVehicleAttribute, Map<String, Long> inputModes,
                         long unknownModeTrips) {
        String report() {
            return String.format(Locale.ROOT,
                    "MODE-CHOICE POPULATION READINESS PASS%n"
                            + "population=%s%npersons=%d selectedPlans=%d mainTrips=%d%n"
                            + "inputModes=%s unknownModeTrips=%d%n"
                            + "closedSubtours=%d plansWithClosedSubtour=%d "
                            + "plansWithoutClosedSubtour=%d likelyModifiablePersons=%d%n"
                            + "personsWithCarAvail=%d personsWithLicence=%d "
                            + "personsWithVehicleAttribute=%d%n"
                            + "No attributes were created or changed.%n",
                    POPULATION, persons, selectedPlans, mainTrips, inputModes, unknownModeTrips,
                    closedSubtours, plansWithClosedSubtour, plansWithoutClosedSubtour,
                    likelyModifiablePersons, personsWithCarAvailability, personsWithLicence,
                    personsWithVehicleAttribute);
        }
    }

    private static final class Counters {
        long persons;
        long selectedPlans;
        long mainTrips;
        long closedSubtours;
        long plansWithClosedSubtour;
        long plansWithoutClosedSubtour;
        long likelyModifiablePersons;
        long personsWithCarAvailability;
        long personsWithLicence;
        long personsWithVehicleAttribute;
        long unknownModeTrips;
        final TreeMap<String, Long> inputModes = new TreeMap<>();

        void accept(Person person) {
            persons++;
            if (hasAny(person, CAR_AVAILABILITY_KEYS)) personsWithCarAvailability++;
            if (hasAny(person, LICENCE_KEYS)) personsWithLicence++;
            if (hasAny(person, VEHICLE_KEYS)) personsWithVehicleAttribute++;
            Plan plan = person.getSelectedPlan();
            if (plan == null) {
                plansWithoutClosedSubtour++;
                return;
            }
            selectedPlans++;
            var trips = TripStructureUtils.getTrips(plan,
                    StageActivityTypeIdentifier::isStageActivity);
            mainTrips += trips.size();
            boolean hasOfferedTrip = false;
            for (var trip : trips) {
                String mode = identifyMode(trip);
                inputModes.merge(mode, 1L, Long::sum);
                if (OFFERED_MODES.contains(mode)) hasOfferedTrip = true;
                else unknownModeTrips++;
            }
            long closed = TripStructureUtils.getSubtours(plan.getPlanElements(),
                            StageActivityTypeIdentifier::isStageActivity,
                            RAW_PLAN_COORDINATE_TOLERANCE_METRES).stream()
                    .filter(TripStructureUtils.Subtour::isClosed).count();
            closedSubtours += closed;
            if (closed > 0) {
                plansWithClosedSubtour++;
                if (hasOfferedTrip) likelyModifiablePersons++;
            } else {
                plansWithoutClosedSubtour++;
            }
        }

        Result result() {
            ValidateModeChoiceCalibrationConfig.require(
                    plansWithClosedSubtour + plansWithoutClosedSubtour == persons,
                    "Readiness person classification is incomplete");
            ValidateModeChoiceCalibrationConfig.require(unknownModeTrips == 0,
                    "Unknown input modes found: " + inputModes);
            return new Result(persons, selectedPlans, mainTrips, closedSubtours,
                    plansWithClosedSubtour, plansWithoutClosedSubtour,
                    likelyModifiablePersons, personsWithCarAvailability,
                    personsWithLicence, personsWithVehicleAttribute,
                    Map.copyOf(inputModes), unknownModeTrips);
        }

        private static boolean hasAny(Person person, Set<String> keys) {
            return keys.stream().anyMatch(key -> person.getAttributes().getAttribute(key) != null);
        }

        private static String identifyMode(TripStructureUtils.Trip trip) {
            try {
                String mode = MAIN_MODE_IDENTIFIER.identifyMainMode(trip.getTripElements());
                return mode == null || mode.isBlank() ? "unknown" : mode.toLowerCase(Locale.ROOT);
            } catch (RuntimeException exception) {
                return "unknown";
            }
        }
    }
}
