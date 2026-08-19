# BAU 2040 scenario

BAU is the reference scenario for the thesis comparison. Its public-transport input is the cleaned GTFS 2037 Munich forecast. The technical GTFS date is 13 February 2026 and does not define the scenario year. The scenario shares its projected five-percent 2040 population and road network with Fast Track.

The production config `config_bau.xml` activates the validated files in `input_transit/`: `network-with-pt.xml.gz`, `transitSchedule.xml.gz` and `transitVehicles.xml.gz`. A two-agent, iteration-zero technical smoke test passed; it is not a calibrated or substantive scenario run. See [`docs/gtfs2040/matsim_2040_transit_inputs.md`](../../docs/gtfs2040/matsim_2040_transit_inputs.md).
