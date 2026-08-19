# Method for constructing the Fast Track GTFS 2037 scenario feed

## Purpose and analytical boundary

This processing step translates four approved Fast Track measures into a reproducible public-transport input for the Munich 2040 MATSim comparison. It starts from the cleaned and spatially filtered Munich GTFS 2037 baseline. It does not alter the baseline ZIP, raw source data, the MATSim network, population data, configuration files, or existing BAU and Fast Track scenario inputs.

The tool separates review from construction:

- `--analyze` checks the policy transcription, baseline stops and platforms, comparison timetables, spatial decisions and service assumptions. It writes preflight evidence but does not create or modify a GTFS feed.
- `--build` repeats the analysis and is fail-closed. It creates the Fast Track ZIP only when the analyzer reports zero critical blockers. The candidate ZIP is validated and converted to MATSim in memory before it can replace the designated output.

This separation prevents uncertain future locations or timetable interpretations from entering the scenario as if they were source facts.

## Inputs and their methodological roles

| Input | Methodological role |
|---|---|
| `Infrastructure_measures.xlsx`, sheet `Maßnahmen`, rows 14, 15, 29 and 30, columns M:R | Policy source specifying the intended projects, stop patterns, service requirements and exclusions. |
| `fast_track_service_specification.csv` | Version-controlled transcription of the relevant policy requirements and explicitly labelled service assumptions. |
| `fast_track_stop_decisions.csv` | Version-controlled decision register for future platforms, spatial proxies and explicit interchange rules. |
| `generated/gtfs2037_munich_clean.zip` | Unchanged operational baseline from which agencies, service ID, existing objects and comparison timetables are derived. |

The Java tool reads the named workbook cells directly from the XLSX container using standard Java ZIP and XML facilities. No Excel runtime library is added. The workbook remains the policy source, while the two CSV files are the deterministic modelling inputs.

The technical GTFS service remains `service_id=1` on 13 February 2026. This date is an input-format convention and does not redefine the scenario year 2040.

## Source facts, derived evidence and scenario assumptions

Three evidence categories are kept separate throughout the method:

1. **Source facts** are statements in the infrastructure workbook or cited official project material, such as the intended corridors, exclusions, 30-minute Nordring interval and 40 daily train pairs.
2. **Derived evidence** is calculated from the cleaned baseline GTFS, including existing parent and platform IDs, observed departure patterns, median moving speeds and dwell times.
3. **Scenario assumptions** fill information that the source material does not specify sufficiently for a routable GTFS feed. These include all future-platform coordinate proxies, the Impler-/Poccistraße transfer time, modelled segment times and the regularized Nordring operating span.

No spatial proxy in this feed should be interpreted as an officially approved future station or platform location.

## Service representation

| Measure | GTFS action | Stop pattern | Service rule | Explicit exclusions |
|---|---|---|---|---|
| FT-NR-A | Add rail route `FT_NR_A`, `route_type=2` | Dachau–Karlsfeld–Lassallestraße–BMW-FIZ–Euro-Industriepark–Johanneskirchen–Englschalking–Daglfing–Trudering–Haar | 40 departures in each direction at 30-minute intervals from 04:30 through 24:00 | Gronsdorf and the Olympiastadion branch |
| FT-NR-B | Add rail route `FT_NR_B`, `route_type=2` | Feldmoching–Lassallestraße–BMW-FIZ–Euro-Industriepark–Englschalking–Riem | 40 departures in each direction at 30-minute intervals from 04:30 through 24:00 | Johanneskirchen, Daglfing and the Olympiastadion branch |
| FT-NR-ENABLER | Document only | Daglfing–Englschalking–Johanneskirchen | No GTFS rows; the existing S8 remains unchanged | No separate service effect |
| FT-U9 | Add subway route `FT_U9`, `route_type=1` | Münchner Freiheit–Elisabethplatz–Pinakotheken–Hauptbahnhof–Esperantoplatz–Impler-/Poccistraße–Harras | Copy directional endpoint departures from suitable U6 trips; use U3 as a plausibility comparison | Theresienstraße and U29 |
| FT-U4-EXT | Extend route `MUC_U4_neu Prognose` | Arabellapark–Cosimapark–Englschalking–Messestadt West | Extend every U4 trip beginning or ending at Arabellapark while preserving its existing operating pattern | Fideliopark and Pellegrinistraße |

The four-track expansion between Daglfing and Johanneskirchen is represented only as an enabling condition for the Nordring services. It does not generate a route, trip, stop or timetable change, and the S8 timetable is copied unchanged.

## Approved stop and platform decisions

All locations in the following table are approved **scenario assumptions**. Existing parent IDs provide stable interchange relationships, but their coordinates are used only as spatial proxies for future rail or subway platforms.

| Measure and planned stop | Parent used | New directional platforms | WGS84 proxy latitude, longitude | Interpretation |
|---|---:|---|---|---|
| Nordring Lassallestraße | `106958` | `FT_NR_LASSALLESTRASSE_D0`, `FT_NR_LASSALLESTRASSE_D1` | 48.187045, 11.533901 | Existing bus-station parent used as a rail-station proxy; bus platforms are not reused. |
| Nordring BMW-FIZ | `107777` | `FT_NR_BMW_FIZ_D0`, `FT_NR_BMW_FIZ_D1` | 48.193476, 11.569959 | Existing bus-station parent used as a rail-station proxy; bus platforms are not reused. |
| Nordring Euro-Industriepark | `107187` | `FT_NR_EURO_INDUSTRIEPARK_D0`, `FT_NR_EURO_INDUSTRIEPARK_D1` | 48.192638, 11.584036 | Euro-Industriepark West is used as the proxy; Euro-Industriepark Nord is excluded from this decision. |
| U9 Münchner Freiheit | `107347` | `FT_U9_MUENCHNER_FREIHEIT_D0`, `FT_U9_MUENCHNER_FREIHEIT_D1` | 48.161962, 11.586497 | Dedicated U9 platforms under the established interchange parent. |
| U9 Elisabethplatz | `106261` | `FT_U9_ELISABETHPLATZ_D0`, `FT_U9_ELISABETHPLATZ_D1` | 48.157660, 11.575017 | Existing surface-stop parent used as an underground-station proxy. |
| U9 Pinakotheken | `106284` | `FT_U9_PINAKOTHEKEN_D0`, `FT_U9_PINAKOTHEKEN_D1` | 48.148970, 11.571900 | Existing surface-stop parent used as an underground-station proxy. |
| U9 Hauptbahnhof | `106087` | `FT_U9_HAUPTBAHNHOF_D0`, `FT_U9_HAUPTBAHNHOF_D1` | 48.140027, 11.561066 | Dedicated U9 platforms under the established multimodal parent. |
| U9 Esperantoplatz | `FT_U9_ESPERANTOPLATZ` | `FT_U9_ESPERANTOPLATZ_D0`, `FT_U9_ESPERANTOPLATZ_D1` | 48.130190, 11.553590 | New parent at the approved square-centroid proxy; not an official station coordinate. |
| U9 Impler-/Poccistraße | `FT_U9_IMPLER_POCCI` | `FT_U9_IMPLER_POCCI_D0`, `FT_U9_IMPLER_POCCI_D1` | 48.122829, 11.549348 | New parent at the rounded midpoint of baseline parents Implerstraße `108587` and Poccistraße `106206`; not an official station coordinate. |
| U4 Cosimapark | `107660` | `FT_U4_COSIMAPARK_D0`, `FT_U4_COSIMAPARK_D1` | 48.153603, 11.629401 | Existing Cosimabad parent used as the Cosimapark proxy. |
| U4 Englschalking | `157857` | `FT_U4_ENGLSCHALKING_D0`, `FT_U4_ENGLSCHALKING_D1` | 48.156551, 11.648400 | Dedicated U4 platforms under the established S-Bahn interchange parent. |

The platform choices are informed by directional use in the baseline feed. At Münchner Freiheit, the U6 uses platform `107353` in both directions and the U3 generally uses `107356` southbound and `107354` northbound. At Hauptbahnhof, existing U1/U2/U4/U5 services use their own platform IDs. At Englschalking, S8 services use platform `157858`. These existing platforms are retained, but they are not falsely presented as future U9 or U4 infrastructure; dedicated `FT_` platforms are added under the same parent station instead.

### Impler-/Poccistraße interchange

The midpoint station is connected bidirectionally to the baseline subway platforms actually used at the two surrounding stations:

- Poccistraße: `106211`, `106212`;
- Implerstraße: `108590`, `108591`, `108592`.

Both new U9 directional platforms are connected to all five existing platforms in both transfer directions. This produces 20 directed `transfers.txt` relations with `transfer_type=2` and `min_transfer_time=300`. The 300-second value is a walking-time scenario assumption for the approximately 300-metre proxy separation. It is not an operationally validated interchange time.

## Timetable and running-time derivation

The Nordring policy source fixes 40 train pairs and a 30-minute all-day interval but does not define exact first and last departures. The baseline S1, S2 and S8 comparisons begin at approximately 04:10 and continue beyond midnight. Decision D12 regularizes both Nordring lines to 40 departures per direction at exact 30-minute intervals from 04:30 to 24:00. This is internally consistent because 39 intervals of 30 minutes span 19.5 hours. It is a scenario timetable designed for transparent comparison, not an operationally validated railway timetable.

FT-U9 uses the U6 because it serves both Münchner Freiheit and Harras and therefore supplies directional endpoint departures. The U3 is retained as a plausibility comparison. The build produces 538 U9 trips: 274 in GTFS direction 0 and 264 in direction 1, reflecting the observed U6 template rather than imposing an artificial symmetrical count.

FT-U4 preserves the departure pattern of all 398 baseline U4 trips that begin or end at Arabellapark. Only the new section is timed. New segment running times are calculated deterministically from the approved coordinate proxies and the median moving speed of the comparison services. Dwell time is taken from the corresponding baseline comparison; a fallback value is used only if no comparison statistic is available.

The running-time calculations are modelling approximations. They do not represent engineering forecasts and omit alignment curvature, acceleration constraints, junction conflicts, infrastructure-specific speed limits and operational recovery margins.

## Build result and bounded changes

The completed Fast Track feed has the following relationship to the unchanged cleaned baseline:

| GTFS object | Baseline | Fast Track | Change |
|---|---:|---:|---:|
| Routes | 1,733 | 1,736 | +3 |
| Stops and station/platform rows | 54,627 | 54,651 | +24 |
| Trips | 70,620 | 71,318 | +698 |
| Stop-time rows | 1,341,494 | 1,347,734 | +6,240 |
| Transfer relations | 95,876 | 95,896 | +20 |
| Shape points | 1,441,848 | 1,441,848 | unchanged |

The 698 new trips comprise 538 U9 trips, 80 FT-NR-A trips and 80 FT-NR-B trips. Each Nordring route contains exactly 40 departures in each direction. No existing route or stop row is changed or removed. Of the 70,620 baseline trip rows, only the 398 extended U4 trips are modified, and only their optional `shape_id` is cleared because the old geometry no longer describes the extended trip. Their stop-time groups are extended; 70,222 other baseline stop-time groups remain byte-identical. Agency, calendar and shape tables remain byte-identical to the baseline.

New and extended trips have an empty optional `shape_id`. No unverified alignment or shape is invented. A targeted automated test and the complete feed conversion both demonstrate that the installed GTFS-to-MATSim converter accepts these trips and creates routes from their ordered stops.

## Integrity and MATSim conversion checks

Before publication, the builder verifies:

- unique agency, service, route, trip and stop identifiers;
- closed route-to-agency, trip-to-route/service/shape, stop-to-parent, stop-time-to-trip/stop and transfer references;
- valid WGS84 coordinates, GTFS times, monotonic arrival/departure order and strictly increasing stop sequences;
- at least two stop times for every trip;
- standard route types for every added Fast Track route;
- unique, valid transfer relations and the exact presence of all 20 approved Impler-/Poccistraße relations;
- the root-level ZIP entry set and row counts after reopening the completed archive.

The finished ZIP was then loaded by the independent GTFS library used by MATSim, which reported zero GTFS loading errors, and converted in memory for 13 February 2026. WGS84 coordinates were transformed to `EPSG:31468`, GTFS stops were not merged, and minimal transfer-time import was explicitly enabled. The conversion produced 54,651 MATSim transit stops, 1,736 transit lines, 14,309 transit routes and 71,318 departures. It retained 95,896 minimal transfer-time relations, including all 20 new Impler-/Poccistraße relations at exactly 300 seconds. No MATSim simulation was run.

The convenience method `RunGTFS2MATSim.convertGTFSandAddToScenario` used by older project converters does not enable GTFS minimal transfer times. A future Fast Track MATSim input converter must therefore use `GtfsConverter.Builder.setIncludeMinimalTransferTimes(true)` explicitly; otherwise the GTFS transfer rows remain valid but are not copied into the MATSim schedule.

## Reproduction

From the project root, run the preflight with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode analyze
```

Review `generated/fast_track_preflight/preflight_report.md` and confirm that the critical blocker count is zero. Then build and validate the feed with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode build
```

The resulting archive is `original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip`. The permanent build summary is `docs/gtfs2040/gtfs2037_fast_track_build_report.md`.

## Implications and remaining limitations

The method isolates the scenario treatment to the approved U9 main corridor, U4 extension and two Nordring services. It does not add U29, Fideliopark, Pellegrinistraße, Gronsdorf, an Olympiastadion branch or an independent four-track-expansion timetable effect.

The main substantive limitation is spatial uncertainty. Future station proxies affect access distance, transfer paths, travel times and network routing, so later BAU/Fast Track results must be interpreted as conditional on these approved scenario locations. The regularized Nordring timetable, modelled segment times and 300-second Impler-/Poccistraße transfer are likewise analytical assumptions rather than operational forecasts. Finally, the generated trips have no authoritative track geometry; the MATSim pseudonetwork will connect ordered stops technically but does not represent a validated infrastructure alignment.
