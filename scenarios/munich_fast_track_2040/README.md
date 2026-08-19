# Fast Track 2040 scenario

Fast Track uses the same cleaned forecast, projected five-percent population and road network as BAU, then adds U9, the U4 extension and two Nordring services associated with infrastructure that could be accelerated for a hypothetical Munich Olympic Games. The four-track Daglfing–Johanneskirchen measure is only an enabling condition; physical rail capacity is not represented. Existing S8 service remains unchanged.

The production config `config_fast_track.xml` activates the validated files in `input_transit/`: `network-with-pt.xml.gz`, `transitSchedule.xml.gz` and `transitVehicles.xml.gz`. Focused SwissRailRaptor and two-agent iteration-zero tests passed. These are technical integration checks, not calibrated or substantive results. Remaining Olympic Village/Media Village demand, road and non-PT measures, calibration and sensitivities are pending. See [`docs/gtfs2040/matsim_2040_transit_inputs.md`](../../docs/gtfs2040/matsim_2040_transit_inputs.md).
