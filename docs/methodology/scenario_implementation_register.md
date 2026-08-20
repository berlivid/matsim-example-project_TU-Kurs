# BAU 2040 and Fast Track 2040 implementation register

## Purpose and classification method

This register audits the complete measure matrix in `Infrastructure_measures.xlsx` against the current project state. It separates policy inclusion from technical executability: a successfully loaded MATSim transit schedule does not prove that every policy measure is substantively present.

The workbook sheet `Maßnahmen`, rows 5–31, was read from an unchanged temporary copy because the source workbook was open in another process. Most source `measure_id` cells are blank, so the register uses transparent audit identifiers `XLSX_ROW_05` through `XLSX_ROW_31`; these are not claimed to be source identifiers.

Statuses mean:

- **implemented and tested**: the intended service or mechanism is present and has passed at least structural MATSim validation; measure-specific checks already exist or the audited stop pattern is explicit;
- **partially or indirectly represented**: an operating pattern, population distribution or enabling assumption is present, but the physical or behavioural effect is incomplete;
- **still required**: the matrix includes the measure but the necessary representation is absent or substantively unresolved;
- **intentionally not modelled**: the matrix excludes it or the current model cannot represent its intended effect without a new calibrated method.

The CSV register is authoritative for row-level decisions: [`scenario_implementation_register.csv`](scenario_implementation_register.csv).

## Main findings

| Status | Measures | Interpretation |
|---|---:|---|
| Implemented and tested | 12 | Poccistraße, Berduxstraße, Nordring, second S-Bahn trunk, three forecast tram projects, U5 Pasing, U5 Freiham, U6 Martinsried, U9 and U4 East |
| Partially or indirectly represented | 7 | Daglfing–Johanneskirchen capacity, Westkreuz junction, Sendlinger Spange, Paul-Gerhardt-Allee, the new Hauptbahnhof, Olympic Village and Media Village |
| Still required | 2 | Pedestrian zones and mobility hubs |
| Intentionally not modelled | 6 | Autonomous operation, cycle express route, park corridors and the three measures excluded from both scenarios |

### Public-transport completeness

The cleaned forecast feed substantively represents the second S-Bahn trunk: representative S18X, S23X and S24X patterns serve `München Hbf tief 2. Stammstrecke`, `Marienplatz Marienhof 2. Stammstrecke` and `Ostbahnhof 2. Stammstrecke`. It also contains S20 patterns between Pasing, Heimeranplatz, Mittersendling and Solln. This is evidence of a Sendlinger-Spange-related operating path, but not evidence that additional capacity, disruption resilience or a specific frequency improvement has been modelled. The measure therefore remains indirect pending an explicit operating comparison.

The common-measures stage adds dedicated rail-platform records rather than reusing bus or underground platform IDs. Poccistraße is inserted in 234 eligible regional trips on the six Excel-specified routes. Berduxstraße is inserted in 203 regular S2 trips identified by the exact route and complete calling pattern; express routes are excluded. Project-internal current-GTFS evidence supports 60 seconds dwell at each new stop; no separate braking/acceleration penalty can be identified. Eight directed 180-second transfers connect the new Poccistraße rail platforms to U3/U6. Existing parent-station centroids are transparent scenario-assumption proxies, not official future platform coordinates. Both measures are inherited unchanged by Fast Track.

The Sendlinger Spange remains indirect by design. Excel specifies no published normal-weekday timetable change, so existing S20 and other timetable rows remain unchanged. Operational stability, diversions and disruption benefits are not represented in the normal-day MATSim simulation.

The Nordring, U9 and U4 extension are complete within their documented strategic-scenario assumptions. The four-track Daglfing–Johanneskirchen project remains only an enabling condition; neither physical railway capacity nor freight interaction is simulated.

## Population and place-based measures

BAU retains `population_2040.xml`. Fast Track uses `population_2040_fast_track.xml`, deterministically derived from the byte-identical common source population (SHA-256 `FF93581E4FF105BE86408102BFA3D45CC0CC06C200763DA01B0DC344C4323C6B`). The builder relocates 525 Home persons and 175 Work persons to the Olympic Village, and 175 Home persons and 58 Work persons to the Media Village. It creates no new persons and preserves activity times, modes, plan structures and person attributes.

The demand centroids are transformed from supplied WGS84 coordinates to `EPSG:31468`. Olympic Village uses `(4474723.879, 5335134.368)` and car link `3215` at 2.0 metres. Media Village uses `(4474646.104, 5333855.294)` and car link `416540` at 190.0 metres. These are scenario assumptions and routing anchors, not official site boundaries or invented roads.

Existing activities were counted as a diagnostic using circular spatial proxies in `EPSG:31468`. These buffers are not official development boundaries and must not be used to assign final population:

| Diagnostic area | Proxy | Persons with home activity | Persons with work activity | Persons with any activity |
|---|---|---:|---:|---:|
| Olympic Village | 1.5 km around the midpoint of Englschalking and Johanneskirchen | 1,529 | 470 | 4,217 |
| Media Village | 1 km around Trabrennbahn stop 107720 | 240 | 375 | 1,215 |
| Paul-Gerhardt-Allee | 1 km around stop 109761 | 587 | 182 | 1,534 |

The earlier diagnostics demonstrate substantial pre-existing modelled activity. The implemented relocation avoids increasing the total population, but future refinement must still distinguish:

1. **relocation**, where existing synthetic persons or activities are moved while total population is conserved;
2. **additional long-term population**, which must be reconciled with the demographic projection already used to create `population_2040.xml`;
3. **temporary Games-time demand**, which should be represented as separate visitor, worker or accommodation activity chains rather than permanent residents.

Paul-Gerhardt-Allee already contains modelled residents and activities and existing bus service. Whether the demographic forecast already incorporates the full development is unknown. No new residents should be added until the development boundary and the demographic input provenance are reconciled. No measure-specific road-network edit is currently documented.

## Road, walking, cycling and hub measures

The road component is identical between BAU and Fast Track. Therefore no Fast Track-specific pedestrian-zone, mobility-hub or other road intervention is currently present.

Verified motor-vehicle restrictions could be encoded by changing allowed modes on precisely identified car links, but this would represent only the car restriction. The public-space and pedestrian benefit would remain absent. Cycling and park-corridor benefits cannot be represented credibly while bicycle travel is not routed on a calibrated link network. Mobility hubs also lack a defined facility inventory and a represented parking, shared-mobility or access mechanism. Arbitrary changes to scoring or mode constants are not recommended.

Autonomous public transport is excluded as a technology label because automation itself has no direct MATSim passenger effect. Only independently evidenced changes to frequency, travel time or capacity could be modelled, and none are specified in the matrix.

## Prioritized next implementation list

1. **Regenerate and validate MATSim transit inputs.** Convert the rebuilt BAU and Fast Track GTFS feeds, rerun focused routing checks for both new stations, and only then regard the activated configs as current.
2. **Refine village demand without double counting.** Obtain official site boundaries and decide separately how permanent residents, relocated residents, Media Village accommodation, workplaces and temporary Games demand relate to the existing 2040 population.
3. **Audit Paul-Gerhardt-Allee against demographic and road-source provenance.** Establish whether its residents and road access are already embedded before changing population or network files.

Only after these tasks should lower-priority non-PT measures be considered. Pedestrian-zone link closures need authoritative geometry and access rules. Mobility hubs need named sites and a mechanism that affects represented modes. Cycle and park-corridor measures should remain outside the quantified comparison unless a calibrated active-mode model is added.

## Validation and limitations

This is an implementation audit, not a simulation result. The village update changes only the derived Fast Track population and its production population reference. BAU, GTFS, networks and facilities remain unchanged; no substantive simulation was run.

The forecast GTFS is a supplied planning dataset rather than an independently certified operational timetable. Physical railway capacity, construction disruption, station-concourse capacity, pedestrian amenity, cycling quality and land-use development are not automatically created by the PT pseudonetwork. Each later implementation must retain the register's distinction between source facts, modelling decisions and explicit assumptions.
