package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/** Tests assumptions that are critical for deterministic Fast Track builds. */
class BuildFastTrackGtfs2037Test {

    @TempDir
    Path temporaryDirectory;

    @Test
    void matsimConverterAcceptsTripWithoutShapeId() throws IOException {
        Path gtfs = temporaryDirectory.resolve("no-shape.zip");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\n"
                + "A,Test Agency,https://example.invalid,Europe/Berlin\n");
        files.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,"
                + "friday,saturday,sunday,start_date,end_date\n"
                + "1,1,1,1,1,1,1,1,20260213,20260213\n");
        files.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name,"
                + "route_type\nFT_TEST,A,FT,Fast Track test,1\n");
        files.put("trips.txt", "route_id,service_id,trip_id,trip_headsign,direction_id,"
                + "shape_id\nFT_TEST,1,FT_TEST_0,Second stop,0,\n");
        files.put("stops.txt", "stop_id,stop_name,stop_lat,stop_lon,location_type,"
                + "parent_station\nFT_A,First stop,48.100000,11.500000,0,\n"
                + "FT_B,Second stop,48.110000,11.510000,0,\n");
        files.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,"
                + "stop_sequence\nFT_TEST_0,06:00:00,06:00:00,FT_A,0\n"
                + "FT_TEST_0,06:05:00,06:05:00,FT_B,1\n");
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time\nFT_A,FT_B,2,300\n");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(gtfs))) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        GtfsConverter.newBuilder()
                .setFeed(gtfs)
                .setDate(LocalDate.parse("2026-02-13"))
                .setTransform(coord -> coord)
                .setScenario(scenario)
                .setUseExtendedRouteTypes(false)
                .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                .setIncludeMinimalTransferTimes(true)
                .build()
                .convert();

        assertEquals(1, scenario.getTransitSchedule().getTransitLines().size());
        assertEquals(2, scenario.getTransitSchedule().getFacilities().size());
        assertEquals(1, scenario.getTransitSchedule().getTransitLines().values()
                .iterator().next().getRoutes().size());
        assertEquals(300.0, scenario.getTransitSchedule().getMinimalTransferTimes().get(
                Id.create("FT_A", TransitStopFacility.class),
                Id.create("FT_B", TransitStopFacility.class)
        ));
    }
}
