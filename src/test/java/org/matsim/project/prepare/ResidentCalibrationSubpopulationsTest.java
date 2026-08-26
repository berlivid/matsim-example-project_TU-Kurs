package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

class ResidentCalibrationSubpopulationsTest {
    private static MunichMunicipalBoundary boundary;
    private static PopulationFactory factory;
    private static Coord inside;
    private static Coord outside;

    @BeforeAll
    static void loadBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        factory = PopulationUtils.getFactory();
        var interior = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(interior.x, interior.y);
        var envelope = boundary.envelope();
        outside = new Coord(envelope.getMinX() - 10_000,
                envelope.getMinY() - 10_000);
    }

    @Test
    void assignsThreeRuntimeLabelsWithoutChangingPlansModesOrIds() {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person resident = person("resident", activity("home", inside),
                activity("work", outside));
        Person regional = person("regional", activity("home", outside),
                activity("work", inside));
        Person unresolved = person("unresolved", activity("work", inside),
                activity("other", outside));
        population.addPerson(resident);
        population.addPerson(regional);
        population.addPerson(unresolved);
        List<String> before = signature(population);

        ResidentCalibrationSubpopulations.Counts counts =
                ResidentCalibrationSubpopulations.assign(population, boundary);

        assertEquals(1, counts.count(ResidentCalibrationSubpopulations.MUNICH_RESIDENT));
        assertEquals(1, counts.count(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND));
        assertEquals(1, counts.count(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND));
        assertEquals(ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                PopulationUtils.getSubpopulation(resident));
        assertEquals(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
                PopulationUtils.getSubpopulation(regional));
        assertEquals(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND,
                PopulationUtils.getSubpopulation(unresolved));
        assertEquals(before, signature(population));
    }

    @Test
    void refusesAConflictingExistingRuntimeLabel() {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person resident = person("resident", activity("home", inside),
                activity("work", outside));
        PopulationUtils.putSubpopulation(resident,
                ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND);
        population.addPerson(resident);
        assertThrows(IllegalStateException.class,
                () -> ResidentCalibrationSubpopulations.assign(population, boundary));
    }

    private static Person person(String id, Activity... activities) {
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        for (int index = 0; index < activities.length; index++) {
            if (index > 0) plan.addLeg(factory.createLeg("car"));
            plan.addActivity(activities[index]);
        }
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Activity activity(String type, Coord coord) {
        return factory.createActivityFromCoord(type, coord);
    }

    private static List<String> signature(Population population) {
        return population.getPersons().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .flatMap(entry -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(entry.getKey().toString()),
                        entry.getValue().getSelectedPlan().getPlanElements().stream()
                                .map(element -> element instanceof Activity activity
                                        ? "A|" + activity.getType() + "|" + activity.getCoord()
                                        : "L|" + ((org.matsim.api.core.v01.population.Leg) element)
                                        .getMode())))
                .toList();
    }
}
