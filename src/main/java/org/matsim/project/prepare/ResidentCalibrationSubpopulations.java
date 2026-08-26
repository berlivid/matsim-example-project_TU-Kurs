package org.matsim.project.prepare;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;

/** Assigns calibration subpopulations only to the in-memory MATSim population. */
public final class ResidentCalibrationSubpopulations {
    public static final String MUNICH_RESIDENT = "munich_resident";
    public static final String REGIONAL_BACKGROUND = "regional_background";
    public static final String UNRESOLVED_BACKGROUND = "unresolved_background";
    public static final long EXPECTED_MUNICH_RESIDENTS = 68_770;
    public static final long EXPECTED_REGIONAL_BACKGROUND = 147_655;
    public static final long EXPECTED_UNRESOLVED_BACKGROUND = 107_618;
    public static final long EXPECTED_TOTAL_PERSONS = 324_043;

    private ResidentCalibrationSubpopulations() { }

    public static Counts assignAndValidate(Population population,
                                           MunichMunicipalBoundary boundary) {
        Counts result = assign(population, boundary);
        result.requireAuthoritative();
        return result;
    }

    static Counts assign(Population population, MunichMunicipalBoundary boundary) {
        MunichResidentClassifier classifier = new MunichResidentClassifier(boundary);
        TreeMap<String, Long> counts = new TreeMap<>();
        population.getPersons().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> assign(entry.getValue(), classifier, counts));
        return new Counts(Collections.unmodifiableMap(counts));
    }

    static String labelFor(MunichResidentClassifier.Classification classification) {
        return switch (classification) {
            case MUNICH_RESIDENT -> MUNICH_RESIDENT;
            case NON_MUNICH_RESIDENT -> REGIONAL_BACKGROUND;
            case NO_HOME_ACTIVITY, MISSING_HOME_COORDINATE,
                    CONFLICTING_HOME_LOCATIONS, INVALID_SELECTED_PLAN ->
                    UNRESOLVED_BACKGROUND;
        };
    }

    private static void assign(Person person, MunichResidentClassifier classifier,
                               Map<String, Long> counts) {
        MunichResidentClassifier.Result classification = classifier.classify(person);
        String label = labelFor(classification.classification());
        String existing = PopulationUtils.getSubpopulation(person);
        ValidateModeChoiceCalibrationConfig.require(existing == null || existing.equals(label),
                "Existing subpopulation conflicts with resident classification for person "
                        + person.getId() + ": " + existing + " != " + label);
        if (existing == null) PopulationUtils.putSubpopulation(person, label);
        counts.merge(label, 1L, Long::sum);
    }

    public record Counts(Map<String, Long> bySubpopulation) {
        public Counts {
            bySubpopulation = Map.copyOf(bySubpopulation);
        }

        public long count(String subpopulation) {
            return bySubpopulation.getOrDefault(subpopulation, 0L);
        }

        public long total() {
            return bySubpopulation.values().stream().mapToLong(Long::longValue).sum();
        }

        public void requireAuthoritative() {
            ValidateModeChoiceCalibrationConfig.require(
                    bySubpopulation.keySet().equals(java.util.Set.of(
                            MUNICH_RESIDENT, REGIONAL_BACKGROUND, UNRESOLVED_BACKGROUND)),
                    "Unexpected runtime subpopulation labels: " + bySubpopulation);
            ValidateModeChoiceCalibrationConfig.require(
                    count(MUNICH_RESIDENT) == EXPECTED_MUNICH_RESIDENTS,
                    "Munich-resident count changed: " + count(MUNICH_RESIDENT));
            ValidateModeChoiceCalibrationConfig.require(
                    count(REGIONAL_BACKGROUND) == EXPECTED_REGIONAL_BACKGROUND,
                    "Regional-background count changed: " + count(REGIONAL_BACKGROUND));
            ValidateModeChoiceCalibrationConfig.require(
                    count(UNRESOLVED_BACKGROUND) == EXPECTED_UNRESOLVED_BACKGROUND,
                    "Unresolved-background count changed: " + count(UNRESOLVED_BACKGROUND));
            ValidateModeChoiceCalibrationConfig.require(total() == EXPECTED_TOTAL_PERSONS,
                    "Total population count changed: " + total());
        }
    }
}
