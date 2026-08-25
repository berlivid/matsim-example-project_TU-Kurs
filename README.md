# Munich MATSim 2040 scenario project

This repository supports a political-science master’s thesis comparing two Munich transport scenarios for 2040:

- **BAU 2040:** the cleaned GTFS 2037 forecast plus the common Poccistraße and Berduxstraße rail stops and the common background road network;
- **Fast Track 2040:** BAU plus U9, the U4 extension, two Nordring services and the approved Herzog-Wilhelm-Straße/Kreuzstraße car-link restrictions associated with infrastructure that could be accelerated for a hypothetical Munich Olympic Games.

Pricing is outside the final thesis scope. Scenario results must be interpreted as modelled contrasts under common assumptions, not as causal forecasts with exact real-world probabilities.

## Common model basis

The project uses MATSim 2025.0 and Java 21, the existing Munich model, a projected 2040 population derived from its five-percent sample, coordinate system `EPSG:31468`, and one shared base road network. Fast Track deterministically removes `car` from 12 spatially selected links and one technical boundary connector while retaining all 13 links and every other mode; BAU retains the base road component unchanged. The GTFS service date 13 February 2026 is only the technical date that activates the forecast feed; it is not the scenario year.

Authoritative methods:

- [Population 2040](docs/methodology/population_2040.md)
- [GTFS filtering](docs/gtfs2040/gtfs2037_munich_filter_method.md)
- [BAU and Fast Track public transport](docs/gtfs2040/gtfs2037_fast_track_method.md)
- [MATSim transit inputs](docs/gtfs2040/matsim_2040_transit_inputs.md)
- [Run log](docs/run_log/run_log.md)

## Workflow navigation

- **Active production components:** the versioned GTFS builders, population
  preparation, scenario specifications, validators and shared analysis support
  remain active.
- **Protected GTFS preparation:** the synthetic GTFS 2019 validation chain and
  the GTFS 2037 clean/common/Fast-Track chain remain reproducible and must not
  be mixed with calibration experiments.
- **BAU and Fast Track:** `config_bau.xml` and `config_fast_track.xml` remain
  the protected 2040 scenario configurations; neither is modified by the
  calibration cleanup.
- **Legacy calibration experiments:** the former `BOTH_INSIDE` rounds and the
  rejected Open-Tour test are technical provenance, summarized in the
  [BOTH_INSIDE legacy note](docs/methodology/legacy/both_inside_calibration_preliminary.md)
  and [Open-Tour legacy note](docs/methodology/legacy/open_tour_mode_choice_experiment.md).
- **Forthcoming resident-based calibration:** the full regional population
  will remain simulated, while future calibration and primary analysis will
  include all trips made by Munich residents. Residence will later be derived
  from the home activity; that classifier and its config do not yet exist.

Future shared IntelliJ run configurations should use stable number groups:
`01–09` for input preparation and validation, `10–19` for calibration,
`20–29` for BAU/Fast Track runs and `30–39` for result analysis. Existing
productive configurations are not broadly renamed by this cleanup.

## Current status

The BAU and Fast Track GTFS feeds and their activated MATSim transit inputs have been rebuilt and revalidated with the common Poccistraße and Berduxstraße measures. Fast Track additionally contains the Olympic Village and Media Village population relocation and a 13-link pedestrian-zone car restriction: 12 spatially selected links plus one technical boundary connector required for car-network consistency. The connector is not an extension of the planned pedestrian area. The Sendlinger Spange is documented as an indirect normal-day representation without added GTFS rows. Focused readback and routing tests pass; no calibrated or full BAU/Fast Track simulation has been run.

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
