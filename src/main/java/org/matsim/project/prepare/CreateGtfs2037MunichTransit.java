package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.contrib.gtfs.RunGTFS2MATSim;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

/**
 * Converts the cleaned Munich GTFS-2037 subset into isolated MATSim test
 * inputs and then reads all three generated files again for verification.
 */
public final class CreateGtfs2037MunichTransit {

    private static final String BASE_NETWORK_FILE =
            "scenarios/munich_base_2023/studyNetworkDense.xml";
    private static final String GTFS_ZIP_FILE =
            "original-input-data/mvv_gtfs_2037/generated/"
                    + "gtfs2037_munich_clean.zip";
    private static final String OUTPUT_DIRECTORY =
            "scenarios/munich_gtfs2037_clean_test/input_transit";
    private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-02-13");

    private CreateGtfs2037MunichTransit() {
        // Utility class
    }

    public static void main(String[] args) throws Exception {
        requireRegularFile(BASE_NETWORK_FILE);
        requireRegularFile(GTFS_ZIP_FILE);

        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        Files.createDirectories(outputPath);

        String outputNetworkFile = outputPath.resolve(
                "studyNetworkDense-with-gtfs2037-munich-pt.xml.gz"
        ).toString();
        String outputScheduleFile = outputPath.resolve(
                "transitSchedule-gtfs2037-munich.xml.gz"
        ).toString();
        String outputVehiclesFile = outputPath.resolve(
                "transitVehicles-gtfs2037-munich.xml.gz"
        ).toString();

        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:31468");
        config.network().setInputFile(BASE_NETWORK_FILE);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        CoordinateTransformation transformation =
                TransformationFactory.getCoordinateTransformation(
                        TransformationFactory.WGS84,
                        "EPSG:31468"
                );

        /*
         * The cleaned feed uses only standard GTFS route types. Early/late
         * departures are not copied, so the technical service day is not
         * expanded beyond the source timetable.
         */
        RunGTFS2MATSim.convertGTFSandAddToScenario(
                scenario,
                GTFS_ZIP_FILE,
                SERVICE_DATE,
                SERVICE_DATE,
                transformation,
                true,
                false,
                false,
                GtfsConverter.MergeGtfsStops.doNotMerge
        );

        Counts generatedCounts = Counts.from(scenario);
        new NetworkWriter(scenario.getNetwork()).write(outputNetworkFile);
        new TransitScheduleWriter(scenario.getTransitSchedule())
                .writeFile(outputScheduleFile);
        new MatsimVehicleWriter(scenario.getTransitVehicles())
                .writeFile(outputVehiclesFile);

        scenario = null;
        System.gc();

        Config verificationConfig = ConfigUtils.createConfig();
        verificationConfig.global().setCoordinateSystem("EPSG:31468");
        verificationConfig.network().setInputFile(outputNetworkFile);
        verificationConfig.transit().setUseTransit(true);
        verificationConfig.transit().setTransitScheduleFile(outputScheduleFile);
        verificationConfig.transit().setVehiclesFile(outputVehiclesFile);

        Scenario verifiedScenario = ScenarioUtils.loadScenario(verificationConfig);
        Counts verifiedCounts = Counts.from(verifiedScenario);
        if (!generatedCounts.equals(verifiedCounts)) {
            throw new IllegalStateException(
                    "MATSim reread counts differ from generated counts: generated="
                            + generatedCounts + ", reread=" + verifiedCounts
            );
        }

        System.out.println("Filtered GTFS-2037 MATSim conversion completed.");
        System.out.println("Network: " + outputNetworkFile);
        System.out.println("Schedule: " + outputScheduleFile);
        System.out.println("Vehicles: " + outputVehiclesFile);
        System.out.println("MATSim reread verification: PASS");
        System.out.println("Transit stops: " + verifiedCounts.transitStops());
        System.out.println("Transit lines: " + verifiedCounts.transitLines());
        System.out.println("Transit routes: " + verifiedCounts.transitRoutes());
        System.out.println("Departures: " + verifiedCounts.departures());
        System.out.println("Transit vehicles: " + verifiedCounts.transitVehicles());
        System.out.println("Total network nodes: " + verifiedCounts.networkNodes());
        System.out.println("Total network links: " + verifiedCounts.networkLinks());
    }

    private static void requireRegularFile(String file) {
        if (!Files.isRegularFile(Path.of(file))) {
            throw new IllegalStateException("Required input file is missing: " + file);
        }
    }

    private record Counts(
            long transitStops,
            long transitLines,
            long transitRoutes,
            long departures,
            long transitVehicles,
            long networkNodes,
            long networkLinks
    ) {
        static Counts from(Scenario scenario) {
            long routes = scenario.getTransitSchedule().getTransitLines()
                    .values().stream()
                    .mapToLong(line -> line.getRoutes().size())
                    .sum();
            long departures = scenario.getTransitSchedule().getTransitLines()
                    .values().stream()
                    .flatMap(line -> line.getRoutes().values().stream())
                    .mapToLong(route -> route.getDepartures().size())
                    .sum();
            return new Counts(
                    scenario.getTransitSchedule().getFacilities().size(),
                    scenario.getTransitSchedule().getTransitLines().size(),
                    routes,
                    departures,
                    scenario.getTransitVehicles().getVehicles().size(),
                    scenario.getNetwork().getNodes().size(),
                    scenario.getNetwork().getLinks().size()
            );
        }
    }
}
