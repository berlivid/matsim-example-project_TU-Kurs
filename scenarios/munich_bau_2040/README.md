# BAU 2040 scenario

BAU is the reference scenario for the thesis comparison. Its GTFS is built from the cleaned Munich forecast and then adds the common Poccistraße regional stop and Berduxstraße S-Bahn stop. The Sendlinger Spange has no separate regular-day timetable modification. The technical GTFS date is 13 February 2026 and does not define the scenario year. BAU retains the unchanged common `population_2040.xml`; Fast Track derives a separate village-relocation population from that baseline. Road supply remains shared.

The production config `config_bau.xml` references the rebuilt files in `input_transit/`. MATSim readback and focused routes to Poccistraße and Berduxstraße, including the regional-to-U3/U6 interchange, pass. This is technical validation rather than substantive model validation. See the [authoritative transit-input methodology](../../docs/gtfs2040/matsim_2040_transit_inputs.md).
