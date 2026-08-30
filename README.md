# Munich MATSim 2040 scenario project

This repository supports a political-science master’s thesis comparing two Munich transport scenarios for 2040:

- **BAU 2040:** the cleaned GTFS 2037 forecast plus the common Poccistraße and Berduxstraße rail stops and the common background road network;
- **Fast Track 2040:** BAU plus U9, the U4 extension, two Nordring services and the approved Herzog-Wilhelm-Straße/Kreuzstraße car-link restrictions associated with infrastructure that could be accelerated for a hypothetical Munich Olympic Games.

Pricing is outside the final thesis scope. Scenario results must be interpreted as modelled contrasts under common assumptions, not as causal forecasts with exact real-world probabilities.

## Common model basis

The project uses MATSim 2025.0 and Java 21, the existing Munich model, a projected 2040 population derived from its five-percent sample, coordinate system `EPSG:31468`, and one shared base road network. Fast Track deterministically removes `car` from 12 spatially selected links and one technical boundary connector while retaining all 13 links and every other mode; BAU retains the base road component unchanged. The GTFS service date 13 February 2026 is only the technical date that activates the forecast feed; it is not the scenario year.

Authoritative methods:

- [Population 2040](docs/methodology/population_2040.md)
- [Literature-based 2019 scoring diagnostic](docs/methodology/literature_based_scoring_diagnostic.md)
- [Shared BAU/Fast Track production analysis](docs/methodology/production_2040_analysis.md)
- [GTFS filtering](docs/gtfs2040/gtfs2037_munich_filter_method.md)
- [BAU and Fast Track public transport](docs/gtfs2040/gtfs2037_fast_track_method.md)
- [MATSim transit inputs](docs/gtfs2040/matsim_2040_transit_inputs.md)
- [Run log](docs/run_log/run_log.md)

## Current status

The BAU and Fast Track GTFS feeds and their activated MATSim transit inputs have been rebuilt and revalidated with the common Poccistraße and Berduxstraße measures. Fast Track additionally contains the Olympic Village and Media Village population relocation and a 13-link pedestrian-zone car restriction: 12 spatially selected links plus one technical boundary connector required for car-network consistency. The connector is not an extension of the planned pedestrian area. The Sendlinger Spange is documented as an indirect normal-day representation without added GTFS rows. Focused readback and routing tests pass; no calibrated or full BAU/Fast Track simulation has been run.

An isolated literature-based 2019 scoring diagnostic is prepared on a separate
branch. It resets the four choice-mode constants, keeps walk as the permanent
zero reference, introduces an explicit car operating cost and uses observed
Munich walk/bike speeds. It is a short diagnostic rather than a calibrated
result; no later BAU or Fast Track run may proceed with different scoring
specifications.

## Reproduction

```powershell
# Targeted tests
.\mvnw.cmd -q -Dtest=BuildCommonGtfs2037MeasuresTest test
.\mvnw.cmd -q -Dtest=BuildFastTrackGtfs2037Test test
.\mvnw.cmd -q -Dtest=FastTrackPedestrianZonesTest test

# Common BAU measures
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_common_gtfs2037.ps1 -Mode build

# Fast Track GTFS
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode build

# Both MATSim transit input sets
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario all
```

Large raw and generated inputs are excluded from Git. Specifications, source code, scripts, tests and methodology documents remain version-controlled.

## Licensing

MATSim program code follows the repository’s GNU GPL v2 terms. MATSim inputs, outputs and analyses follow their stated data licences; external source data retain the licences and conditions of their providers.
