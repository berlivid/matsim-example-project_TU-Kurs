# MVV GTFS input

This directory contains the raw public-transport timetable used to prepare the
current Munich MATSim transit reference.

## Source and license

- Provider: Münchner Verkehrs- und Tarifverbund GmbH (MVV)
- Dataset: `Soll-Fahrplandaten MVV-Gesamtnetz (GTFS)`
- Source portal:
  <https://opendata.muenchen.de/de/dataset/soll-fahrplandaten-mvv-gesamtnetz-gtfs>
- Portal release label at download: `06/2026`
- GTFS `feed_info.txt` version: `20260705`
- Accessed: 2026-07-31
- License: Creative Commons Attribution 4.0 International (`CC BY 4.0`)

Required attribution:

> Münchner Verkehrs- und Tarifverbund GmbH (MVV), MVV
> Gesamt-Soll-Fahrplandaten (GTFS), portal release 06/2026, internal feed
> version 20260705, accessed 31 July 2026, CC BY 4.0.

The dataset represents the complete MVV network, not only the MVV regional-bus
feed. According to the provider, the dataset is generally updated every four to
eight weeks and usually covers approximately six months.

## Archived raw file

- File: `gesamt_gtfs.zip`
- File size: 19,247,667 bytes
- SHA-256:
  `EFB0B5344BA81E57810C2B2329B43122BF15C7B184850B40C1A782781B1C7611`
- Feed validity: 2026-06-15 through 2026-09-30

The ZIP file is archived unchanged and is read directly by the converter. It
does not need to be extracted.

## Representative service day

The MATSim transit schedule uses Wednesday, 2026-09-16. This date:

- lies inside the feed validity period;
- is a regular Wednesday;
- is after the Bavarian summer holidays;
- is not a public holiday;
- is before the start of Oktoberfest; and
- has no service explicitly named `Special` added in `calendar_dates.txt`.

The converter identified 915 active services for this date.

## GTFS observations

- The feed contains 28,243 stop records, 911 route records, 126,096 trip
  records, and 2,389,497 stop-time records, excluding the header lines.
- Standard route types for tram, subway, rail, and bus are present.
- `shapes.txt` is present but contains no data rows. This does not prevent the
  chosen pseudonetwork conversion, but no GTFS route geometry is available.
- Fare tables, `transfers.txt`, and `frequencies.txt` are absent. These files
  are optional and the conversion completed without them.

## MATSim processing

The raw feed is converted by:

`src/main/java/org/matsim/project/prepare/CreateCurrentMvvTransit.java`

Processing assumptions:

- selected day: 2026-09-16;
- coordinate transformation: WGS84 (`EPSG:4326`) to DHDN / 3-degree
  Gauss-Krueger zone 4 (`EPSG:31468`);
- base road network:
  `scenarios/munich_base_2023/studyNetworkDense.xml`;
- early and late departures are copied to obtain an overnight schedule;
- extended GTFS route types are enabled;
- stops are not merged; and
- MATSim creates a PT pseudonetwork and standard transit vehicles.

Generated files and their technical validation are documented in:

`scenarios/munich_base_2023/input_transit/README.md`

## Temporal limitation

The feed represents the available 2026 timetable. It must not be described as
a historical 2023 timetable or as a 2040 timetable. It is a current technical
reference and a possible starting point for explicitly documented 2040
scenario modifications.
