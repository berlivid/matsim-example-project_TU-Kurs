# Audit des unveränderten GTFS-2037-Rohstands

Stand: 14. August 2026

## Ergebnis und Entscheidung

Die neun Rohdateien lassen sich als UTF-8-CSV unverändert und vollständig
streaming-basiert einlesen. Die Primär- und Fremdschlüssel sind konsistent,
alle Trips besitzen Stop-Times und Shapes, und es wurden keine ungültigen
Koordinaten, Uhrzeiten oder nicht monotonen Stop-Sequenzen gefunden.

Eine ungefilterte MATSim-Konvertierung wurde bewusst **nicht ausgeführt**.
Der Feed ist deutschlandweit, alle 202.643 Trips sind am einzigen Servicetag
aktiv, und die lokale JVM stellt standardmäßig nur rund 3,84 GiB Heap bereit.
Der bereits getestete aktuelle MVV-Konverter benötigte dagegen 8 GiB für nur
44.536 Fahrplanabfahrten, 39.799 Transit-Stops und 857 Linien. Der neue Feed
enthält 202.643 aktive Trips, 183.259 Stop-Datensätze und 6.175 Routen. Eine
vollständige Konvertierung ist damit unter der vorhandenen Speicherkonfiguration
unverhältnismäßig riskant und für das Münchner Szenario fachlich nicht
zielführend.

Es wurde nicht stillschweigend gefiltert. Stattdessen wurden das unveränderte
Arbeits-ZIP und ein separat kompilierbarer Konverter vorbereitet. Vor dessen
Ausführung wird ein expliziter, dokumentierter München-Filter empfohlen.

## Prüfrahmen

- Rohdaten nur aus `original-input-data/mvv_gtfs_2037/raw` gelesen;
- keine Rohdatei geändert oder korrigiert;
- bestehende BAU-, Fast-Track- und Transitdateien nicht angefasst;
- kein MATSim-Simulationslauf gestartet;
- große Tabellen zeilenweise verarbeitet; im Speicher lagen nur ID-Mengen,
  Zähler und die für Schlüsselprüfungen notwendigen Zuordnungen;
- das erzeugte ZIP wurde je Eintrag per SHA-256 gegen `raw` verifiziert.

## Inventar

| Datei | Datenzeilen | Inhalt |
|---|---:|---|
| `agency.txt` | 42 | Agencies |
| `calendar.txt` | 1 | Servicekalender |
| `calendar_dates.txt` | 0 | keine Ausnahmen |
| `routes.txt` | 6.175 | Routen |
| `trips.txt` | 202.643 | Trips |
| `stop_times.txt` | 3.570.103 | Stop-Times |
| `stops.txt` | 183.259 | Stations- und Plattformdatensätze |
| `shapes.txt` | 3.460.257 | Shape-Punkte für 49.684 Shape-IDs |
| `transfers.txt` | 299.395 | Transfers |

Dateigrößen und SHA-256-Prüfsummen stehen in
`original-input-data/mvv_gtfs_2037/README.md`.

## Servicetag

`calendar.txt` enthält genau eine Zeile:

```text
service_id=1
monday ... sunday=1
start_date=20260213
end_date=20260213
```

`calendar_dates.txt` enthält nur die Kopfzeile. Alle 202.643 Trips verwenden
`service_id=1`; andere Service-IDs kommen nicht vor. Für den technischen
Import ist daher der 13.02.2026 zu verwenden. Das Datum definiert nicht das
Szenariojahr.

## Struktur- und Referenzprüfung

Alle folgenden Fehlerzähler sind null:

- doppelte `agency_id`, `route_id`, `trip_id`, `stop_id` oder `service_id`;
- Route mit fehlender Agency;
- Trip mit fehlender Route, Service-ID oder Shape-ID;
- Stop-Time mit fehlendem Trip oder Stop;
- Stop mit fehlender `parent_station`;
- Transfer mit fehlendem From-/To-Stop;
- Trip ohne Stop-Time oder ohne Shape;
- nicht ansteigende `stop_sequence` oder `shape_pt_sequence`;
- falsche Spaltenzahl gegenüber der jeweiligen Kopfzeile;
- ungültige oder fehlende Stop-/Shape-Koordinaten;
- syntaktisch ungültige Ankunfts-/Abfahrtszeit, Ankunft nach Abfahrt oder
  rückwärts laufende Zeit innerhalb eines Trips.

Weitere technische Beobachtungen:

- maximale GTFS-Stunde: 43; Werte über 24 Uhr sind im GTFS zulässig;
- `direction_id`: 107.524 Trips mit 0, 95.119 Trips mit 1;
- ausschließlich gültige `pickup_type`-/`drop_off_type`-Werte 0 und 1;
- alle 299.395 Transfers sind Typ 2 und besitzen `min_transfer_time`;
- `stops.txt`: 63.574 Stationsdatensätze (`location_type=1`) und 119.685
  Plattform-/Haltdatensätze (`location_type=0`);
- alle 6.175 `route_short_name` sind leer, aber jedes `route_long_name` ist
  befüllt; damit ist mindestens eines der beiden GTFS-Namensfelder vorhanden.

## Fachliche Datenprobleme

### Route Types

`route_type=0` kommt 6.175-mal vor und ist der einzige vorhandene Wert. Nach
GTFS bedeutet 0 **Tram/Straßenbahn**. Damit würden U-Bahn, S-Bahn, Eisenbahn,
Bus und Fähre im Rohimport fälschlich als Tram behandelt. In diesem ersten
Schritt wurde der Wert nicht korrigiert.

Für den nächsten Schritt bieten die bestehenden ID-Suffixe und Namen eine
belastbare Ausgangsbasis:

- Tram: `*_0` und `*_MUC_Tram neu Prognose` -> 0;
- U-Bahn: `*_1` und `MUC_U*_neu Prognose` -> 1;
- S-Bahn: `*_109` und `S*_Prognose_*` -> Extended-GTFS 109
  (alternativ Standard-GTFS 2, wenn bewusst ohne Extended Types gearbeitet
  wird);
- Bus: `*_3` -> 3;
- weitere nationale Verkehrsmittel müssen separat und nachvollziehbar
  klassifiziert werden.

### Agency-Metadaten

Die Verweise sind formal geschlossen, die Inhalte aber unzureichend:

- 5.849 von 6.175 Routen referenzieren `agency_id=unknown`;
- diese Agency heißt `unknown`, hat `agency_timezone=unknown` und
  `agency_url=http://`;
- insgesamt 38 von 42 Agency-URLs sind lediglich `http://`.

Das sind Metadaten-/GTFS-Qualitätsprobleme. Sie wurden nicht repariert und
können einen streng validierenden Import behindern.

## München-Markierung und Prognoselinien

`München=1` ist bei genau 68 Routen gesetzt. Diese Menge besteht aus:

- 8 vorhandenen S-Bahn-Routen `162455_109` bis `162462_109`;
- 7 vorhandenen U-Bahn-Routen `2271995_1` bis `2272001_1`;
- 19 vorhandenen Tram-Routen mit Suffix `_0`;
- 16 Tram-Prognoserouten:
  `11`, `12`, `13`, `14`, `16`, `17`, `18`, `19`, `20`, `21`, `22`,
  `23`, `24`, `25`, `27`, `28`, jeweils als
  `<Linie>_MUC_Tram neu Prognose`;
- 6 U-Bahn-Prognoserouten `MUC_U1_neu Prognose` bis
  `MUC_U6_neu Prognose`;
- 12 S-Bahn-Prognoserouten:
  `S18X`, `S1`, `S20`, `S21X`, `S23X`, `S24X`, `S2`, `S3`, `S4`, `S6`,
  `S7` und `S8` (jeweils mit dem vollständigen Suffix `_Prognose_*`).

Das Feld ist kein vollständiger München-Filter: mehrere eindeutig lokale
Buslinien mit Suffix `_3` sind nicht markiert, bedienen aber die untersuchten
Halte.

## Bedienung der angefragten Halte

Stationsdatensätze (`location_type=1`) werden erwartungsgemäß nicht direkt in
`stop_times.txt` verwendet. Entscheidend sind ihre Kind-/Plattform-IDs.
„Calls“ zählt Stop-Time-Zeilen über die angegebenen Kind-IDs.

| Suchname | Stations-ID | bediente Kind-IDs | Calls | Befund |
|---|---|---|---:|---|
| Poccistraße | `106206` | `106207`–`106212` | 1.980 | bedient |
| Berduxstraße | `162054` | `162055` | 256 | bedient, aber nur durch Busrouten |
| Lassallestraße | `106958` | `106959`–`106962` | 552 | bedient, aber nur durch Busrouten |
| BMW-FIZ | `107777` | `107778`, `107779` | 89 | bedient, Busroute `2272086_3` |
| Euro-Industriepark West | `107187` | `107188`, `107189` | 229 | bedient |
| Euro-Industriepark Nord | `107298` | `107299`, `107300` | 255 | bedient |
| Implerstraße | `108587` | `108588`–`108592` | 1.907 | bedient, unter anderem U3/U6 |
| Esperantoplatz | – | – | 0 | Name/Stop fehlt vollständig |
| Hauptbahnhof (München) | `106087` | `106088`–`106095` | 6.423 | bedient |
| Pinakotheken | `106284` | `106285`–`106288` | 1.426 | bedient |
| Elisabethplatz | `106261` | `106262`, `106263` | 1.321 | bedient |
| Münchner Freiheit | `107347` | `107348`–`107356` | 3.292 | bedient |
| Dietlindenstraße | `107370` | `107371`–`107374` | 984 | bedient, unter anderem U6 |
| Arabellapark | `107688` | `107689`–`107695` | 1.416 | bedient, U4-Endhalt |
| Arabellapark/Klinikum Bogenhausen | `107668` | `107669`–`107672` | 1.563 | bedient |
| Arabellapark Nord | `107696` | `107697`, `107698` | 515 | bedient |
| Englschalking | `157857` | `157858` | 361 | bedient, S8/S23X |
| Englschalkinger Straße | `107515` | `107516`–`107519` | 505 | bedient |
| Messestadt West | `108848` | `108849`–`108854` | 1.595 | bedient, unter anderem U2; nicht U4 |

Damit ist der bekannte Verdacht nur teilweise bestätigt: Manche zukünftigen
Stationen stehen lediglich als Stationsobjekt ohne direkte Stop-Time, ihre
Kind-IDs werden aber sehr wohl bedient. Esperantoplatz fehlt; Berduxstraße und
BMW-FIZ sind bereits in Fahrten eingebunden, jedoch nicht als die geplanten
Schienenmaßnahmen.

## Repräsentative Haltestellenfolgen

Jeweils ein längster repräsentativer Trip pro Richtung wurde verwendet.

### U4 — `MUC_U4_neu Prognose`

- Trip `195105`, Richtung 0, Headsign Arabellapark:
  Laimer Platz > Friedenheimer Straße > Westendstraße > Heimeranplatz >
  Schwanthalerhöhe > Theresienwiese > Hauptbahnhof > Karlsplatz (Stachus) >
  Odeonsplatz > Lehel > Max-Weber-Platz > Prinzregentenplatz >
  Böhmerwaldplatz > Richard-Strauss-Straße > Arabellapark.
- Trip `195051`, Richtung 1: dieselbe Folge in Gegenrichtung bis Laimer Platz.

Die U4 endet im Rohstand am Arabellapark; Englschalking und Messestadt West
sind nicht Teil dieser Route.

### U5 — `MUC_U5_neu Prognose`

- Trip `195509`, Richtung 1, Headsign Freiham Zentrum:
  Neuperlach Süd > Therese-Giehse-Allee > Neuperlach Zentrum > Quiddestraße >
  Michaelibad > Innsbrucker Ring > Ostbahnhof > Max-Weber-Platz > Lehel >
  Odeonsplatz > Karlsplatz (Stachus) > Hauptbahnhof > Theresienwiese >
  Schwanthalerhöhe > Heimeranplatz > Westendstraße > Friedenheimer Straße >
  Laimer Platz > Willibaldstraße > Knie > Pasing > Westkreuz >
  Radolfzeller Straße > Riesenburgstraße > Freiham Zentrum.
- Trip `195446`, Richtung 0: dieselbe Folge in Gegenrichtung bis
  Neuperlach Süd.

### U6 — `MUC_U6_neu Prognose`

- Trip `195985`, Richtung 0, Headsign Martinsried:
  Garching Forschungszentrum > Garching > Garching-Hochbrück > Fröttmaning >
  Kieferngarten > Freimann > Studentenstadt > Alte Heide > Nordfriedhof >
  Dietlindenstraße > Münchner Freiheit > Giselastraße > Universität >
  Odeonsplatz > Marienplatz > Sendlinger Tor > Goetheplatz > Poccistraße >
  Implerstraße > Harras > Partnachplatz > Westpark > Holzapfelkreuth >
  Haderner Stern > Großhadern > Klinikum Großhadern > Martinsried.
- Trip `196219`, Richtung 1: dieselbe Folge in Gegenrichtung bis Garching
  Forschungszentrum.

### S1 — `S1_Prognose_Ebersberg/Leuchtenbergring-Schwaigerlohe/Freising`

- Trip `200818`, Richtung 0:
  Schwaigerloh Bahnhof > Flughafen München > Besucherpark > Neufahrn > Eching
  > Lohhof > Unterschleißheim > Oberschleißheim > Feldmoching > Fasanerie >
  Moosach > Laim > München Hbf tief 2. Stammstrecke > Marienplatz Marienhof
  2. Stammstrecke > Ostbahnhof 2. Stammstrecke > Leuchtenbergring > Trudering
  > Haar > Zorneding > Eglharting > Kirchseeon > Grafing Bahnhof > Grafing
  Stadt > Ebersberg > Steinhöring > Tulling > Forsting > Edling > Wasserburg.
- Trip `200858`, Richtung 1: dieselbe Folge in Gegenrichtung bis
  Schwaigerloh Bahnhof.

### S2 — `S2_Prognose_Petershausen/Altomünster-Holzkirchen`

- Trip `201219`, Richtung 0:
  Altomünster > Kleinberghofen > Erdweg > Arnbach > Markt Indersdorf >
  Niederroth > Schwabhausen > Bachern > Dachau Stadt > Dachau Bahnhof >
  Karlsfeld > Allach > Untermenzing > Obermenzing > Laim > Hirschgarten >
  Donnersbergerbrücke > Hackerbrücke > Hauptbahnhof > Karlsplatz (Stachus) >
  Marienplatz > Isartor > Rosenheimer Platz > Ostbahnhof > St.-Martin-Straße >
  Giesing > Fasangarten > Fasanenpark > Unterhaching > Taufkirchen > Furth >
  Deisenhofen > Sauerlach > Otterfing > Holzkirchen.
- Trip `201253`, Richtung 1: dieselbe Folge in Gegenrichtung bis Altomünster.

### S8 — `S8_Prognose_Herrsching-Schwaigerlohe`

- Trip `201919`, Richtung 1:
  Schwaigerloh Bahnhof > Flughafen München > Besucherpark > Hallbergmoos >
  Ismaning > Unterföhring > Johanneskirchen > Englschalking > Daglfing >
  Leuchtenbergring > Ostbahnhof > Rosenheimer Platz > Isartor > Marienplatz >
  Karlsplatz (Stachus) > Hauptbahnhof > Hackerbrücke > Donnersbergerbrücke >
  Hirschgarten > Laim > Pasing > Westkreuz > Neuaubing > Freiham > Harthaus
  > Germering-Unterpfaffenhofen > Geisenbrunn > Gilching-Argelsried >
  Neugilching > Weßling > Steinebach > Seefeld-Hechendorf > Herrsching.
- Trip `201839`, Richtung 0: dieselbe Folge in Gegenrichtung bis
  Schwaigerloh Bahnhof.

## IDs der späteren Maßnahmen

Die Maßnahmenliste kennzeichnet U9, U4 Messe, S-Bahn-Nordring, Berduxstraße
und den Regionalzughalt Poccistraße jeweils als **nicht im GTFS2037
enthalten**. Das stimmt mit den Routen-/Trip-Prüfungen überein. Vorhandene
gleichnamige Stops oder Busbedienungen dürfen nicht mit der Maßnahme
gleichgesetzt werden.

### U9

- Keine Route und kein Trip mit Linienkennung U9 vorhanden.
- Relevante bestehende U-Bahn-Anker:
  `MUC_U6_neu Prognose` (zum Beispiel Trip `195985`) und
  `MUC_U3_neu Prognose` (zum Beispiel Trip `194367`).
- Korridor-Stop-IDs: Implerstraße `108587`/`108588`–`108592`, Poccistraße
  `106206`/`106207`–`106212`, Münchner Freiheit
  `107347`/`107348`–`107356`, Dietlindenstraße
  `107370`/`107371`–`107374`.
- BMW-FIZ `107777`/`107778`–`107779` wird nur von Route `2272086_3`
  bedient (Beispieltrip `141072`).
- Pinakotheken und Elisabethplatz sind vorhanden und bedient, aber nicht
  durch eine U9. Esperantoplatz fehlt vollständig.

### U4 bis Messe

- Bestehende Prognoseroute: `MUC_U4_neu Prognose`, Beispieltrips `195105`
  und `195051`; Endhalt Arabellapark.
- Messestadt West: `108848`/`108849`–`108854`.
- Dort vorhandene U-Bahn-Route: `MUC_U2_neu Prognose`, Beispieltrip `193946`.
- Es existiert kein U4-Trip zwischen Arabellapark, Englschalking und
  Messestadt West.

### S-Bahn-Nordring und Berduxstraße

- Keine Route/kein Trip mit Kennung Nordring vorhanden.
- Berduxstraße: `162054`/`162055`; bedient durch die Busrouten
  `2272075_3` (Beispieltrip `139561`) und `2272094_3`
  (Beispieltrip `142209`), nicht durch eine S-Bahn.
- Lassallestraße: `106958`/`106959`–`106962`; Busrouten `2272030_3`,
  `2272040_3`, `2272077_3`.
- Englschalking: `157857`/`157858`; vorhandene S-Bahn-Anker sind
  `S8_Prognose_Herrsching-Schwaigerlohe` (Trip `201838`) und
  `S23X_Prognose_Augsburg-Flughafen` (Trip `200928`).

### Neuer Regionalzughalt Poccistraße

- Die Stations-/Plattform-IDs `106206`/`106207`–`106212` existieren.
- Sie werden unter anderem von `MUC_U3_neu Prognose` und
  `MUC_U6_neu Prognose` sowie mehreren Busrouten bedient.
- Ein eigener Regionalzughalt beziehungsweise eine angepasste Regionalroute
  für die Maßnahme ist nicht enthalten.

## Vorbereiteter MATSim-Konverter

`src/main/java/org/matsim/project/prepare/CreateGtfs2037RawTransit.java`
verwendet ausschließlich:

- GTFS-ZIP:
  `original-input-data/mvv_gtfs_2037/generated/gtfs2037_raw.zip`;
- Basisnetz: `scenarios/munich_base_2023/studyNetworkDense.xml`;
- Servicetag: 13.02.2026;
- Transformation EPSG:4326 nach EPSG:31468;
- PT-Pseudonetz und Standard-Transitfahrzeuge;
- Ausgabe nach `scenarios/munich_gtfs2037_raw_test/input_transit`.

Das Basisnetz wird nur gelesen. Der Konverter kopiert keine frühen/späten
Abfahrten, damit der technische Roh-Servicetag nicht künstlich erweitert
wird. BAU-/Fast-Track-Configs bleiben unverändert.

Build-Prüfung am 14.08.2026:

```text
.\mvnw.cmd -DskipTests compile
BUILD SUCCESS
Java release 21, MATSim 2025.0
```

Maven meldete lediglich die bereits im Projekt vorhandene Warnung, dass
`${parent.version}` künftig durch `${project.parent.version}` ersetzt werden
sollte. Diese unabhängige POM-Stelle wurde nicht verändert.

Da die vollständige Konvertierung nach dem Speicher-Audit gestoppt wurde,
liegen noch keine belastbaren MATSim-Zahlen für Linien, Stops, Transit-Routen,
Abfahrten oder Fahrzeuge vor. Solche Zahlen werden erst nach der expliziten
Filterentscheidung erhoben.

## Empfohlener nächster Implementierungsschritt

Als Nächstes sollte ein reproduzierbarer **GTFS-München-Filter** implementiert
werden, der aus dem unveränderten `raw` einen neuen abgeleiteten Feed erzeugt.
Er soll nicht lediglich `München=1` verwenden, weil dieses Feld lokale
Buslinien auslässt. Empfohlen ist:

1. fachlich freigegebene Routenmenge aus den 68 markierten Schienen-/U-Bahn-/
   Tram-Routen plus einer expliziten räumlichen/Agency-Regel für lokale Busse;
2. referenzielle Closure über Trips, Stop-Times, bediente Stops samt
   `parent_station`, Shapes, Agencies, Kalender und interne Transfers;
3. Korrektur der `route_type`-Werte in der abgeleiteten Kopie, nie in `raw`;
4. erneuter Audit des Filterfeeds;
5. MATSim-Testkonvertierung mit großzügig gesetztem Heap und anschließendem
   Re-Import der drei erzeugten MATSim-Dateien.

Erst danach sollten getrennte bereinigte BAU- und Fast-Track-GTFS-Dateien
erzeugt und die Maßnahmen U9, U4 Messe, S-Bahn-Nordring, Berduxstraße und
Poccistraße explizit modelliert werden.
