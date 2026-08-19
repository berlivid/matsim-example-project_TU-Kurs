# Munich MATSim 2040 scenario project

This repository supports a political-science master’s thesis comparing two Munich transport scenarios for 2040:

- **BAU 2040:** the cleaned GTFS 2037 forecast and the common background road network;
- **Fast Track 2040:** BAU plus U9, the U4 extension and two Nordring services associated with infrastructure that could be accelerated for a hypothetical Munich Olympic Games.

Pricing is outside the final thesis scope. Scenario results must be interpreted as modelled contrasts under common assumptions, not as causal forecasts with exact real-world probabilities.

## Common model basis

The project uses MATSim 2025.0 and Java 21, the existing Munich model, a projected 2040 population derived from its five-percent sample, coordinate system `EPSG:31468`, and one shared road network. The GTFS service date 13 February 2026 is only the technical date that activates the forecast feed; it is not the scenario year.

Authoritative methods:

- [Population 2040](docs/methodology/population_2040.md)
- [GTFS filtering](docs/gtfs2040/gtfs2037_munich_filter_method.md)
- [BAU and Fast Track public transport](docs/gtfs2040/gtfs2037_fast_track_method.md)
- [MATSim transit inputs](docs/gtfs2040/matsim_2040_transit_inputs.md)
- [Run log](docs/run_log/run_log.md)

## Current status

The BAU and Fast Track GTFS feeds and separate MATSim network/schedule/vehicle files have been generated and validated. Public transport is activated in both production configurations, and focused routing and iteration-zero smoke tests have passed. No calibrated or full BAU/Fast Track simulation has been run. Olympic Village and Media Village demand/facility representation, remaining road and non-PT measures, calibration, sensitivities and final runs remain pending.

## Reproduction

```powershell
# Targeted tests
.\mvnw.cmd -q -Dtest=BuildFastTrackGtfs2037Test test

# Fast Track GTFS
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode build

# Fast Track MATSim transit inputs only
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track
```

Large raw and generated inputs are excluded from Git. Specifications, source code, scripts, tests and methodology documents remain version-controlled.

## Licensing

MATSim program code follows the repository’s GNU GPL v2 terms. MATSim inputs, outputs and analyses follow their stated data licences; external source data retain the licences and conditions of their providers.
