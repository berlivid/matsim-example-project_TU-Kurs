# MATSim public-transport inputs for the 2040 scenarios

## Analytical purpose

This step translates the two approved GTFS representations into MATSim inputs. It does not change the transport-policy scenarios themselves. BAU represents the cleaned 2037 forecast service, while Fast Track adds the previously documented U9 trunk, U4 extension and two Nordring services. Both representations use the same road network so that later differences between model results cannot be attributed to different background road infrastructure.

## Shared conversion rule

Both feeds are converted with the same Java implementation in `CreateGtfs2037MunichTransit`. The technical service date is 13 February 2026. GTFS coordinates are transformed from WGS84 to EPSG:31468, standard GTFS route types are used, stops are not merged, and GTFS minimum transfer times are included. MATSim then adds a public-transport pseudonetwork and one transit vehicle per departure. Pseudolinks permit only the MATSim `pt` mode and therefore cannot be used by cars.

The road-network input was resolved from each existing scenario configuration rather than assumed. Both configurations declare their local `studyNetworkDense.xml`. The two files are byte-identical (SHA-256 `FFE53A5CF7386D9255F9C1A7DF0DFD388410F9C84635582699C1826C3AF0572E`) and contain 212,772 nodes and 499,435 links. A semantic digest additionally covers road-node coordinates and attributes and road-link endpoints, length, free speed, capacity, lanes, allowed modes and attributes. This digest is unchanged after pseudonetwork construction and identical between scenarios (`B0B3B94A898427CFED6220077773C5C9366374FBDC03E9828BC2B1F0E08830B5`).

## Results

| Measure | BAU 2040 | Fast Track 2040 | Difference |
|---|---:|---:|---:|
| Combined network nodes | 249,190 | 249,212 | +22 |
| Combined network links | 561,977 | 562,016 | +39 |
| Transit stop facilities | 80,751 | 80,792 | +41 |
| Transit lines | 1,733 | 1,736 | +3 |
| Transit routes | 14,303 | 14,309 | +6 |
| Departures | 70,620 | 71,300 | +680 |
| Transit vehicles | 70,620 | 71,300 | +680 |
| Minimum-transfer relations | 95,876 | 95,896 | +20 |

The Fast Track schedule contains `FT_U9` (520 departures: 259 in direction 0 and 261 in direction 1), `FT_NR_A` (80 departures) and `FT_NR_B` (80 departures). Exact U9 direction/time duplicates are absent; positive sub-two-minute intervals are deliberately retained. The U4 retains its number of departures and serves the four new directional Cosimapark and Englschalking platform records. The 20 explicitly modelled transfer relations between the two new Impler-/Poccistraße platforms and five existing interchange platforms survive GTFS conversion, file writing and MATSim reloading with their specified transfer times. The S8 schedule signature is identical in BAU and Fast Track.

Current output checksums:

| File | BAU SHA-256 | Fast Track SHA-256 |
|---|---|---|
| Combined network | `D6E74CCD154282CE723769F734A7AFB03A23BECA1AC9AE2DEDDB3549991E033D` | `377D8E053B410FC503D2534C3D85A9D395E7ED43736AE425F6820983CC6B6006` |
| Transit schedule | `2CBEBBD0966C8CD0F02DB85CF4DA52DD66F9BA8883BFD1936E1CD534CA644817` | `C51EA48B6B7F6CFF5CC02418AA8DC9CE38F65F68720954D739F7E69FFDE41EB5` |
| Transit vehicles | `03F9617EB5D8E0CD464C4C8BF5B4ADC051B78431883BDD21B6A46CFB4B37CDCB` | `57F79CF0C15745A86EE517F8F2D27647F2EA323337EDBEA924B7DE1A2A6B0786` |

## Validation and reproducibility

The tool validates the converted objects before writing and loads all three compressed files again with MATSim before publication. It checks schedule-to-network links, route-network links, departure-to-vehicle and vehicle-to-type references, transfer endpoints and times, PT-only pseudolink modes, preservation of every road-network element and scenario-specific services. A separate validation mode reloads both published scenarios and compares the shared road component, S8 and U4 departure counts directly.

Run the complete conversion from the project root with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario all
```

Validate already generated outputs without repeating GTFS conversion with:

```powershell
$env:MAVEN_OPTS='-Xmx12g'
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd -q exec:java '-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit' '-Dexec.args=--validate-existing'
```

## Configuration activation and focused technical tests

Both production configurations now use `input_transit/network-with-pt.xml.gz`, `input_transit/transitSchedule.xml.gz` and `input_transit/transitVehicles.xml.gz` relative to their scenario directory. Transit is enabled and the CRS is `EPSG:31468`. Population, scoring, routing, capacity factors, strategy settings and mode-choice parameters were preserved. The machine-readable comparison is [`matsim_2040_config_comparison.csv`](matsim_2040_config_comparison.csv).

The project runners install SwissRailRaptor whenever transit is enabled. Full configuration loading confirmed 336,208 persons in each unchanged production population and all network, schedule and vehicle references. Focused daytime routes verified U6 to U9 at Münchner Freiheit; U9 interchange at Hauptbahnhof; the U9 to U3 interchange at Impler-/Poccistraße; U4 interchange at Englschalking; complete FT-NR-A and FT-NR-B journeys; the U4 extension; absence of the new lines in BAU; and an existing representative PT connection in BAU. The Impler-/Poccistraße route exits U9 at `FT_U9_IMPLER_POCCI_D0`, boards U3 at `106212`, and includes at least the documented 300-second transfer requirement. No additional transfer fix was required.

Separate iteration-zero runs used a deterministic two-person population created in memory, one PT traveller and one car traveller, a fixed seed of 4711 and a 09:30–11:00 QSim window. Both BAU and Fast Track started successfully; the PT traveller boarded and arrived, and the car traveller arrived. The production population was not loaded or modified. Smoke outputs are ignored under each scenario's `smoke-output/` directory.

Reproduce the checks from the project root:

```powershell
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd -q -DskipTests exec:java '-Dexec.mainClass=org.matsim.project.prepare.ValidateMatsim2040Activation'
.\mvnw.cmd -q -DskipTests exec:java '-Dexec.mainClass=org.matsim.project.RunMatsim2040TransitSmokeTest' '-Dexec.args=bau'
.\mvnw.cmd -q -DskipTests exec:java '-Dexec.mainClass=org.matsim.project.RunMatsim2040TransitSmokeTest' '-Dexec.args=fast-track'
```

These checks establish technical executability only. They do not validate behavioural calibration, demand forecasts or policy effects, and no calibrated or full BAU/Fast Track simulation has been completed.

## Limitations and implications

The combined networks contain synthetic PT links constructed from stop sequences; they are routing infrastructure for MATSim, not surveyed railway geometry. Parent stations and other unserved GTFS facilities may legitimately have no network link, whereas every facility used by a transit route is required to have one. Vehicle capacities remain MATSim converter defaults rather than operator-specific forecasts. No production population, facilities or road supply was changed. Olympic Village and Media Village representation, other road and non-PT measures, calibration, sensitivity analysis and substantive scenario runs remain pending.
