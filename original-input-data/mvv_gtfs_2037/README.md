# GTFS 2037 raw input

Stand der Dokumentation: 14. August 2026.

## Herkunft und Ablage

Die neun GTFS-Textdateien wurden für diese Arbeit unverändert in
`original-input-data/mvv_gtfs_2037/raw` kopiert. Sie sind damit vom aktuellen
MVV-GTFS unter `original-input-data/mvv_gtfs` und von allen Szenarioeingaben
und -ausgaben getrennt.


Die genaue externe Quelle, das Exportwerkzeug, das Exportdatum und der
ursprüngliche Speicherpfad vor dem Kopieren wurden nicht mit den Dateien
übergeben und sind aus ihren Metadaten nicht rekonstruierbar. Diese Angaben
müssen noch durch den Datenbereitsteller beziehungsweise den Bearbeiter
ergänzt werden. Bis dahin darf der Datensatz nur als bereitgestellter
Prognose-/Planungsstand „GTFS 2037“ bezeichnet werden, nicht als offizieller
MVV-Fahrplan.

Die Datei `Infrastructure_measures.xlsx` wurde von David Berling erstellt und
liegt bewusst eine Ebene oberhalb von `raw`. Sie gehört nicht zum GTFS und
wurde beim Rohdaten-Audit nicht verändert.

## Format und technischer Servicetag

- CSV mit Komma als Trennzeichen und UTF-8-BOM in allen neun Dateien;
- zahlreiche zusätzliche, nicht zum GTFS-Standard gehörende Analysespalten;
- `calendar.txt`: ausschließlich `service_id=1`, täglich aktiv, Start- und
  Enddatum 13.02.2026;
- `calendar_dates.txt`: nur Kopfzeile, keine Ausnahmen;
- alle 202.643 Trips verwenden `service_id=1`.

Der 13.02.2026 ist ein technischer Servicetag für die Konvertierung und keine
Aussage über das Szenariojahr 2037 oder 2040.

## Dateien und Prüfsummen

Zeilenzahlen verstehen sich ohne Kopfzeile. Prüfsummen sind SHA-256.

| Datei | Bytes | Datenzeilen | SHA-256 |
|---|---:|---:|---|
| `agency.txt` | 1.696 | 42 | `D0C5A9975C44EC8F7627A371F75892A87F9C3AC89EA47C8B6EB3B289AEDC3436` |
| `calendar.txt` | 127 | 1 | `67C5106670978CDE7E56AFE213A104A774C7A2BC6BB7D23AA9619F7FD8B74660` |
| `calendar_dates.txt` | 35 | 0 | `5AEEF05EDF27E5AE331D6E3014CE4C3CFE9346C1A31EABB1473E42904B50D896` |
| `routes.txt` | 564.404 | 6.175 | `55B4A901E34D96109A7D1447267AA28E37952FFF0B8CCE97B3ED621F490F0828` |
| `trips.txt` | 13.571.697 | 202.643 | `AC6F5517DD04E8623EFADC159E9A85E46EF10E7A790A75122CDFCBE245AA5A5A` |
| `stop_times.txt` | 223.792.573 | 3.570.103 | `6487553B967AFDBA45F78B3D2CD188433AA624633829AFBCF10D6E0C7EBB8FDF` |
| `stops.txt` | 27.200.702 | 183.259 | `FEFDCEAF0BC7A81DE65A83F80147E46575C354ABB8899A8ECB073A0C2B22E922` |
| `shapes.txt` | 103.905.757 | 3.460.257 | `E765F6E9DB4EF101BA9D78E8F8645D873F91A955B79F8006CF3412B667C32146` |
| `transfers.txt` | 5.877.519 | 299.395 | `BAA04E338567F719CAA1DC1DB501E8E8D0979BBE05B59AC6EE1423F5DDF6BCCA` |

Gesamtgröße der GTFS-Textdateien: 374.914.510 Bytes (rund 357,5 MiB).

Weitere Datei:

| Datei | Bytes | SHA-256 |
|---|---:|---|
| `Infrastructure_measures.xlsx` | 17.505 | `EEE883B4F7EDFDF06130E7F3A8E8BF24D743E055431DCE6CDC690A973BE585EE` |

## Unverändertes Arbeits-ZIP

Für den MATSim-Konverter wurde
`generated/gtfs2037_raw.zip` erzeugt:

- Größe: 61.668.973 Bytes;
- SHA-256: `571AFF1D55354F8819D4AAB75F2240F0A780773BF45BC5053FCB79B90645918D`;
- genau neun Einträge auf der ZIP-Wurzelebene;
- SHA-256 jedes entpackten Eintrags stimmt mit der jeweiligen Rohdatei in
  `raw` überein.

Das ZIP ist ein reproduzierbares Arbeitsartefakt und wird von Git ignoriert.
Die Rohdateien wurden bei seiner Erstellung weder geändert noch umbenannt.

## Bekannte Einschränkungen

- Alle 6.175 Routen haben `route_type=0`. Im GTFS-Standard bedeutet 0
  Straßenbahn. Das ist für U-Bahn, S-Bahn, Eisenbahn und Bus fachlich falsch.
- 5.849 Routen referenzieren die zwar vorhandene, aber inhaltlich nur als
  `unknown` ausgefüllte Agency. 38 von 42 Agency-URLs lauten nur `http://`;
  die `unknown`-Agency besitzt außerdem keine gültige Zeitzonenangabe.
- Alle `route_short_name`-Felder sind leer; `route_long_name` ist jeweils
  vorhanden.
- Der Datensatz deckt große Teile Deutschlands ab und ist nicht auf das
  Münchner Untersuchungsgebiet gefiltert.
- `München=1` markiert 68 Schienen-/U-Bahn-/Tram-Routen, aber nicht alle für
  ein Münchner Szenario relevanten Buslinien. Das Feld ist deshalb allein
  kein vollständiges Filterkriterium.
- Die Rohdaten werden in diesem Arbeitsschritt nicht korrigiert. Der
  vollständige Audit steht in `docs/gtfs2040/gtfs2037_raw_audit.md`.
