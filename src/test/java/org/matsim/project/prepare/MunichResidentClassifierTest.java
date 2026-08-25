package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

class MunichResidentClassifierTest {
    private static MunichResidentClassifier classifier;
    private static PopulationFactory factory;
    private static Coord inside;
    private static Coord secondInside;
    private static Coord outside;
    private static Coord onBoundary;

    @BeforeAll
    static void loadBoundary() throws Exception {
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        classifier = new MunichResidentClassifier(boundary);
        factory = PopulationUtils.getFactory();
        var interior = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(interior.x, interior.y);
        secondInside = new Coord(interior.x + 1.0, interior.y + 1.0);
        var envelope = boundary.envelope();
        outside = new Coord(envelope.getMinX() - 10_000,
                envelope.getMinY() - 10_000);
        var edge = boundary.geometry().getBoundary().getCoordinate();
        onBoundary = new Coord(edge.x, edge.y);
    }

    @Test
    void exactHomeInsideClassifiesResident() {
        assertClassification(MunichResidentClassifier.Classification.MUNICH_RESIDENT,
                person("inside", plan(activity("home", inside), activity("work", outside))));
    }

    @Test
    void exactHomeOutsideClassifiesNonResident() {
        assertClassification(MunichResidentClassifier.Classification.NON_MUNICH_RESIDENT,
                person("outside", plan(activity("home", outside), activity("work", inside))));
    }

    @Test
    void exactHomeOnBoundaryClassifiesResident() {
        assertClassification(MunichResidentClassifier.Classification.MUNICH_RESIDENT,
                person("boundary", plan(activity("home", onBoundary),
                        activity("work", outside))));
    }

    @Test
    void multipleInsideHomeActivitiesAreConsistentWithoutDistanceTolerance() {
        Plan plan = plan(activity("home", inside), activity("work", outside),
                activity("home", secondInside));
        assertClassification(MunichResidentClassifier.Classification.MUNICH_RESIDENT,
                person("consistent", plan));
    }

    @Test
    void contradictoryHomeBoundaryResultsAreConflicting() {
        Plan plan = plan(activity("home", inside), activity("work", inside),
                activity("home", outside));
        assertClassification(
                MunichResidentClassifier.Classification.CONFLICTING_HOME_LOCATIONS,
                person("conflict", plan));
    }

    @Test
    void missingHomeActivityIsUnresolved() {
        assertClassification(MunichResidentClassifier.Classification.NO_HOME_ACTIVITY,
                person("no-home", plan(activity("work", inside),
                        activity("shopping", outside))));
    }

    @Test
    void missingHomeCoordinateIsUnresolved() {
        Activity home = factory.createActivityFromLinkId("home", Id.createLinkId("home-link"));
        assertClassification(MunichResidentClassifier.Classification.MISSING_HOME_COORDINATE,
                person("missing-coordinate", plan(home, activity("work", inside))));
    }

    @Test
    void stageActivityWithHomeStemIsNeverTreatedAsHome() {
        Activity stage = activity(TripStructureUtils.createStageActivityType("home"), inside);
        assertClassification(MunichResidentClassifier.Classification.NO_HOME_ACTIVITY,
                person("stage-only", plan(activity("work", inside), stage,
                        activity("shopping", outside))));
    }

    @Test
    void missingSelectedPlanIsInvalid() {
        Person person = factory.createPerson(Id.createPersonId("invalid"));
        assertClassification(MunichResidentClassifier.Classification.INVALID_SELECTED_PLAN,
                person);
    }

    private static void assertClassification(MunichResidentClassifier.Classification expected,
                                             Person person) {
        assertEquals(expected, classifier.classify(person).classification());
    }

    private static Person person(String id, Plan plan) {
        Person person = factory.createPerson(Id.createPersonId(id));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Plan plan(Activity... activities) {
        Plan plan = factory.createPlan();
        for (int index = 0; index < activities.length; index++) {
            if (index > 0) plan.addLeg(factory.createLeg("walk"));
            plan.addActivity(activities[index]);
        }
        return plan;
    }

    private static Activity activity(String type, Coord coordinate) {
        return factory.createActivityFromCoord(type, coordinate);
    }
}
