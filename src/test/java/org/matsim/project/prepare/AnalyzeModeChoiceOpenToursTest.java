package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.modules.SubtourModeChoice;

class AnalyzeModeChoiceOpenToursTest {
    private static MunichMunicipalBoundary boundary;
    private static Coord inside;

    @BeforeAll
    static void loadBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        var point = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(point.x, point.y);
    }

    @Test
    void installedMatsimProvidesTheReviewedAlternativeWithoutChangingConfig() {
        assertEquals(SubtourModeChoice.Behavior.betweenAllAndFewerConstraints,
                SubtourModeChoice.Behavior.valueOf("betweenAllAndFewerConstraints"));
        var config = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.CONFIG.toString());
        assertEquals(SubtourModeChoice.Behavior.fromSpecifiedModesToSpecifiedModes,
                config.subtourModeChoice().getBehavior());
    }

    @Test
    void closedAndOpenPlansAndReasonsAreAggregatedWithoutPersonData() {
        Person closed = person("closed", closedPlan());
        Person sameTypeOpen = person("same-type", openPlan("home", "home"));
        Person differentTypeOpen = person("different-type", openPlan("home", "work"));

        var result = AnalyzeModeChoiceOpenTours.analyzePeople(
                List.of(differentTypeOpen, closed, sameTypeOpen), boundary);
        assertEquals(3, result.persons());
        assertEquals(1, result.plansWithClosedSubtour());
        assertEquals(2, result.plansWithoutClosedSubtour());
        assertEquals(2, result.currentEligibleBothInsideTrips());
        assertEquals(2, result.currentOpenBothInsideTrips());
        assertEquals(2, result.alternativeAdditionalPersons());
        assertEquals(2, result.alternativeAdditionalBothInsideTrips());
        assertEquals(1, result.primaryReasons()
                .get("OPEN_SAME_ACTIVITY_TYPE_DIFFERENT_LOCATION"));
        assertEquals(1, result.primaryReasons().get("OPEN_DIFFERENT_ACTIVITY_TYPES"));
        assertEquals(2, result.diagnosticFlags()
                .get("POSSIBLE_DAY_EDGE_OR_INCOMPLETE_CHAIN"));
        assertEquals(50.0, result.immutableBothInsideShare(), 1e-12);
        assertTrue(result.modesCsv().contains("ALTERNATIVE_ADDITION,car,2,2,2"));
    }

    private static Person person(String id, Plan plan) {
        Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId(id));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Plan openPlan(String firstType, String lastType) {
        var factory = PopulationUtils.getFactory();
        Plan plan = factory.createPlan();
        plan.addActivity(activity(firstType, inside));
        plan.addLeg(factory.createLeg("car"));
        plan.addActivity(activity(lastType,
                new Coord(inside.getX() + 1_000, inside.getY())));
        return plan;
    }

    private static Plan closedPlan() {
        var factory = PopulationUtils.getFactory();
        Plan plan = factory.createPlan();
        plan.addActivity(activity("home", inside));
        plan.addLeg(factory.createLeg("car"));
        plan.addActivity(activity("work",
                new Coord(inside.getX() + 1_000, inside.getY())));
        plan.addLeg(factory.createLeg("car"));
        plan.addActivity(activity("home", inside));
        return plan;
    }

    private static Activity activity(String type, Coord coord) {
        return PopulationUtils.getFactory().createActivityFromCoord(type, coord);
    }
}
