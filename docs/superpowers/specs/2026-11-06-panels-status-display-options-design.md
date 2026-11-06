# Bar Display: Panels, Status und Anzeige-Optionen — Design

Stand: 2026-11-06

## Ziel

Die Bar Display soll im Club zuverlässig laufen und flexibel auf wechselnde Setups
(ein normaler Bildschirm zum Testen bis zu vier Bar-Panels) einstellbar sein. Zielgruppe
der Anzeige ist der Lichttechniker (LJ), nicht der DJ.

Umfang:

1. Feste Zuordnung Player → Feld
2. Robuste Bildschirmwahl mit Ersatz
3. Mehrere Panels konfigurierbar
4. Statusanzeige im Show-Fenster
5. Helligkeit (Dimmen)
6. Abschaltbare Blöcke plus neue Infos: Phrase, Tonart, Loop, nächster Cue
7. Optionale gelegentliche Laufschrift für abgeschnittene Titel
8. Tests

Ausdrücklich **nicht** im Umfang: visuelle Warnung bei Track-Ende (Blinken o. ä.),
Einstellungen pro Panel für Anzeige-Optionen.

## 1–3: Panels und Zuordnung

### Settings-Format

`~/.bar-display.edn` bekommt diese Form:

```clojure
{:panels  [{:screen  {:id "\\Display2" :width 1920 :height 360 :x 1920 :y 0}
            ;; oder :window für ein normales Fenster
            :layout  :row          ; :row | :column | :grid
            :players [1 2]         ; Reihenfolge = Reihenfolge der Felder
            :window-bounds [80 40 1500 1100]}]   ; nur bei :window
 :display {:blocks         #{:track :status :tempo :time :detail :preview
                             :phrase :key :loop :cue}
           :brightness     100     ; 20–100 (%)
           :title-scroll   :off    ; :off | :occasional
           :warn-seconds   60
           :urgent-seconds 30
           :scale          4       ; Waveform-Zoom
           :always-on-top? false}
 :simulate? false}
```

- Bis zu 4 Panels.
- Ein Panel mit `:screen :window` ist ein normales, verschiebbares Fenster (Test am Schreibtisch).
- Das Layout `:separate` entfällt. Mehrere Fenster bzw. Bildschirme werden über mehrere Panels abgebildet.
- **Migration:** Eine Datei im alten Format (`:layout`, `:player-count`, `:screen` auf oberster Ebene)
  wird beim Laden in genau ein Panel umgewandelt: Player `1..player-count`, gleiches Layout
  (`:separate` → `player-count` Panels mit je einem Player auf demselben Ziel, im Fenstermodus
  kaskadiert wie bisher), gleicher Bildschirm. Fehlende Werte kommen aus den Defaults. Die
  Umwandlung ist eine reine Funktion.

### Feste Zuordnung

- Jedes Feld zeigt immer den Player, der ihm in `:players` zugewiesen ist.
- Die bisherige Umverteilung (`reassign!` bei Device found/lost) entfällt.
- Ist der Player nicht im Netz, zeigt das Feld einen gedimmten Platzhalter
  „PLAYER n – OFFLINE“. Das Layout ändert sich dadurch nicht.
- Der Snapshot bekommt `:online?`. Im Simulationsmodus gelten simulierte Player als online.

### Bildschirmauflösung (`screen/resolve-screen`)

Reine Funktion: gespeicherte Bildschirmangabe + Liste vorhandener Bildschirme (`describe-screens`)
+ bereits von anderen Panels belegte Bildschirme → `{:device-id … :reason …}`.

Reihenfolge:

1. Bildschirm mit gleicher ID vorhanden und frei → `:reason :exact`
2. Sonst: freier Bildschirm mit gleicher Breite und Höhe (bei mehreren: der mit der nächstgelegenen
   Position) → `:reason :same-size`
3. Sonst: breitester freier Bildschirm, der nicht der Hauptbildschirm ist und ein Seitenverhältnis
   ≥ 2.5 hat → `:reason :widest`
4. Sonst: kein Bildschirm → `:reason :not-found`, das Panel wird nicht geöffnet.

Panels werden in Listenreihenfolge aufgelöst, damit zwei Panels nie denselben Bildschirm bekommen.
`:window` wird nicht aufgelöst.

## 4: Status

`core` führt pro Panel einen Statuseintrag:

```clojure
{:index 0 :wanted "\\Display2" :actual "\\Display4" :reason :same-size
 :open? true :players [1 2]}
```

Das Show-Fenster (`bardisplay.blt`) zeigt über den Buttons einen Statusbereich, der sich
jede Sekunde aus diesen Einträgen und dem Online-Zustand der Player neu aufbaut:

```
BLT: Online (4 Player)
Panel 1  Display 2   läuft    Player 1 ●  2 ●
Panel 2  Display 3 → Ersatz Display 4   läuft    Player 3 ●  4 ○ offline
Panel 3  Display 5   NICHT GEFUNDEN – Panel nicht geöffnet
```

- Grün: alles ok. Gelb: Ersatzbildschirm oder Player offline. Rot: Panel nicht geöffnet.
- BLT offline: „BLT: Offline – Anzeigen öffnen sich, sobald BLT online geht“.
- Das Settings-Fenster zeigt nach „Apply“ dieselbe Kurzfassung.
- Der Timer des Statusbereichs stoppt beim Shutdown der Show.

## 5–7: Anzeige-Optionen

Alle Optionen gelten für alle Panels gemeinsam.

### Blöcke

| Schlüssel | Inhalt |
|---|---|
| (immer) | Player-Badge mit Beat-im-Takt |
| `:track` | Titel/Artist |
| `:status` | ON AIR / MASTER / SYNC |
| `:tempo` | Pitch / BPM |
| `:time` | gespielte und verbleibende Zeit |
| `:info` | wird automatisch angezeigt, wenn mindestens eines von `:phrase :key :loop :cue` aktiv ist |
| `:detail` | scrollende Waveform |
| `:preview` | Waveform-Übersicht |

- Ausgeblendete Kopfblöcke geben ihre Breite an den Titelblock ab.
- Fehlen beide Waveforms, nimmt der Kopf die ganze Höhe ein. Fehlt nur eine, bekommt die andere den Platz.
- Das Layout ist eine reine Funktion `cell/layout`: Breite, Höhe, aktive Blöcke →
  Map Block → `[x y w h]`. Nicht angezeigte Blöcke fehlen in der Map.

### Info-Block (2×2)

Zeigt nur die aktiven Einträge, in der Reihenfolge Phrase, Tonart, Loop, Cue:

- **Phrase:** Typ des aktuellen Abschnitts aus der rekordbox-Phrasenanalyse (Intro, Verse, Chorus,
  Bridge, Up, Down, Outro …) in der zugehörigen Phrasenfarbe, plus Beats bis zum nächsten Abschnitt,
  z. B. `CHORUS · 12`. Ohne Analyse: gedimmt `PHRASE –`.
- **Tonart:** Label aus `TrackMetadata.getKey()`, z. B. `Am`. Ohne Wert: `KEY –`.
- **Loop:** Pill `LOOP n` (aktive Loop-Länge in Beats), leuchtet bei `isLooping`. Kann der Player
  das nicht melden (`canReportLooping` falsch), dann gedimmt `LOOP –`.
- **Nächster Cue:** Zeit bis zum nächsten Memory- oder Hot-Cue (`CueList.findEntryAfter`),
  `CUE m:ss`, mit Farbpunkt in der Cue-Farbe. Kein weiterer Cue: `CUE –`.

Die Berechnung von Phrase (aktueller Abschnitt + Beats bis zum nächsten anhand der Beat-Nummer)
und von „nächster Cue“ (Abstand in ms) sind reine Funktionen in `state`.

### Helligkeit

Glass Pane pro Panel-Fenster, das ein schwarzes Rechteck mit Alpha `1 - brightness/100` über
alles legt. Damit werden auch die Beat-Link-Waveforms gedimmt. Bei 100 % wird nichts gezeichnet.

### Titel-Laufschrift

- `:off` (Standard): wie bisher abschneiden mit „…“.
- `:occasional`: Passt Titel oder Artist nicht, läuft der Text etwa alle 30 s einmal komplett
  nach links durch (ca. 4 s), springt zurück an den Anfang und steht dann wieder still.
  Passender Text bewegt sich nie.
- Der Versatz ist eine reine Funktion `paint/scroll-offset`: Zeit, Textbreite, verfügbare Breite → Pixel.
- Der Animationstakt der Zelle repaintet den Titelblock während des Durchlaufs.

## Settings-Fenster

- Bereich **Panels**: bis zu 4 Zeilen mit Bildschirm (Auswahl), Anordnung (Auswahl), Player
  (Textfeld, z. B. `1 2`), Entfernen-Button. Darunter „Panel hinzufügen“.
  Eingabeprüfung: Player 1–6, keine Dopplung innerhalb eines Panels, mindestens ein Player.
- Bereich **Anzeige**: Checkboxen für die Blöcke, Helligkeit-Regler, Laufschrift-Auswahl,
  Warn-Schwellen, Waveform-Zoom, „Keep above other windows“.
- „Simulate players“ wie bisher (wird online weiterhin ignoriert).
- Statuszeile, „Stop“, „Apply“.

## Simulation

`bardisplay.sim` liefert zusätzlich Phrase-Abschnitte, Tonart, gelegentliche Loops und Cues,
damit alle Blöcke ohne CDJs geprüft werden können.

## Tests

Verzeichnis `bar-display/test`, `clojure.test`. Start über `run-tests.bat`.
`bar-display/test` wird dafür lokal im `:dev`-Profil von `project.clj` ergänzt.

Abgedeckt:

- Migration alter Settings, Erzeugung der Panel-Beschreibungen
- `resolve-screen`: exakt, gleiche Größe, breitester, keiner, Konflikt zweier Panels
- `cell/layout`: verschiedene Größen und Block-Kombinationen; keine Überlappung, keine negativen Maße,
  alles innerhalb der Zelle
- Phrase- und Cue-Berechnung
- Formatierung von Zeit, Pitch, Tempo
- `scroll-offset`

Zusätzlich optischer Check über `bardisplay.dev/render-samples` (PNG-Varianten: alle Blöcke,
wenige Blöcke, Offline-Feld, gedimmt).

## Fehlerbehandlung

- Fehler beim Lesen der Settings → Defaults, Warnung im Log (wie bisher).
- Ungültige Panel-Einträge (z. B. Player außerhalb 1–6) werden beim Laden gefiltert; ein Panel ohne
  gültige Player wird übersprungen und im Status als Fehler gezeigt.
- Fehler beim Datensammeln einer Zelle werden wie bisher geloggt, die Zelle zeigt weiter den letzten Stand.
