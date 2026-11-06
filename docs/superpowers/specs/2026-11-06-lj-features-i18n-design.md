# Bar Display: LJ-Funktionen, Club-Betrieb, Sprachen — Design

Stand: 2026-11-06. Baut auf `2026-11-06-panels-status-display-options-design.md` auf.

## A. Anzeige

### Nächste Phrase

- Ist `:phrase` aktiv, belegt sie die ganze obere Zeile des Info-Blocks:
  `CHORUS → DOWN · 3 Takte`, Hintergrund in der Farbe der aktuellen Phrase. In der letzten Phrase
  steht statt des nächsten Namens `ENDE`.
- Die übrigen Info-Einträge (Tonart, Loop, Cue) teilen sich die untere Zeile. Ohne Phrase bleibt
  das 2×2-Raster.
- Einheit einstellbar (`:display :phrase-unit`, `:bars` Standard oder `:beats`). Takte werden
  aufgerundet (`ceil(beats/4)`).
- `analysis/phrase-at` liefert zusätzlich `:next` (die folgende Phrase oder `nil`).

### Hörbares Deck hervorheben

- `:display :dim-off-air?` (Standard `true`): Felder live verbundener Player, die nicht ON AIR
  sind, werden mit 55 % Schwarz überlagert, auch die Waveform.
- Meldet kein Player ON AIR, wird nichts gedimmt.
- Reine Funktion `cell/dim?`.

### Beat

- Neuer Block `:beat`: große Ziffer 1–4, die 1 in Rot. Kopfreihenfolge
  `info, beat, status, tempo, time`. Ausblendreihenfolge bei Platzmangel
  `tempo, status, beat, time, info`.
- `:display :flash-downbeat?` (Standard `false`): Der Zellrahmen leuchtet bei jedem Wechsel auf
  die 1 für 150 ms auf. Reine Funktion `cell/downbeat?`.
- Dimmen und Blitzen zeichnet die Zelle als Ebene über ihre Kinder
  (`isOptimizedDrawingEnabled` = false, `paintChildren` überschrieben).

## B. Betrieb

### Bildschirme identifizieren

- Button in den Settings. Auf jedem Bildschirm erscheint für 4 s ein randloses Fenster mit Nummer,
  ID, Auflösung und ggf. „Hauptbildschirm“. Ein Klick schließt es.

### Spät angeschlossene Bildschirme

- Solange Anzeigen offen sind, prüft `core` alle 3 s die Menge der Bildschirm-IDs. Ändert sie sich,
  werden alle Panels mit denselben Beschreibungen neu geöffnet. Reine Funktion
  `screen/screens-changed?`.

### Presets

- Settings-Datei: `:presets {"Name" {:panels … :display … :simulate? …}}`.
- Reine Funktionen `config/save-preset`, `config/load-preset`, `config/delete-preset`.
- UI oben im Settings-Fenster: Auswahl, „Laden“ (füllt das Formular), „Als Preset speichern…“,
  „Löschen“. Wirksam erst mit „Übernehmen“.
- „Übernehmen“ und das Speichern der Fensterpositionen erhalten vorhandene Presets.

## C. Sprachen

- Neuer Namespace `bardisplay.i18n`. Texte liegen in `src/bardisplay/i18n/<code>.edn`
  (namespaced Keywords → Text, `:language/name` = Name der Sprache). Vorhandene Dateien werden
  automatisch zur Auswahl angeboten.
- Einstellung `:language`: `:auto` (Systemsprache, Standard) oder ein Code. Gilt global, nicht pro
  Preset.
- Auflösung: gewählte Sprache → sonst Englisch → sonst Schlüsselname. Texte mit Platzhaltern
  über `format`.
- Übersetzt werden Settings, Show-Fenster, Status, Beschriftungen der Anzeige, Fehlermeldungen.
  CDJ-Begriffe stehen ebenfalls in den Dateien (deutsch unverändert). rekordbox-Phrasennamen
  bleiben unübersetzt.
- Sprachwechsel baut das Settings-Fenster neu auf; Anzeigen übernehmen ihn beim nächsten
  „Übernehmen“.

## Tests

Phrase `:next` und Countdown in beiden Einheiten, Info-Aufteilung, Layout mit `:beat`,
`dim?`, `downbeat?`, `screens-changed?`, Presets, Normalisierung von `:presets`/`:language`,
i18n-Rückfall und -Auflösung, `de.edn` enthält alle Schlüssel aus `en.edn`.
