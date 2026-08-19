# Fast Track GTFS 2037 build report

The feed was created from the unchanged cleaned Munich GTFS baseline. All critical entries in the versioned service and stop specifications were approved before build mode was permitted.

- Baseline SHA-256: `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A`
- Output: `original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip`
- SHA-256: `A601EF17EE9FDBB3355F8F0B890FE831A8D93E8992F8B4235BBCA98F86714F86`
- New routes: 3
- New trips: 698
  - FT_U9: 538
  - FT_NR_A: 80
  - FT_NR_B: 80
- Extended U4 trips: 398
- New stop rows: 24
- New directed transfer relations: 20
- Validated rows: {agency.txt=18, calendar.txt=1, routes.txt=1736, trips.txt=71318, stops.txt=54651, shapes.txt=1441848, stop_times.txt=1347734, transfers.txt=95896}

## MATSim conversion verification

The completed ZIP was converted in memory for the technical service date 2026-02-13 with WGS84-to-EPSG:31468 transformation, unmerged GTFS stops and minimal transfer-time import enabled. No MATSim simulation was run.

- Transit stops: 54651
- Transit lines: 1736
- Transit routes: 14309
- Departures: 71318
- Minimal transfer-time relations: 95896
- Explicit Impler-/Poccistraße relations verified: 20

New and extended trips have an empty optional `shape_id`; no shape was invented. Existing S8 rows were copied without modification. All new station coordinates are approved scenario proxies rather than official future platform locations. The 300-second Impler-/Poccistraße transfer time and the regularized Nordring timetable are scenario assumptions, not operationally validated values.
