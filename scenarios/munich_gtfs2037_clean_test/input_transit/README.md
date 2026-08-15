# Filtered GTFS 2037 MATSim test inputs

This directory contains isolated test outputs derived from the cleaned Munich-region GTFS 2037 feed. It is not a BAU or Fast Track scenario and must not be used as evidence that any missing infrastructure measure has already been implemented.

The large generated XML files are intentionally ignored by Git. Recreate them from the project root after building the cleaned GTFS ZIP:

```powershell
$env:MAVEN_OPTS='-Xmx12g'
.\mvnw.cmd -q exec:java "-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit"
```

The converter uses service date `2026-02-13`, transforms WGS84 coordinates to `EPSG:31468`, adds a PT pseudonetwork to `scenarios/munich_base_2023/studyNetworkDense.xml`, and creates transit vehicles. It writes:

- `studyNetworkDense-with-gtfs2037-munich-pt.xml.gz`;
- `transitSchedule-gtfs2037-munich.xml.gz`; and
- `transitVehicles-gtfs2037-munich.xml.gz`.

The converter re-reads all three files before reporting success. The verified result has 80,751 transit stops, 1,733 lines, 14,303 transit routes, 70,620 departures, 70,620 vehicles, 249,190 total network nodes and 561,977 total network links.

The methodological basis, limitations and cleaned-feed reproduction command are documented in `docs/gtfs2040/gtfs2037_munich_filter_method.md`.
