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

public final class CreateCurrentMvvTransit {

    private CreateCurrentMvvTransit() {
        // Utility class
    }

    public static void main(String[] args) throws Exception {

        /*
         * Adjust these paths if your actual files have different names.
         * Paths are relative to the project root.
         */
        String baseNetworkFile =
                "scenarios/munich_base_2023/studyNetworkDense.xml";

        String gtfsZipFile =
                "original-input-data/mvv_gtfs_2026/gesamt_gtfs.zip";

        String outputDirectory =
                "scenarios/munich_base_2023/input_transit";

        /*
         * Replace this example date with the representative weekday
         * that you verified in calendar.txt/calendar_dates.txt.
         */
        LocalDate serviceDate = LocalDate.parse("2026-09-16");

        Path outputPath = Path.of(outputDirectory);
        Files.createDirectories(outputPath);

        String outputNetworkFile =
                outputPath.resolve("studyNetworkDense-with-pt.xml.gz").toString();

        String outputScheduleFile =
                outputPath.resolve("transitSchedule-current.xml.gz").toString();

        String outputVehiclesFile =
                outputPath.resolve("transitVehicles-current.xml.gz").toString();

        /*
         * Load the existing Munich road network first.
         * The PT pseudo-network will then be added to this scenario.
         */
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:31468");
        config.network().setInputFile(baseNetworkFile);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        /*
         * GTFS stop coordinates are WGS84 longitude/latitude.
         * Transform them to the coordinate system of the Munich model.
         */
        CoordinateTransformation transformation =
                TransformationFactory.getCoordinateTransformation(
                        TransformationFactory.WGS84,
                        "EPSG:31468"
                );

        /*
         * Boolean parameters:
         * 1. createNetworkAndVehicles = true
         * 2. copyEarlyAndLateDepartures = true
         * 3. useExtendedRouteTypes = true
         */
        RunGTFS2MATSim.convertGTFSandAddToScenario(
                scenario,
                gtfsZipFile,
                serviceDate,
                serviceDate,
                transformation,
                true,
                true,
                true,
                GtfsConverter.MergeGtfsStops.doNotMerge
        );

        new NetworkWriter(scenario.getNetwork())
                .write(outputNetworkFile);

        new TransitScheduleWriter(scenario.getTransitSchedule())
                .writeFile(outputScheduleFile);

        new MatsimVehicleWriter(scenario.getTransitVehicles())
                .writeFile(outputVehiclesFile);

        System.out.println("GTFS conversion completed.");
        System.out.println("Network: " + outputNetworkFile);
        System.out.println("Schedule: " + outputScheduleFile);
        System.out.println("Vehicles: " + outputVehiclesFile);
        System.out.println(
                "Transit stops: "
                        + scenario.getTransitSchedule().getFacilities().size()
        );
        System.out.println(
                "Transit lines: "
                        + scenario.getTransitSchedule().getTransitLines().size()
        );
        System.out.println(
                "Transit vehicles: "
                        + scenario.getTransitVehicles().getVehicles().size()
        );
    }
}
