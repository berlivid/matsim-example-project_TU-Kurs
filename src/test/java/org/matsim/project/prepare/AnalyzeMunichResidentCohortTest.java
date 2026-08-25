package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

class AnalyzeMunichResidentCohortTest {
    private static MunichMunicipalBoundary boundary;
    private static PopulationFactory factory;
    private static Coord inside;
    private static Coord outsideOne;
    private static Coord outsideTwo;

    @BeforeAll
    static void loadBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        factory = PopulationUtils.getFactory();
        var interior = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(interior.x, interior.y);
        var envelope = boundary.envelope();
        outsideOne = new Coord(envelope.getMinX() - 10_000,
                envelope.getMinY() - 10_000);
        outsideTwo = new Coord(envelope.getMinX() - 20_000,
                envelope.getMinY() - 20_000);
    }

    @Test
    void allResidentTripsCountRegardlessOfTerritorialCategory() {
        Person crossingResident = person("resident-crossing", "car",
                activity("home", inside), activity("work", outsideOne));
        Person externalTripResident = person("resident-external", "bike",
                activity("home", inside), activity("work", outsideOne),
                activity("shopping", outsideTwo));
        Person nonResidentInsideTrip = person("non-resident", "pt",
                activity("home", outsideOne), activity("work", inside),
                activity("shopping", inside));

        var result = AnalyzeMunichResidentCohort.analyzePersons(
                List.of(crossingResident, externalTripResident, nonResidentInsideTrip), boundary);

        assertEquals(2, result.residents());
        assertEquals(1, result.nonResidents());
        assertEquals(0, result.unresolvedPersons());
        assertEquals(3, result.residentMainTrips());
        assertEquals(2, result.residentTripsByScope().get(
                MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY));
        assertEquals(1, result.residentTripsByScope().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE));
        assertEquals(0, result.residentTripsByScope().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE));
        assertEquals(3, result.spatialCategorySum());
        assertEquals(2, result.residentTripsByInputMode().get("bike"));
        assertEquals(1, result.residentTripsByInputMode().get("car"));
        assertFalse(result.residentTripsByInputMode().containsKey("pt"),
                "Trips of the non-resident must not enter the resident cohort");
        assertEquals(5, result.totalMainTrips());
    }

    @Test
    void closedSubtourReadinessCoversEveryResident() {
        Person closed = person("closed", "walk", activity("home", inside),
                activity("work", outsideOne), activity("home", inside));
        Person open = person("open", "walk", activity("home", inside),
                activity("work", outsideOne));
        var result = AnalyzeMunichResidentCohort.analyzePersons(
                List.of(closed, open), boundary);
        assertEquals(1, result.residentsWithClosedSubtour());
        assertEquals(1, result.residentsWithoutClosedSubtour());
    }

    @Test
    void outputFilesAreStableAndContainCompletenessCheck(@TempDir Path temp)
            throws Exception {
        Person resident = person("resident", "car", activity("home", inside),
                activity("work", outsideOne));
        var result = AnalyzeMunichResidentCohort.analyzePersons(List.of(resident), boundary);
        AnalyzeMunichResidentCohort.write(result, temp);
        String first = Files.readString(temp.resolve("preflight_report.md"));
        AnalyzeMunichResidentCohort.write(result, temp);
        assertEquals(first, Files.readString(temp.resolve("preflight_report.md")));
        assertTrue(first.contains("Spatial-category completeness check: 1 = 1"));
        assertTrue(Files.isRegularFile(temp.resolve("resident_classification_summary.csv")));
        assertTrue(Files.isRegularFile(temp.resolve("resident_trip_scope_summary.csv")));
        assertTrue(Files.isRegularFile(temp.resolve("resident_input_mode_summary.csv")));
        assertTrue(Files.isRegularFile(temp.resolve("unresolved_residents.csv")));
    }

    @Test
    void populationPathComesFromAuthoritativeConfig() {
        var config = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.CONFIG.toString());
        Path expected = ValidateModeChoiceCalibrationConfig.CONFIG.getParent()
                .resolve(config.plans().getInputFile()).normalize();
        assertEquals(expected, AnalyzeMunichResidentCohort.resolvePopulation(config));
    }

    private static Person person(String id, String mode, Activity... activities) {
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        for (int index = 0; index < activities.length; index++) {
            if (index > 0) plan.addLeg(factory.createLeg(mode));
            plan.addActivity(activities[index]);
        }
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Activity activity(String type, Coord coordinate) {
        return factory.createActivityFromCoord(type, coordinate);
    }
}
