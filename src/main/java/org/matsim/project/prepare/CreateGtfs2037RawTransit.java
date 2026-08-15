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
 * Converts the unchanged GTFS-2037 archive for a technical MATSim test.
 *
 * <p>This converter deliberately writes to a separate test scenario. It does
 * not change the source GTFS, the base network, or any BAU/Fast-Track input.
 * The national feed should only be run after reviewing the raw-data audit in
 * {@code docs/gtfs2040/gtfs2037_raw_audit.md}.</p>
 */
public final class CreateGtfs2037RawTransit {

    private static final String BASE_NETWORK_FILE =
            "scenarios/munich_base_2023/studyNetworkDense.xml";
    private static final String GTFS_ZIP_FILE =
            "original-input-data/mvv_gtfs_2037/generated/gtfs2037_raw.zip";
    private static final String OUTPUT_DIRECTORY =
            "scenarios/munich_gtfs2037_raw_test/input_transit";
    private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-02-13");

    private CreateGtfs2037RawTransit() {
        // Utility class
    }

    public static void main(String[] args) throws Exception {
        requireRegularFile(BASE_NETWORK_FILE);
        requireRegularFile(GTFS_ZIP_FILE);

        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        Files.createDirectories(outputPath);

        String outputNetworkFile = outputPath.resolve(
                "studyNetworkDense-with-gtfs2037-raw-pt.xml.gz"
        ).toString();
        String outputScheduleFile = outputPath.resolve(
                "transitSchedule-gtfs2037-raw.xml.gz"
        ).toString();
        String outputVehiclesFile = outputPath.resolve(
                "transitVehicles-gtfs2037-raw.xml.gz"
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
         * createNetworkAndVehicles = true
         * copyEarlyAndLateDepartures = false (raw service day remains unchanged)
         * useExtendedRouteTypes = true
         */
        RunGTFS2MATSim.convertGTFSandAddToScenario(
                scenario,
                GTFS_ZIP_FILE,
                SERVICE_DATE,
                SERVICE_DATE,
                transformation,
                true,
                false,
                true,
                GtfsConverter.MergeGtfsStops.doNotMerge
        );

        new NetworkWriter(scenario.getNetwork()).write(outputNetworkFile);
        new TransitScheduleWriter(scenario.getTransitSchedule())
                .writeFile(outputScheduleFile);
        new MatsimVehicleWriter(scenario.getTransitVehicles())
                .writeFile(outputVehiclesFile);

        long transitRoutes = scenario.getTransitSchedule().getTransitLines()
                .values().stream()
                .mapToLong(line -> line.getRoutes().size())
                .sum();
        long departures = scenario.getTransitSchedule().getTransitLines()
                .values().stream()
                .flatMap(line -> line.getRoutes().values().stream())
                .mapToLong(route -> route.getDepartures().size())
                .sum();

        System.out.println("GTFS-2037 raw conversion completed.");
        System.out.println("Network: " + outputNetworkFile);
        System.out.println("Schedule: " + outputScheduleFile);
        System.out.println("Vehicles: " + outputVehiclesFile);
        System.out.println("Transit stops: "
                + scenario.getTransitSchedule().getFacilities().size());
        System.out.println("Transit lines: "
                + scenario.getTransitSchedule().getTransitLines().size());
        System.out.println("Transit routes: " + transitRoutes);
        System.out.println("Departures: " + departures);
        System.out.println("Transit vehicles: "
                + scenario.getTransitVehicles().getVehicles().size());
    }

    private static void requireRegularFile(String file) {
        if (!Files.isRegularFile(Path.of(file))) {
            throw new IllegalStateException("Required input file is missing: " + file);
        }
    }
}
