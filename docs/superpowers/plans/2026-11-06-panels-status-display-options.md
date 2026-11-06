# Panels, Status and Display Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the bar display reliable and configurable for the club: fixed player-to-cell mapping, up to four panels with screen fallback, a live status readout, dimming, switchable blocks with new info items (phrase, key, loop, next cue), occasional title scrolling, and a test suite.

**Architecture:** Pure logic is pulled into small, testable functions (`bardisplay.analysis`, `bardisplay.status`, `config/migrate`, `screen/resolve-screen`, `cell/layout`, `paint/scroll-offset`, `paint/info-content`). Swing code calls into them. Settings move to a new shape (`:panels` + `:display`) with automatic migration of the old file. Spec: `docs/superpowers/specs/2026-11-06-panels-status-display-options-design.md`.

**Tech Stack:** Clojure, Seesaw/Swing, core.async, Beat Link 8.1 (`org.deepsymmetry.beatlink`), crate-digger, clojure.test. Runs on the classpath of a Beat Link Trigger checkout (Leiningen `:dev` profile).

---

## Conventions for every task

- Repository: `O:\Beatlink-trigger\bar-display` (its own git repo, remote `Brunsben/bar-display`). It lives inside a Beat Link Trigger checkout at `O:\Beatlink-trigger`, whose `project.clj` puts `bar-display/src` (and after Task 1 `bar-display/test`) on the `:dev` classpath. **Never commit to the outer BLT repo.**
- Work on branch `panels` in the bar-display repo (created in Task 1).
- The share `O:` is a network drive: `sed -i` fails there. Use the Edit/Write tools for file changes.
- Run tests from PowerShell (first run takes ~1 minute because Leiningen starts a JVM):
  - all: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
  - one namespace: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.config-test`
  - Success ends with `0 failures, 0 errors.` and exit code 0.
- Commit from Git Bash: `git -C /o/Beatlink-trigger/bar-display ...`. End every commit message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Between Task 3 and Task 10 the settings window and the standalone launcher are temporarily broken (old and new settings shape mixed; after Task 7 the old `core.clj` no longer even compiles because `state/online-players` is gone, until Task 8 replaces it). That is expected on the branch: no test namespace loads `core` or `settings`, and tests must still pass after every task.

## File map

| File | Responsibility |
|---|---|
| `run-tests.bat`, `run-tests.clj` | New. Test runner. |
| `test/bardisplay/*_test.clj` | New. Tests. |
| `src/bardisplay/analysis.clj` | New. Pure conversion of phrase analysis and cue lists; phrase/cue lookup. |
| `src/bardisplay/status.clj` | New. Pure status lines and their HTML. |
| `src/bardisplay/theme.clj` | Add `:loop` color, `:info` and `:min-track` metrics. |
| `src/bardisplay/config.clj` | Rewrite. New settings shape, migration, player parsing, panel descriptions. |
| `src/bardisplay/paint.clj` | Rewrite. `live?`, scrolling, info block, offline handling. |
| `src/bardisplay/cell.clj` | Rewrite. Pure `layout`, block toggles, info block, scroll repaint. |
| `src/bardisplay/sim.clj` | Rewrite. Simulated phrase, key, loop, cue. |
| `src/bardisplay/state.clj` | Rewrite. Online flag, phrase, key, loop, cue in snapshots. |
| `src/bardisplay/screen.clj` | Rewrite. Screen resolution, dimming glass pane, fixed players. |
| `src/bardisplay/core.clj` | Rewrite. Open panels from descriptions, record status. |
| `src/bardisplay/settings.clj` | Rewrite. Panel list and display section. |
| `src/bardisplay/blt.clj` | Status area with timer, new load order. |
| `src/bardisplay/dev.clj` | Rewrite. Render and demo helpers for the new options. |
| `README.md` | Document panels, tests. |

---

### Task 1: Branch and test harness

**Files:**
- Create: `run-tests.bat`, `run-tests.clj`, `test/bardisplay/theme_test.clj`
- Modify (local only, not committed): `O:\Beatlink-trigger\project.clj` (`:dev` profile)

- [ ] **Step 1: Create the branch**

```bash
git -C /o/Beatlink-trigger/bar-display checkout -b panels
```

- [ ] **Step 2: Put the test folder on the dev classpath**

In `O:\Beatlink-trigger\project.clj` replace

```clojure
                       :source-paths ["bar-display/src"]
```

with

```clojure
                       :source-paths ["bar-display/src" "bar-display/test"]
```

- [ ] **Step 3: Write `run-tests.clj`**

```clojure
;;;; Runs the bar display tests. Start it with run-tests.bat. Pass test
;;;; namespace names to run only those, for example:
;;;;   run-tests.bat bardisplay.config-test

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.test :as t])

(def test-root
  (.getCanonicalFile (io/file (.getParentFile (.getCanonicalFile (io/file *file*))) "test")))

(defn- namespace-of
  "Turns a test file path into its namespace symbol."
  [^java.io.File f]
  (-> (str (.relativize (.toPath test-root) (.toPath (.getCanonicalFile f))))
      (str/replace #"\.clj$" "")
      (str/replace #"[\\/]" ".")
      (str/replace "_" "-")
      symbol))

(let [requested  (map symbol *command-line-args*)
      found      (->> (file-seq test-root)
                      (filter #(str/ends-with? (.getName ^java.io.File %) "_test.clj"))
                      (map namespace-of)
                      sort)
      namespaces (if (seq requested) requested found)]
  (apply require namespaces)
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
```

- [ ] **Step 4: Write `run-tests.bat`**

```bat
@echo off
rem Runs the bar display tests. Optional arguments name test namespaces to run.
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=%LOCALAPPDATA%\Programs\clojure-toolchain\jdk-21.0.13+9"
set "PATH=%JAVA_HOME%\bin;%LOCALAPPDATA%\Programs\clojure-toolchain\bin;%PATH%"

cd /d "%~dp0.."

rem Skip the user guide build, which is only needed to package Beat Link Trigger.
call lein.bat update-in :prep-tasks empty -- run -m clojure.main "%~dp0run-tests.clj" %*
exit /b %errorlevel%
```

- [ ] **Step 5: Write the first test, `test/bardisplay/theme_test.clj`**

```clojure
(ns bardisplay.theme-test
  (:require [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is]]))

(deftest su-test
  (is (= 2 (theme/su 0.5 4)))
  (is (= 112 (theme/su 1.0 112))))

(deftest scale-test
  (is (= 1.0 (theme/scale 440)))
  (is (= 0.5 (theme/scale 220))))
```

- [ ] **Step 6: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `Ran 2 tests containing 4 assertions. 0 failures, 0 errors.`

- [ ] **Step 7: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add run-tests.bat run-tests.clj test/bardisplay/theme_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Add test runner

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `bardisplay.analysis` (phrases and cues)

**Files:**
- Create: `src/bardisplay/analysis.clj`, `test/bardisplay/analysis_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.analysis-test
  (:require [bardisplay.analysis :as analysis]
            [beat-link-trigger.util :as util]
            [clojure.test :refer [deftest is]]))

(def sample-phrases
  {:end-beat 100
   :entries  [{:beat 1 :label "Intro"} {:beat 33 :label "Chorus"} {:beat 65 :label "Outro"}]})

(deftest phrase-at-test
  (is (= {:beat 1 :label "Intro" :beats-left 32} (analysis/phrase-at sample-phrases 1)))
  (is (= {:beat 1 :label "Intro" :beats-left 1} (analysis/phrase-at sample-phrases 32)))
  (is (= {:beat 33 :label "Chorus" :beats-left 32} (analysis/phrase-at sample-phrases 33)))
  (is (= {:beat 65 :label "Outro" :beats-left 1} (analysis/phrase-at sample-phrases 99)))
  (is (nil? (analysis/phrase-at sample-phrases 100)))
  (is (nil? (analysis/phrase-at sample-phrases 0)))
  (is (nil? (analysis/phrase-at sample-phrases nil)))
  (is (nil? (analysis/phrase-at nil 10))))

(deftest next-cue-test
  (let [cues [{:ms 1000 :color :a} {:ms 5000 :color :b}]]
    (is (= {:ms-left 1000 :color :a} (analysis/next-cue cues 0)))
    (is (= {:ms-left 4000 :color :b} (analysis/next-cue cues 1000)))
    (is (nil? (analysis/next-cue cues 5000)))
    (is (nil? (analysis/next-cue cues nil)))
    (is (nil? (analysis/next-cue nil 0)))))

(deftest sample-track-conversion-test
  ;; Sample track 2 shipped with Beat Link Trigger has phrase analysis and cues.
  (let [track   (util/data-for-simulation :entry 2)
        phrases (analysis/phrases (:song-structure track))
        cues    (analysis/cues (:cue-list track))]
    (is (seq (:entries phrases)))
    (is (every? string? (map :label (:entries phrases))))
    (is (apply <= (map :beat (:entries phrases))))
    (is (pos? (:end-beat phrases)))
    (is (seq cues))
    (is (apply <= (map :ms cues))))
  (is (nil? (analysis/phrases nil)))
  (is (nil? (analysis/cues nil))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.analysis-test`
Expected: FAIL, `Could not locate bardisplay/analysis__init.class, bardisplay/analysis.clj`.

- [ ] **Step 3: Write `src/bardisplay/analysis.clj`**

```clojure
(ns bardisplay.analysis
  "Pure helpers that turn Beat Link track analysis (phrases and cues)
  into the small values the info block shows. Nothing here talks to the
  network, so it can be tested directly."
  (:import [org.deepsymmetry.beatlink Util]
           [org.deepsymmetry.beatlink.data CueList CueList$Entry]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureEntry
            RekordboxAnlz$SongStructureTag]))

(defn phrases
  "Converts a rekordbox song structure (phrase analysis) tag into
  `{:end-beat n :entries [{:beat :label :color :text-color} ...]}`,
  sorted by beat. Returns `nil` when there is no tag."
  [^RekordboxAnlz$SongStructureTag tag]
  (when tag
    (let [body (.body tag)]
      {:end-beat (.endBeat body)
       :entries  (->> (.entries body)
                      (map (fn [^RekordboxAnlz$SongStructureEntry entry]
                             {:beat       (.beat entry)
                              :label      (Util/phraseLabel entry)
                              :color      (Util/phraseColor entry)
                              :text-color (Util/phraseTextColor entry)}))
                      (sort-by :beat)
                      vec)})))

(defn phrase-at
  "Finds the phrase playing at `beat`, adding `:beats-left` until the
  next phrase (or the end of the analysis). Returns `nil` outside the
  analysed range or when anything is missing."
  [{:keys [entries end-beat]} beat]
  (when (and beat (seq entries) end-beat
             (>= beat (:beat (first entries)))
             (< beat end-beat))
    (let [[before after] (split-with #(<= (:beat %) beat) entries)
          next-beat      (if-let [following (first after)] (:beat following) end-beat)]
      (assoc (last before) :beats-left (- next-beat beat)))))

(defn cues
  "Converts a cue list into `[{:ms :color} ...]` sorted by time, covering
  hot cues and memory points. Returns `nil` when there is no cue list."
  [^CueList cue-list]
  (when cue-list
    (->> (.entries cue-list)
         (map (fn [^CueList$Entry entry]
                {:ms (.cueTime entry) :color (.getColor entry)}))
         (sort-by :ms)
         vec)))

(defn next-cue
  "Finds the first cue after `now-ms`, as `{:ms-left :color}`."
  [cues now-ms]
  (when now-ms
    (when-let [cue (first (filter #(> (:ms %) now-ms) cues))]
      {:ms-left (- (:ms cue) now-ms) :color (:color cue)})))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.analysis-test`
Expected: `0 failures, 0 errors.` If `sample-track-conversion-test` reports no cues, check the sample track with
`(count (.entries (:cue-list (util/data-for-simulation :entry 2))))` and switch the test to sample track 1 if that one has cues; keep the phrase assertions on track 2.

- [ ] **Step 5: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/analysis.clj test/bardisplay/analysis_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Add phrase and cue analysis helpers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Theme additions and new settings shape (`bardisplay.config`)

**Files:**
- Modify: `src/bardisplay/theme.clj`
- Modify: `src/bardisplay/cell.clj` (add `all-blocks` only)
- Rewrite: `src/bardisplay/config.clj`
- Create: `test/bardisplay/config_test.clj`

- [ ] **Step 1: Theme additions**

In `src/bardisplay/theme.clj`, add to the `colors` map after `:wave-tick Color/WHITE`:

```clojure
   :loop          (Color. 120 220 60)
```

(so the map ends `:wave-tick     Color/WHITE` newline `:loop          (Color. 120 220 60)})`), and in `metrics` add after the `:time` entry:

```clojure
   :info         {:full 300 :compact 220}
   ;; The narrowest the track title is squeezed to when the header grows to fill a cell.
   :min-track    200
```

- [ ] **Step 2: Add `all-blocks` to `src/bardisplay/cell.clj`**

Directly after the `tick-interval` def add:

```clojure
(def all-blocks
  "Every display block that can be switched on or off. The player badge
  is always shown and is not listed."
  #{:track :status :tempo :time :detail :preview :phrase :key :loop :cue})
```

- [ ] **Step 3: Write the failing test `test/bardisplay/config_test.clj`**

```clojure
(ns bardisplay.config-test
  (:require [bardisplay.config :as config]
            [clojure.test :refer [deftest is testing]]))

(deftest parse-players-test
  (is (= {:players [1 2]} (config/parse-players "1 2")))
  (is (= {:players [3 1]} (config/parse-players " 3, 1 ")))
  (is (:error (config/parse-players "")))
  (is (:error (config/parse-players "0 2")))
  (is (:error (config/parse-players "7")))
  (is (:error (config/parse-players "a")))
  (is (:error (config/parse-players "2 2"))))

(deftest migrate-test
  (testing "old single panel settings become one panel"
    (let [m (config/migrate {:layout :row :player-count 3 :screen "\\Display2"
                             :window-bounds [1 2 3 4] :scale 6 :always-on-top? true
                             :simulate? true :warn-seconds 90 :urgent-seconds 20})]
      (is (= [{:screen {:id "\\Display2"} :layout :row :players [1 2 3] :window-bounds [1 2 3 4]}]
             (:panels m)))
      (is (= {:scale 6 :always-on-top? true :warn-seconds 90 :urgent-seconds 20} (:display m)))
      (is (true? (:simulate? m)))))
  (testing "separate windows become one panel per player"
    (let [m (config/migrate {:layout :separate :player-count 2 :screen :window
                             :window-bounds [0 0 1000 1000]})]
      (is (= [[1] [2]] (mapv :players (:panels m))))
      (is (every? #(= :window (:screen %)) (:panels m)))
      (is (apply distinct? (map :window-bounds (:panels m))))))
  (testing "new format and missing settings are left alone"
    (let [s {:panels [] :display {}}]
      (is (identical? s (config/migrate s))))
    (is (nil? (config/migrate nil)))))

(deftest normalize-test
  (let [n (config/normalize {:panels  [{:screen :window :players [1 9 1 2]}]
                             :display {:brightness 50 :blocks [:track]}})]
    (is (= [1 2] (:players (first (:panels n)))))
    (is (= :column (:layout (first (:panels n)))))
    (is (= 50 (get-in n [:display :brightness])))
    (is (= #{:track} (get-in n [:display :blocks])))
    (is (= 4 (get-in n [:display :scale])))
    (is (false? (:simulate? n))))
  (is (= 4 (count (:panels (config/normalize {:panels (repeat 6 {:players [1]})})))))
  (is (= (:panels config/defaults) (:panels (config/normalize {}))))
  (is (= (:panels config/defaults) (:panels (config/normalize nil)))))

(deftest panels-test
  (let [[a b] (config/panels
               {:panels  [{:screen :window :layout :row :players [1 2] :window-bounds [0 0 10 10]}
                          {:screen {:id "X"} :layout :grid :players [3 4 5]}]
                :display {:scale 5 :blocks #{:track} :title-scroll :off :brightness 80
                          :always-on-top? true :warn-seconds 1}})]
    (is (= {:screen :window :players [1 2] :window-bounds [0 0 10 10] :rows 1 :columns 2
            :scale 5 :blocks #{:track} :title-scroll :off :brightness 80 :always-on-top? true}
           a))
    (is (= {:rows 2 :columns 2} (select-keys b [:rows :columns])))))
```

- [ ] **Step 4: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.config-test`
Expected: FAIL (`parse-players`, `migrate`, `normalize` unresolved).

- [ ] **Step 5: Rewrite `src/bardisplay/config.clj`**

```clojure
(ns bardisplay.config
  "The settings the bar displays are driven by: their shape, migrating
  older settings files, checking what the user typed, and loading and
  saving them so they survive a restart.

  Settings look like this:

      {:panels    [{:screen        {:id \"\\\\Display2\" :width 1920 :height 440 :x 0 :y 0}
                                   ; or :window for an ordinary window
                    :layout        :row          ; :row, :column or :grid
                    :players       [1 2]         ; fixed, in cell order
                    :window-bounds [80 40 1500 1100]}]
       :display   {:blocks #{...} :brightness 100 :title-scroll :off
                   :warn-seconds 60 :urgent-seconds 30 :scale 4 :always-on-top? false}
       :simulate? false}"
  (:require [bardisplay.cell :as cell]
            [bardisplay.screen :as screen]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]))

(def settings-file
  "Where the settings are kept between sessions."
  (io/file (System/getProperty "user.home") ".bar-display.edn"))

(def max-panels
  "How many panels can be configured."
  4)

(def default-panel
  "A panel as it starts out: an ordinary window with four players stacked."
  {:screen        :window
   :layout        :column
   :players       [1 2 3 4]
   :window-bounds [80 40 1500 1100]})

(def defaults
  "The settings used until the user changes them."
  {:panels    [default-panel]
   :display   {:blocks         cell/all-blocks
               :brightness     100
               :title-scroll   :off
               :warn-seconds   60
               :urgent-seconds 30
               :scale          4
               :always-on-top? false}
   :simulate? false})

(defrecord Layout [key label describe]
  Object
  (toString [_] label))

(def layouts
  "The ways a panel's cells can be arranged. `describe` turns a player
  count into the grid of rows and columns to use."
  [(->Layout :row "Side by side" (fn [n] {:rows 1 :columns n}))
   (->Layout :column "Stacked" (fn [n] {:rows n :columns 1}))
   (->Layout :grid "Grid (two per row)" (fn [n] {:rows (long (Math/ceil (/ n 2.0))) :columns (min n 2)}))])

(defn layout-for
  "Looks up a layout by its key."
  [k]
  (or (first (filter #(= k (:key %)) layouts))
      (first layouts)))

(defrecord ScreenChoice [id label spec]
  Object
  (toString [_] label))

(defn screen-choices
  "Returns the targets a panel can be sent to: an ordinary window, or any
  of the attached screens. `spec` is what gets saved."
  []
  (into [(->ScreenChoice :window "Window (not full screen)" :window)]
        (map (fn [{:keys [id width height x y]}]
               (->ScreenChoice id (format "%s  -  %d x %d" id width height)
                               {:id id :width width :height height :x x :y y}))
             (screen/describe-screens))))

(defn- cascade-bounds
  "Spreads separate windows down the screen so they do not cover each
  other."
  [[x y w h] index count]
  (let [height (max 120 (long (/ (- h (* 34 (dec count))) count)))]
    [x (+ y (* index (+ height 34))) w height]))

(defn migrate
  "Converts settings saved by the single panel version into the panel
  list form. Anything already in the new form (or `nil`) is returned
  unchanged."
  [settings]
  (if (or (nil? settings) (contains? settings :panels))
    settings
    (let [{:keys [layout player-count screen window-bounds]} settings
          n      (or player-count 4)
          target (if (string? screen) {:id screen} :window)
          bounds (or window-bounds (:window-bounds default-panel))
          panels (if (= layout :separate)
                   (vec (for [index (range n)]
                          {:screen        target
                           :layout        :row
                           :players       [(inc index)]
                           :window-bounds (cascade-bounds bounds index n)}))
                   [{:screen        target
                     :layout        (if (#{:row :column :grid} layout) layout :column)
                     :players       (vec (range 1 (inc n)))
                     :window-bounds bounds}])]
      {:panels    panels
       :display   (into {} (filter (comp some? val))
                        (select-keys settings [:scale :always-on-top? :warn-seconds :urgent-seconds]))
       :simulate? (boolean (:simulate? settings))})))

(defn clean-players
  "Keeps only valid player numbers (1-6), each once, in order."
  [players]
  (->> players
       (filter #(and (integer? %) (<= 1 % 6)))
       distinct
       vec))

(defn parse-players
  "Reads the player list typed for a panel, such as `1 2` or `3, 4`.
  Returns `{:players [...]}` or `{:error \"...\"}`."
  [s]
  (let [tokens  (remove str/blank? (str/split (or s "") #"[\s,;]+"))
        numbers (map parse-long tokens)]
    (cond
      (empty? tokens)
      {:error "enter at least one player"}

      (some #(or (nil? %) (not (<= 1 % 6))) numbers)
      {:error "players must be numbers from 1 to 6"}

      (not= (count numbers) (count (distinct numbers)))
      {:error "a player is listed twice"}

      :else
      {:players (vec numbers)})))

(defn normalize
  "Fills in defaults and drops anything invalid, so the rest of the code
  can rely on the settings' shape."
  [settings]
  (let [display (merge (:display defaults) (:display settings))]
    {:panels    (->> (or (seq (:panels settings)) (:panels defaults))
                     (take max-panels)
                     (mapv #(update (merge default-panel %) :players clean-players)))
     :display   (update display :blocks set)
     :simulate? (boolean (:simulate? settings))}))

(defn panels
  "Turns the settings into the panel descriptions `bardisplay.core/open!`
  expects: each panel's screen, players and grid, plus the display
  options every panel shares."
  [{:keys [panels display]}]
  (mapv (fn [{:keys [layout players] :as panel}]
          (merge (select-keys panel [:screen :players :window-bounds])
                 ((:describe (layout-for layout)) (max 1 (count players)))
                 (select-keys display [:scale :blocks :title-scroll :brightness :always-on-top?])))
        panels))

(defn load-settings
  "Reads the saved settings, migrating older files and falling back to
  the defaults for anything missing or unreadable."
  []
  (normalize
   (migrate
    (when (.exists settings-file)
      (try
        (edn/read-string {:readers {}} (slurp settings-file))
        (catch Exception e
          (timbre/warn e "Could not read" (str settings-file) "- using defaults.")
          nil))))))

(defn save-settings!
  "Writes the settings out so the next session starts the same way."
  [settings]
  (try
    (spit settings-file (pr-str (select-keys settings [:panels :display :simulate?])))
    (str settings-file)
    (catch Exception e
      (timbre/error e "Could not save" (str settings-file))
      nil)))
```

- [ ] **Step 6: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.` (theme, analysis, config).

- [ ] **Step 7: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/theme.clj src/bardisplay/cell.clj src/bardisplay/config.clj test/bardisplay/config_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "New settings shape with panel list and migration

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Status lines (`bardisplay.status`)

**Files:**
- Create: `src/bardisplay/status.clj`, `test/bardisplay/status_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.status-test
  (:require [bardisplay.status :as status]
            [clojure.test :refer [deftest is testing]]))

(deftest lines-test
  (testing "offline with nothing open"
    (is (= [{:level :warn :text "BLT offline \u2013 displays open when it goes online"}
            {:level :warn :text "Displays closed"}]
           (status/lines {:online? false :simulating? false :visible #{} :panels []}))))
  (testing "simulation header"
    (is (= {:level :warn :text "Simulating players 1 2"}
           (first (status/lines {:online? false :simulating? true :visible #{2 1} :panels []})))))
  (testing "panel states"
    (let [[head ok sub missing empty-panel]
          (status/lines {:online? true :simulating? false :visible #{1 2 3}
                         :panels  [{:index 0 :wanted "\\Display2" :actual "\\Display2" :reason :exact
                                    :open? true :players [1 2]}
                                   {:index 1 :wanted "\\Display3" :actual "\\Display4" :reason :same-size
                                    :open? true :players [3 4]}
                                   {:index 2 :wanted "\\Display5" :actual nil :reason :not-found
                                    :open? false :players [5]}
                                   {:index 3 :wanted "Window" :actual "Window" :reason :no-players
                                    :open? false :players []}]})]
      (is (= {:level :ok :text "BLT online (3 players)"} head))
      (is (= {:level :ok :text "Panel 1  \\Display2  running  Player 1 \u25cf  2 \u25cf"} ok))
      (is (= {:level :warn
              :text  "Panel 2  \\Display3 \u2192 \\Display4 (substitute)  running  Player 3 \u25cf  4 \u25cb offline"}
             sub))
      (is (= {:level :error :text "Panel 3  \\Display5  NOT FOUND \u2013 panel not opened"} missing))
      (is (= {:level :error :text "Panel 4  Window  no players set \u2013 panel not opened"} empty-panel)))))

(deftest html-test
  (is (= (str "<html><span style='color:#2e9e44'>a &lt;b&gt; &amp;</span><br>"
              "<span style='color:#d0302a'>c</span></html>")
         (status/html [{:level :ok :text "a <b> &"} {:level :error :text "c"}]))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.status-test`
Expected: FAIL, cannot locate `bardisplay/status.clj`.

- [ ] **Step 3: Write `src/bardisplay/status.clj`**

```clojure
(ns bardisplay.status
  "Turns what the displays are doing into a few lines of text, for the
  show window and the settings window. Pure, so it can be tested."
  (:require [clojure.string :as str]))

(def ^:private level-colors
  {:ok "#2e9e44" :warn "#c98a00" :error "#d0302a"})

(defn- header
  "The first line: where the player data comes from."
  [{:keys [online? simulating? visible]}]
  (cond
    simulating? {:level :warn :text (str "Simulating players " (str/join " " (sort visible)))}
    online?     {:level :ok :text (str "BLT online (" (count visible) " players)")}
    :else       {:level :warn :text "BLT offline \u2013 displays open when it goes online"}))

(defn- player-text
  "Lists a panel's players, marking the ones not on the network."
  [visible players]
  (str "Player " (str/join "  " (map #(str % (if (contains? visible %) " \u25cf" " \u25cb offline"))
                                     players))))

(defn- panel-line
  "Describes one panel's status entry from `bardisplay.core/status`."
  [visible {:keys [index wanted actual reason open? players]}]
  (let [prefix (str "Panel " (inc index) "  ")]
    (case reason
      :not-found  {:level :error :text (str prefix wanted "  NOT FOUND \u2013 panel not opened")}
      :no-players {:level :error :text (str prefix wanted "  no players set \u2013 panel not opened")}
      (let [substitute? (contains? #{:same-size :widest} reason)
            where       (if substitute? (str wanted " \u2192 " actual " (substitute)") wanted)
            missing?    (not-every? #(contains? visible %) players)]
        (if open?
          {:level (if (or substitute? missing?) :warn :ok)
           :text  (str prefix where "  running  " (player-text visible players))}
          {:level :error :text (str prefix where "  could not be opened")})))))

(defn lines
  "Builds the status lines. `state` holds `:online?`, `:simulating?`,
  `:visible` (set of player numbers present) and `:panels` (the entries
  from `bardisplay.core/status`)."
  [{:keys [panels visible] :as state}]
  (into [(header state)]
        (if (seq panels)
          (map #(panel-line visible %) panels)
          [{:level :warn :text "Displays closed"}])))

(defn- escape
  "Escapes text for use inside Swing HTML."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn html
  "Renders status lines as HTML for a Swing label, colored by level."
  [lines]
  (str "<html>"
       (str/join "<br>" (map (fn [{:keys [level text]}]
                               (str "<span style='color:" (level-colors level) "'>" (escape text) "</span>"))
                             lines))
       "</html>"))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.status-test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/status.clj test/bardisplay/status_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Add status lines

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Painters (`bardisplay.paint`)

**Files:**
- Rewrite: `src/bardisplay/paint.clj`
- Create: `test/bardisplay/paint_test.clj`

Note: `track` gains a second argument (`opts`). Its only caller, `cell/create`, is rewritten in Task 6; until then `cell.clj` still calls `paint/track` with three arguments, which only matters at paint time, not at load time, so tests keep passing.

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.paint-test
  (:require [bardisplay.paint :as paint]
            [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is]])
  (:import [java.awt Color]))

(deftest live-test
  (is (paint/live? {:player 1}))
  (is (paint/live? {:player 1 :online? true}))
  (is (not (paint/live? {:player 1 :online? false})))
  (is (not (paint/live? {:player 0}))))

(deftest scroll-offset-test
  (is (= 0 (paint/scroll-offset 2000 100 200)) "text that fits never moves")
  (is (= 0 (paint/scroll-offset 0 300 200)))
  (is (= 50 (paint/scroll-offset 2000 300 200)))
  (is (= 100 (paint/scroll-offset 4500 300 200)) "holds at the end")
  (is (= 0 (paint/scroll-offset 10000 300 200)) "rests at the start")
  (is (= 50 (paint/scroll-offset 32000 300 200)) "repeats every period"))

(deftest scrolling-test
  (is (paint/scrolling? 1000))
  (is (paint/scrolling? 5000))
  (is (not (paint/scrolling? 6000))))

(deftest scroll-time-test
  (is (= 1000 (paint/scroll-time 1000 0)))
  (is (= 15000 (paint/scroll-time 1000 2))))

(deftest format-countdown-test
  (is (= "0:12" (paint/format-countdown 12500)))
  (is (= "2:05" (paint/format-countdown 125000)))
  (is (= "0:00" (paint/format-countdown 0)))
  (is (= "0:00" (paint/format-countdown -5))))

(deftest info-content-test
  (let [snap {:player   1 :online? true
              :phrase   {:label "Chorus" :color Color/RED :text-color Color/BLACK :beats-left 12}
              :key      "Am"
              :looping  true :loop-beats 4
              :next-cue {:ms-left 12000 :color Color/GREEN}}]
    (is (= {:text "CHORUS \u00b7 12" :fill Color/RED :text-color Color/BLACK}
           (paint/info-content :phrase snap)))
    (is (= {:text "KEY Am" :text-color (theme/color :value)} (paint/info-content :key snap)))
    (is (= {:text "LOOP 4" :fill (theme/color :loop) :text-color Color/BLACK}
           (paint/info-content :loop snap)))
    (is (= {:text "CUE 0:12" :text-color (theme/color :value) :dot Color/GREEN}
           (paint/info-content :cue snap)))
    (is (= {:text "LOOP 4" :fill (theme/color :loop) :text-color Color/BLACK}
           (paint/info-content :loop (assoc snap :loop-beats 4))))
    (is (= {:text "LOOP" :fill (theme/color :loop) :text-color Color/BLACK}
           (paint/info-content :loop (assoc snap :loop-beats 0))))
    (is (= {:text "LOOP"} (paint/info-content :loop (assoc snap :looping false))))
    (is (= {:text "LOOP \u2013"} (paint/info-content :loop (dissoc snap :looping)))))
  (is (= {:text "PHRASE \u2013"} (paint/info-content :phrase {:player 1})))
  (is (= {:text "KEY \u2013"} (paint/info-content :key nil)))
  (is (= {:text "CUE \u2013"} (paint/info-content :cue {}))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.paint-test`
Expected: FAIL, `live?` (and others) unresolved.

- [ ] **Step 3: Rewrite `src/bardisplay/paint.clj`**

```clojure
(ns bardisplay.paint
  "Resolution independent painters for the pieces of a player cell.

  Beat Link Trigger's own Player Status painters draw at hard coded
  pixel coordinates, so they cannot be reused on panels of a different
  size. These equivalents take a scale factor and lay everything out in
  design units (see `bardisplay.theme`)."
  (:require [bardisplay.theme :as theme]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Font Graphics2D]
           [java.awt.geom Ellipse2D$Double Rectangle2D$Double RoundRectangle2D$Double]
           [javax.swing JComponent]))

(defn- u
  "Shorthand for scaling a design unit value."
  ^long [^double s v]
  (theme/su s v))

(defn- text-width
  "Measures a string in the graphics context's current font."
  ^long [^Graphics2D g ^String s]
  (.stringWidth (.getFontMetrics g) (or s "")))

(defn fit
  "Truncates a string with an ellipsis until it fits within `max-w`
  pixels in the graphics context's current font."
  ^String [^Graphics2D g ^String s max-w]
  (let [s (or s "")]
    (if (<= (text-width g s) max-w)
      s
      (loop [n (count s)]
        (if (<= n 1)
          "\u2026"
          (let [candidate (str (str/trimr (subs s 0 (dec n))) "\u2026")]
            (if (<= (text-width g candidate) max-w)
              candidate
              (recur (dec n)))))))))

(defn- draw-left
  "Draws a string with its left edge at `x` and its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float x) (float y)))

(defn- draw-right
  "Draws a string with its right edge at `x` and its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float (- x (text-width g s))) (float y)))

(defn- draw-centered
  "Draws a string centered horizontally on `x`, with its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float (- x (/ (text-width g s) 2.0))) (float y)))

(defn- rounded
  ^RoundRectangle2D$Double [x y w h r]
  (RoundRectangle2D$Double. (double x) (double y) (double w) (double h) (double r) (double r)))

;;; Shared helpers -------------------------------------------------------------

(defn live?
  "Checks whether a snapshot describes a player that is assigned and on
  the network. Snapshots without `:online?` count as online."
  [{:keys [player online?]}]
  (and (pos? (or player 0)) (not (false? online?))))

(def scroll-period
  "How often, in milliseconds, a title too long to fit scrolls through."
  30000)

(def scroll-run
  "How long, in milliseconds, one scroll takes."
  4000)

(def scroll-hold
  "How long, in milliseconds, the end of the text stays visible before it
  jumps back to the start."
  1500)

(defn scroll-time
  "The clock a cell's scrolling runs on. Offset by player number so the
  cells of a panel do not all move at once."
  ^long [now player]
  (+ (long now) (* 7000 (long (or player 0)))))

(defn scrolling?
  "Checks whether an occasional scroll is under way at time `t`."
  [t]
  (< (mod (long t) scroll-period) (+ scroll-run scroll-hold)))

(defn scroll-offset
  "How far, in pixels, text `text-w` wide is shifted left at time `t`
  when only `avail-w` pixels are available."
  ^long [t text-w avail-w]
  (let [overflow (- (long text-w) (long avail-w))
        phase    (mod (long t) scroll-period)]
    (cond
      (<= overflow 0)                     0
      (< phase scroll-run)                (long (* overflow (/ (double phase) scroll-run)))
      (< phase (+ scroll-run scroll-hold)) overflow
      :else                               0)))

(defn format-countdown
  "Formats a duration in milliseconds as `m:ss`."
  ^String [ms]
  (let [ms (max 0 (long ms))]
    (format "%d:%02d" (quot ms 60000) (quot (mod ms 60000) 1000))))

;;; Number formatting ----------------------------------------------------------

(defn- two-digits
  "Formats a number as a two digit string, or `--` if it is missing or
  out of range."
  ^String [v]
  (if v
    (let [v (int v)]
      (if (< -1 v 100) (format "%02d" v) "--"))
    "--"))

(defn- clock-parts
  "Splits a duration in milliseconds into the two digit minute, second
  and frame strings used by the time displays."
  [ms]
  (if ms
    (let [half-frame (mod (org.deepsymmetry.beatlink.Util/timeToHalfFrame ms) 150)]
      [(two-digits (quot ms 60000))
       (two-digits (quot (mod ms 60000) 1000))
       (two-digits (quot half-frame 2))])
    ["--" "--" "--"]))

(defn- pitch-sign
  "Returns the sign to draw in front of a pitch value. The segment font
  has no plus or minus glyph, so it is drawn separately in Teko."
  ^String [pitch]
  (cond
    (nil? pitch)                        " "
    (< (Math/abs (double pitch)) 0.025) " "
    (neg? pitch)                        "-"
    :else                               "+"))

(defn- pitch-digits
  "Formats the magnitude of a pitch percentage, always using a period as
  the decimal separator because that is all the segment font offers."
  ^String [pitch]
  (if pitch
    (let [magnitude (Math/abs (double pitch))]
      (String/format java.util.Locale/ROOT
                     (if (< magnitude 20.0) "%.2f" "%.1f")
                     (to-array [magnitude])))
    "--.--"))

(defn- format-tempo
  "Formats an effective tempo, or a placeholder when none is known."
  ^String [tempo]
  (if tempo
    (String/format java.util.Locale/ROOT "%.1f" (to-array [(double tempo)]))
    "--.-"))

;;; Painters -------------------------------------------------------------------

(defn- draw-clock
  "Draws a time value in the segment font with manually drawn colons,
  since the font has no colon glyph. Returns the total width drawn. The
  value is right aligned on `right`."
  [^Graphics2D g ^double s {:keys [ms size baseline right frames?]}]
  (let [[mm ss ff] (clock-parts ms)
        font       (theme/font :segment Font/PLAIN s size)
        _          (.setFont g font)
        group      (text-width g "00")
        dot        (max 1 (u s (* size 0.09)))
        colon      (* 3 dot)
        total      (+ (* group (if frames? 3 2)) (* colon (if frames? 2 1)))
        x          (- right total)
        dot-high   (- baseline (u s (* size 0.62)))
        dot-low    (- baseline (u s (* size 0.28)))]
    (draw-left g mm x baseline)
    (.fill g (Rectangle2D$Double. (double (+ x group dot)) (double dot-high) (double dot) (double dot)))
    (.fill g (Rectangle2D$Double. (double (+ x group dot)) (double dot-low) (double dot) (double dot)))
    (draw-left g ss (+ x group colon) baseline)
    (when frames?
      (let [x2 (+ x group colon group)]
        (.fill g (Rectangle2D$Double. (double (+ x2 dot)) (double dot-high) (double dot) (double dot)))
        (.fill g (Rectangle2D$Double. (double (+ x2 dot)) (double dot-low) (double dot) (double dot)))
        (draw-left g ff (+ x2 colon) baseline)))
    total))

(defn badge
  "Paints the player number, play state and position within the bar."
  [{:keys [player playing beat] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        active (and (live? snap) playing)
        tint   (if active (theme/color :playing) (theme/color :idle))
        stroke (max 1 (u s 2))]
    (.setStroke g (BasicStroke. stroke))
    (.setPaint g tint)
    (.draw g (rounded (/ stroke 2.0) (/ stroke 2.0) (- w stroke) (- h stroke) (u s 12)))
    (.setFont g (theme/font :teko Font/PLAIN s 16))
    (draw-centered g "PLAYER" (/ w 2.0) (u s 20))
    (.setFont g (theme/font :orbitron Font/BOLD s 44))
    (draw-centered g (if (pos? (or player 0)) (str player) "-") (/ w 2.0) (u s 82))
    ;; The four tick marks along the bottom show the beat within the bar.
    (let [inset  (u s 10)
          gap    (u s 4)
          tick-w (max 1 (quot (- w (* 2 inset) (* 3 gap)) 4))
          tick-y (- h (u s 20))
          tick-h (u s 8)]
      (dotimes [i 4]
        (.setPaint g (cond
                       (not (live? snap))  (theme/color :idle)
                       (not= (inc i) beat) (theme/color :idle)
                       (zero? i)           (theme/color :on-air)
                       :else               (theme/color :value)))
        (.fill g (rounded (+ inset (* i (+ tick-w gap))) tick-y tick-w tick-h (u s 2)))))))

(defn- draw-line
  "Draws one line of the track block: truncated with an ellipsis, or
  shifted left while an occasional scroll is under way."
  [^Graphics2D g ^String text w baseline scroll? t]
  (let [offset (if scroll? (scroll-offset t (text-width g text) w) 0)]
    (if (pos? offset)
      (draw-left g text (- offset) baseline)
      (draw-left g (fit g text w) 0 baseline))))

(defn track
  "Paints the title and artist of the loaded track. `opts` holds
  `:scroll?` (occasional scrolling on) and `:now` (current time in ms)."
  [{:keys [player title artist] :as snap} {:keys [scroll? now]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        active (live? snap)
        t      (scroll-time (or now 0) player)]
    (.setFont g (theme/font :teko Font/BOLD s 44))
    (.setPaint g (if active (theme/color :title) (theme/color :idle)))
    (draw-line g (cond
                   (not (pos? (or player 0))) "No Player"
                   (not active)               "OFFLINE"
                   :else                      (or title "No Track"))
               w (u s 48) scroll? t)
    (.setFont g (theme/font :teko Font/PLAIN s 32))
    (.setPaint g (if (and active artist) (theme/color :artist) (theme/color :idle)))
    (draw-line g (if active (or artist "\u2014") "\u2014") w (u s 92) scroll? t)))

(defn info-content
  "Decides what one entry of the info block shows. Returns `:text`, and
  optionally `:fill` (lit background), `:text-color` and `:dot` (a
  colored marker before the text). `snap` is `nil` when the player is
  not live."
  [item snap]
  (case item
    :phrase (if-let [{:keys [label color text-color beats-left]} (:phrase snap)]
              {:text (str (str/upper-case label) " \u00b7 " beats-left) :fill color :text-color text-color}
              {:text "PHRASE \u2013"})
    :key    (if-let [k (:key snap)]
              {:text (str "KEY " k) :text-color (theme/color :value)}
              {:text "KEY \u2013"})
    :loop   (case (:looping snap)
              true  {:text       (let [beats (:loop-beats snap)]
                                   (if (and beats (pos? beats)) (str "LOOP " beats) "LOOP"))
                     :fill       (theme/color :loop)
                     :text-color Color/BLACK}
              false {:text "LOOP"}
              {:text "LOOP \u2013"})
    :cue    (if-let [{:keys [ms-left color]} (:next-cue snap)]
              {:text (str "CUE " (format-countdown ms-left)) :text-color (theme/color :value) :dot color}
              {:text "CUE \u2013"})))

(defn- info-item
  "Draws one entry of the info block as a pill."
  [^Graphics2D g s {:keys [text fill text-color dot]} x y w h]
  (let [stroke (max 1 (u s 2))
        shape  (rounded x y w h (u s 6))]
    (if fill
      (do (.setPaint g fill)
          (.fill g shape))
      (do (.setStroke g (BasicStroke. stroke))
          (.setPaint g (theme/color :outline))
          (.draw g shape)))
    (.setFont g (theme/font :teko Font/BOLD s 24))
    (let [dot-d    (u s 10)
          dot-gap  (u s 6)
          text     (fit g text (- w (u s 8) (if dot (+ dot-d dot-gap) 0)))
          total    (+ (text-width g text) (if dot (+ dot-d dot-gap) 0))
          left     (+ x (/ (- w total) 2.0))
          baseline (+ y (* h 0.72))]
      (when dot
        (.setPaint g dot)
        (.fill g (Ellipse2D$Double. (double left) (double (- (+ y (/ h 2.0)) (/ dot-d 2.0)))
                                    (double dot-d) (double dot-d))))
      (.setPaint g (or text-color (theme/color :idle)))
      (draw-left g text (if dot (+ left dot-d dot-gap) left) baseline))))

(defn info
  "Paints the info block: up to four entries (`items`, a vector drawn
  from `:phrase :key :loop :cue`) in a two by two grid."
  [snap items ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w    (.getWidth c)
        h    (.getHeight c)
        s    (theme/block-scale h)
        gap  (u s 8)
        cw   (quot (- w gap) 2)
        ch   (quot (- h gap) 2)
        snap (when (live? snap) snap)]
    (doseq [[i item] (map-indexed vector items)]
      (info-item g s (info-content item snap)
                 (* (mod i 2) (+ cw gap)) (* (quot i 2) (+ ch gap)) cw ch))))

(defn- pill
  "Draws one status indicator. It is filled when lit and merely outlined
  when not."
  [^Graphics2D g s ^String label lit? ^Color tint x y w h]
  (let [stroke (max 1 (u s 2))
        shape  (rounded x y w h (u s 6))]
    (if lit?
      (do (.setPaint g tint)
          (.fill g shape)
          (.setPaint g Color/BLACK))
      (do (.setStroke g (BasicStroke. stroke))
          (.setPaint g (theme/color :outline))
          (.draw g shape)
          (.setPaint g (theme/color :idle))))
    (.setFont g (theme/font :teko Font/BOLD s 24))
    (draw-centered g label (+ x (/ w 2.0)) (+ y (* h 0.76)))))

(defn status
  "Paints the on-air, tempo master and sync indicators."
  [{:keys [on-air master synced bpm-only] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        gap    (u s 8)
        pill-h (quot (- h (* 2 gap)) 3)
        active (live? snap)]
    (pill g s "ON AIR" (and active on-air) (theme/color :on-air) 0 0 w pill-h)
    (pill g s "MASTER" (and active master) (theme/color :master) 0 (+ pill-h gap) w pill-h)
    (pill g s (if bpm-only "BPM" "SYNC") (and active synced)
          (if bpm-only (theme/color :bpm-only) (theme/color :sync))
          0 (* 2 (+ pill-h gap)) w pill-h)))

(defn tempo
  "Paints the pitch adjustment and the resulting effective tempo."
  [{:keys [pitch tempo master] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        inset  (u s 2)
        active (live? snap)
        tint   (cond (not active) (theme/color :idle)
                     master       (theme/color :master)
                     :else        (theme/color :value))]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g "TEMPO" inset (u s 20))
    (.setPaint g (if active (theme/color :value) (theme/color :idle)))
    (let [baseline (u s 54)
          digits   (if active (pitch-digits pitch) "--.--")]
      (.setFont g (theme/font :teko Font/BOLD s 26))
      (draw-left g (if active (pitch-sign pitch) " ") inset baseline)
      (.setFont g (theme/font :segment Font/PLAIN s 24))
      (let [x        (+ inset (u s 14))
            ;; Measure in the segment font, before switching to Teko for the unit.
            digits-w (text-width g digits)]
        (draw-left g digits x baseline)
        (.setFont g (theme/font :teko Font/PLAIN s 22))
        (draw-left g "%" (+ x digits-w (u s 8)) baseline)))
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 22))
    (draw-left g "BPM" inset (u s 104))
    (.setPaint g tint)
    (.setFont g (theme/font :segment Font/PLAIN s 44))
    (draw-right g (if active (format-tempo tempo) "--.-") (- w inset) (u s 104))))

(defn- remain-color
  "Picks the color for the remaining time, warning as the track runs
  out."
  ^Color [active remain]
  (let [{:keys [warn urgent]} @theme/thresholds]
    (cond
      (not active)       (theme/color :idle)
      (nil? remain)      (theme/color :idle)
      (<= remain urgent) (theme/color :remain-urgent)
      (<= remain warn)   (theme/color :remain-warn)
      :else              (theme/color :value))))

(defn time-display
  "Paints the elapsed and remaining playback times."
  [{:keys [played remain precise] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w        (.getWidth c)
        h        (.getHeight c)
        s        (theme/block-scale h)
        inset    (u s 2)
        active   (live? snap)
        compact? (< (/ w s) 260)]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g (if precise "PRECISE TIME" "TIME") inset (u s 24))
    (.setPaint g (if active (theme/color :value) (theme/color :idle)))
    (draw-clock g s {:ms       (when active played)
                     :size     24
                     :baseline (u s 28)
                     :right    (- w inset)
                     :frames?  (not compact?)})
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g "REMAIN" inset (u s 62))
    (.setPaint g (remain-color active remain))
    (draw-clock g s {:ms       (when active remain)
                     :size     (if compact? 38 48)
                     :baseline (u s 106)
                     :right    (- w inset)
                     :frames?  false})))
```

- [ ] **Step 4: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/paint.clj test/bardisplay/paint_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Painters: offline state, info block, occasional scrolling

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Cell layout and block toggles (`bardisplay.cell`)

**Files:**
- Rewrite: `src/bardisplay/cell.clj`
- Create: `test/bardisplay/cell_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.cell-test
  (:require [bardisplay.cell :as cell]
            [clojure.test :refer [deftest is testing]]))

(defn- overlap?
  [[ax ay aw ah] [bx by bw bh]]
  (and (< ax (+ bx bw)) (< bx (+ ax aw)) (< ay (+ by bh)) (< by (+ ay ah))))

(def sizes [[1920 440] [960 440] [1500 275] [800 300]])

(def block-sets
  [cell/all-blocks
   #{:track :time :detail}
   #{:track :status :tempo :time :phrase :key :loop :cue}
   #{}
   #{:detail}
   #{:preview}])

(deftest layout-fits-test
  (doseq [[w h] sizes
          blocks block-sets]
    (let [rects (cell/layout w h (cell/layout-blocks blocks))]
      (testing (str w "x" h " " (sort blocks))
        (is (contains? rects :badge))
        (doseq [[k [x y rw rh]] rects]
          (is (and (>= x 0) (>= y 0) (>= rw 0) (>= rh 0)) (str k " has negative geometry"))
          (is (<= (+ x rw) w) (str k " sticks out on the right"))
          (is (<= (+ y rh) h) (str k " sticks out at the bottom")))
        (doseq [[ka a] rects
                [kb b] rects
                :when (neg? (compare ka kb))]
          (is (not (overlap? a b)) (str ka " overlaps " kb)))))))

(deftest layout-blocks-test
  (is (contains? (cell/layout-blocks #{:key}) :info))
  (is (not (contains? (cell/layout-blocks #{:track}) :info))))

(deftest hidden-blocks-test
  (is (= #{:badge :track :time} (set (keys (cell/layout 1920 440 (cell/layout-blocks #{:track :time})))))))

(deftest header-grows-without-waveforms-test
  (let [with    (cell/layout 1920 440 (cell/layout-blocks cell/all-blocks))
        without (cell/layout 1920 440 (cell/layout-blocks #{:track :time}))]
    (is (> (get-in without [:badge 3]) (get-in with [:badge 3])))))

(deftest single-waveform-takes-the-space-test
  (is (> (get-in (cell/layout 1920 440 #{:preview}) [:preview 3]) 200))
  (is (> (get-in (cell/layout 1920 440 #{:detail}) [:detail 3]) 200)))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.cell-test`
Expected: FAIL, `layout` / `layout-blocks` unresolved.

- [ ] **Step 3: Rewrite `src/bardisplay/cell.clj`**

```clojure
(ns bardisplay.cell
  "A single player's region of a bar panel: a header of painted
  indicators above a scrolling waveform detail view and a waveform
  preview acting as the progress bar.

  A cell is pointed at one player with `set-player!` and keeps showing
  that player, online or not. Which blocks appear is chosen when the
  cell is created; `layout` works out where they go."
  (:require [bardisplay.paint :as paint]
            [bardisplay.sim :as sim]
            [bardisplay.state :as state]
            [bardisplay.theme :as theme]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt BorderLayout Dimension]
           [javax.swing JComponent JPanel]
           [org.deepsymmetry.beatlink.data WaveformDetailComponent WaveformPreviewComponent]))

(def tick-interval
  "How often, in milliseconds, we re-examine a player's state. Matches
  the rate Beat Link Trigger's own Player Status window uses."
  20)

(def all-blocks
  "Every display block that can be switched on or off. The player badge
  is always shown and is not listed."
  #{:track :status :tempo :time :detail :preview :phrase :key :loop :cue})

(def info-items
  "The optional entries of the info block, in display order."
  [:phrase :key :loop :cue])

(def header-order
  "The header blocks to the right of the track title, left to right."
  [:info :status :tempo :time])

(def repaint-groups
  "Which snapshot keys each painted component depends on. The animation
  loop only repaints a component when one of its keys has changed."
  {:badge  [:player :online? :playing :beat]
   :track  [:player :online? :title :artist]
   :info   [:player :online? :phrase :key :looping :loop-beats :next-cue]
   :status [:player :online? :on-air :master :synced :bpm-only]
   :tempo  [:player :online? :pitch :tempo :master]
   :time   [:player :online? :played :remain :precise]})

(defn layout-blocks
  "Turns the chosen display blocks into the set `layout` works with,
  adding `:info` when any info entry is switched on."
  [blocks]
  (let [blocks (set blocks)]
    (cond-> blocks
      (some blocks info-items) (conj :info))))

(defn layout
  "Works out where each visible block of a `w` by `h` cell goes. `blocks`
  is the result of `layout-blocks`. Returns a map from block key to
  `[x y width height]`; blocks not shown are absent. The badge is always
  present.

  Everything is derived from the cell's height, so the layout is the
  same on panels of any resolution. Header blocks keep their widths
  relative to the header height; when both waveforms are hidden the
  header grows to fill the cell, as far as the width allows."
  [w h blocks]
  (let [metrics      theme/metrics
        s            (theme/scale h)
        m            (theme/su s (:margin metrics))
        gap          (theme/su s (:gap metrics))
        compact?     (< (/ (double w) s) (:compact-below metrics))
        design-w     (fn [k] (get-in metrics [k (if compact? :compact :full)]))
        right        (filterv blocks header-order)
        detail?      (contains? blocks :detail)
        preview?     (contains? blocks :preview)
        waves?       (or detail? preview?)
        natural      (theme/su s (:header metrics))
        avail-h      (- h (* 2 m))
        header-h     (if waves?
                       natural
                       (let [fixed-du (+ (design-w :badge) (reduce + 0 (map design-w right)))
                             gaps     (* gap (inc (count right)))
                             room     (- w (* 2 m) gaps (theme/su s (:min-track metrics)))
                             widest   (long (/ (* room (:header metrics)) fixed-du))]
                         (max natural (min avail-h widest))))
        hs           (/ (double header-h) (:header metrics))
        block-w      (fn [k] (theme/su hs (design-w k)))
        top          (if waves? m (+ m (quot (- avail-h header-h) 2)))
        [placed edge] (reduce (fn [[acc x] k]
                                (let [bw (block-w k)
                                      bx (- x bw)]
                                  [(assoc acc k [bx top bw header-h]) (- bx gap)]))
                              [{} (- w m)]
                              (reverse right))
        badge-w      (block-w :badge)
        track-x      (+ m badge-w gap)
        track-w      (max 0 (- edge track-x))
        inner-w      (- w (* 2 m))
        wave-y       (+ m header-h gap)
        wave-h       (max 0 (- h wave-y m))
        preview-h    (theme/su s (:preview metrics))
        waves        (cond
                       (and detail? preview?)
                       (let [detail-h (max 0 (- wave-h preview-h gap))]
                         {:detail  [m wave-y inner-w detail-h]
                          :preview [m (+ wave-y detail-h gap) inner-w (min preview-h wave-h)]})
                       detail?  {:detail [m wave-y inner-w wave-h]}
                       preview? {:preview [m wave-y inner-w wave-h]}
                       :else    {})]
    (merge {:badge [m top badge-w header-h]}
           (when (contains? blocks :track) {:track [track-x top track-w header-h]})
           placed
           waves)))

(defn- style-waveform!
  "Applies our color scheme to a waveform component."
  [component]
  (doto component
    (.setBackgroundColor (theme/color :background))
    (.setIndicatorColor (theme/color :wave-tick))
    (.setEmphasisColor (theme/color :wave-emphasis))))

(defn- holder
  "Builds the container a waveform component lives in. Going through a
  container lets the simulation swap components out, which it has to do
  because data mode components are bound to one track."
  ^JPanel []
  (doto (JPanel. (BorderLayout.))
    (.setOpaque true)
    (.setBackground (theme/color :background))))

(defn- live-waveforms
  "Waveform components in auto-tracking mode, which subscribe to the
  Beat Link finders themselves and repaint as the player moves."
  [^JPanel detail-holder ^JPanel preview-holder scale]
  (let [detail  (style-waveform! (WaveformDetailComponent. (int 0)))
        preview (style-waveform! (WaveformPreviewComponent. (int 0)))]
    (.setScale ^WaveformDetailComponent detail (int scale))
    (.add detail-holder ^JComponent detail BorderLayout/CENTER)
    (.add preview-holder ^JComponent preview BorderLayout/CENTER)
    {:attach!  (fn [n]
                 (.setMonitoredPlayer ^WaveformDetailComponent detail (int n))
                 (.setMonitoredPlayer ^WaveformPreviewComponent preview (int n)))
     :advance! (fn [_ _ _])
     :release! (fn []
                 (.setMonitoredPlayer ^WaveformDetailComponent detail (int 0))
                 (.setMonitoredPlayer ^WaveformPreviewComponent preview (int 0)))}))

(defn- simulated-waveforms
  "Waveform components in data mode, rebuilt whenever the cell is pointed
  at a different simulated track and driven by `advance!`."
  [^JPanel detail-holder ^JPanel preview-holder scale]
  (let [current (atom nil)]
    {:attach!  (fn [n]
                 (.removeAll detail-holder)
                 (.removeAll preview-holder)
                 (reset! current
                         (when-let [track (sim/track n)]
                           (let [detail  (style-waveform!
                                           (WaveformDetailComponent. (:detail track) (:cue-list track)
                                                                     (:grid track)))
                                 preview (style-waveform!
                                           (WaveformPreviewComponent. (:preview track)
                                                                      (get-in track [:metadata :duration])
                                                                      (:cue-list track)))]
                             (.setScale ^WaveformDetailComponent detail (int scale))
                             (.setSongStructure ^WaveformDetailComponent detail (:song-structure track))
                             (.setBeatGrid ^WaveformPreviewComponent preview (:grid track))
                             (.setSongStructure ^WaveformPreviewComponent preview (:song-structure track))
                             (.add detail-holder ^JComponent detail BorderLayout/CENTER)
                             (.add preview-holder ^JComponent preview BorderLayout/CENTER)
                             {:detail detail :preview preview})))
                 (doto detail-holder (.revalidate) (.repaint))
                 (doto preview-holder (.revalidate) (.repaint)))
     :advance! (fn [n played playing]
                 (when-let [{:keys [detail preview]} @current]
                   (when played
                     (.setPlaybackState ^WaveformDetailComponent detail
                                        (int n) (long played) (boolean playing))
                     (.setPlaybackState ^WaveformPreviewComponent preview
                                        (int n) (long played) (boolean playing)))))
     :release! (fn [])}))

(defn- lay-out!
  "Positions the children of a cell according to `layout`, hiding the
  blocks that are switched off."
  [^JPanel panel children blocks]
  (let [rects (layout (.getWidth panel) (.getHeight panel) blocks)]
    (doseq [[k ^JComponent child] children]
      (if-let [[x y w h] (get rects k)]
        (doto child
          (.setVisible true)
          (.setBounds (int x) (int y) (int w) (int h)))
        (.setVisible child false)))))

(defn- canvas
  "Creates one of the painted header components. `paint-fn` receives the
  current snapshot, the component and the graphics context."
  [paint-fn snapshot]
  (seesaw/canvas :background (theme/color :background)
                 :opaque? true
                 :paint (fn [c g] (paint-fn @snapshot c g))))

(defn create
  "Builds a cell. `shutdown-chan` is closed when the display is torn
  down, which stops the animation loop and releases the waveform
  components. `options` holds `:scale` (waveform detail zoom; larger
  values show more of the track), `:blocks` (which blocks to show, see
  `all-blocks`) and `:title-scroll` (`:off` or `:occasional`).

  Returns a map containing the Swing `:panel`, the `:snapshot` atom and
  a `:set-player!` function."
  [shutdown-chan {:keys [scale blocks title-scroll]}]
  (let [scale    (or scale 4)
        chosen   (set (or blocks all-blocks))
        items    (filterv chosen info-items)
        blocks   (layout-blocks chosen)
        scroll?  (= title-scroll :occasional)
        ;; The player number is owned by `set-player!`; the snapshot is owned by the
        ;; animation loop. Keeping them apart stops a tick in flight from reverting
        ;; a reassignment that happened while it was gathering state.
        player   (atom 0)
        snapshot (atom state/empty-snapshot)
        moving   (atom false)
        detail   (holder)
        preview  (holder)
        children {:badge   (canvas paint/badge snapshot)
                  :track   (canvas (fn [snap c g]
                                     (paint/track snap {:scroll? scroll? :now (System/currentTimeMillis)} c g))
                                   snapshot)
                  :info    (canvas (fn [snap c g] (paint/info snap items c g)) snapshot)
                  :status  (canvas paint/status snapshot)
                  :tempo   (canvas paint/tempo snapshot)
                  :time    (canvas paint/time-display snapshot)
                  :detail  detail
                  :preview preview}
        waves    (if (sim/active?)
                   (simulated-waveforms detail preview scale)
                   (live-waveforms detail preview scale))
        panel    (proxy [JPanel] []
                   (doLayout []
                     (lay-out! ^JPanel this children blocks)))]
    (doto panel
      (.setLayout nil)
      (.setOpaque true)
      (.setBackground (theme/color :background))
      (.setMinimumSize (Dimension. 320 160)))
    (doseq [child (vals children)]
      (.add panel ^JComponent child))

    (async/go-loop []
      (let [timeout     (async/timeout tick-interval)
            [_ channel] (async/alts! [shutdown-chan timeout])]
        (if (= channel timeout)
          (do
            (try
              (let [current   (state/snapshot @player)
                    previous  (deref snapshot)
                    changed   (vec (keep (fn [[component ks]]
                                           (when (some #(not= (get previous %) (get current %)) ks)
                                             component))
                                         repaint-groups))
                    scrolling (and scroll?
                                   (paint/scrolling? (paint/scroll-time (System/currentTimeMillis)
                                                                        (:player current))))
                    ;; Keep repainting the title while it scrolls, and once more when it stops.
                    stale     (cond-> changed (or scrolling @moving) (conj :track))]
                (reset! moving scrolling)
                (when (seq changed)
                  (reset! snapshot current))
                (seesaw/invoke-later
                  ((:advance! waves) (:player current) (:played current) (:playing current))
                  (when (seq stale)
                    (seesaw/repaint! (mapv children (distinct stale))))))
              (catch Throwable t
                (timbre/error t "Problem updating bar display cell.")))
            (recur))
          (seesaw/invoke-later ((:release! waves))))))

    {:panel       panel
     :snapshot    snapshot
     :set-player! (fn [n]
                    (let [n (long (or n 0))]
                      (when (not= n @player)
                        (reset! player n)
                        (seesaw/invoke-later
                          ((:attach! waves) n)
                          (seesaw/repaint! panel)))))}))
```

- [ ] **Step 4: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.` If an overlap or overflow fails, print the rects for that size with `(cell/layout w h (cell/layout-blocks blocks))` in a REPL and fix the arithmetic in `layout`, not the test.

- [ ] **Step 5: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/cell.clj test/bardisplay/cell_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Cell: pure layout, switchable blocks, info block, scroll repaint

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Snapshot data (`bardisplay.sim`, `bardisplay.state`)

**Files:**
- Rewrite: `src/bardisplay/sim.clj`, `src/bardisplay/state.clj`
- Create: `test/bardisplay/sim_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.sim-test
  (:require [bardisplay.sim :as sim]
            [bardisplay.state :as state]
            [clojure.test :refer [deftest is]]))

(deftest snapshot-test
  (sim/start! {:players [1 2 3]})
  (try
    (let [a (sim/snapshot 1)
          b (sim/snapshot 2)
          c (sim/snapshot 3)]
      (is (true? (:online? a)))
      (is (string? (:key a)))
      (is (every? #(contains? a %) [:phrase :next-cue :looping :loop-beats]))
      (is (false? (:looping a)))
      (is (true? (:looping b)))
      (is (nil? (:looping c)) "player 3 pretends it cannot report loops")
      (let [moving [:played :remain :beat :phrase :next-cue]]
        (is (= (apply dissoc (state/snapshot 2) moving) (apply dissoc b moving))
            "state/snapshot passes simulated players through")))
    (finally
      (sim/stop!))))

(deftest unassigned-snapshot-test
  (is (= {:player 0} (state/snapshot 0))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.sim-test`
Expected: FAIL on `(:online? a)` / `(:key a)` / `:looping`.

- [ ] **Step 3: Rewrite `src/bardisplay/sim.clj`**

```clojure
(ns bardisplay.sim
  "A self-contained fake player network, so the bar displays can be built
  and rehearsed without any CDJs on the desk.

  Beat Link Trigger's own shallow playback simulators cannot drive these
  displays: they hand synthetic packets straight to the trigger and show
  listeners and never reach the MetadataFinder, TimeFinder or
  WaveformFinder that a live cell reads. So we keep our own clock here,
  and `bardisplay.cell` switches its waveform components into data mode
  while a simulation is running."
  (:require [bardisplay.analysis :as analysis]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data BeatGrid]))

(def tick-interval
  "How often, in milliseconds, the simulated clock advances."
  20)

(def sample-tracks
  "The sample tracks Beat Link Trigger ships for offline work. Track 2
  has phrase analysis, track 1 does not, so both cases are shown."
  [1 2])

(defonce ^{:private true
           :doc "Holds the running simulation, or `nil` when stopped."}
  simulation (atom nil))

(defn active?
  "Checks whether a simulation is currently running."
  []
  (some? @simulation))

(defn- duration-ms
  "Returns the length of a simulated track in milliseconds."
  ^long [track]
  (* 1000 (long (get-in track [:metadata :duration] 0))))

(defn- make-player
  "Builds the initial state of one simulated player. The players are
  given different pitches, roles and loop states so the display has
  something varied to show."
  [number track index]
  {:player  number
   :track   track
   :phrases (analysis/phrases (:song-structure track))
   :cues    (analysis/cues (:cue-list track))
   :time    (* index 37000)
   :playing (even? index)
   :pitch   (nth [0.0 1.4 -2.2 0.8] (mod index 4))
   :on-air  (even? index)
   :master  (zero? index)
   :synced  (pos? index)
   ;; false: can report loops but is not looping; true: looping; nil: cannot report.
   :looping (nth [false true nil] (mod index 3))})

(defn- advance
  "Moves one player's playback position forward, wrapping at the end of
  the track."
  [{:keys [playing pitch time track] :as player} elapsed-ms]
  (if-not playing
    player
    (let [total (duration-ms track)
          moved (+ time (* elapsed-ms (+ 1.0 (/ pitch 100.0))))]
      (assoc player :time (if (and (pos? total) (>= moved total))
                            (- moved total)
                            moved)))))

(defn stop!
  "Ends the simulation."
  []
  (when-let [running (first (reset-vals! simulation nil))]
    (async/close! (:shutdown running))
    true))

(defn start!
  "Starts simulated playback on the given player numbers, cycling through
  the sample tracks. Call this before opening the displays."
  ([]
   (start! {}))
  ([{:keys [players] :or {players [1 2 3 4]}}]
   (stop!)
   (let [tracks   (mapv #(util/data-for-simulation :entry %) sample-tracks)
         shutdown (async/promise-chan)
         built    (into {} (map-indexed (fn [index number]
                                          [number (make-player number
                                                               (nth tracks (mod index (count tracks)))
                                                               index)])
                                        players))]
     (reset! simulation {:players built :shutdown shutdown})
     (async/go-loop [previous (System/nanoTime)]
       (let [timeout     (async/timeout tick-interval)
             [_ channel] (async/alts! [shutdown timeout])]
         (when (= channel timeout)
           (let [now     (System/nanoTime)
                 elapsed (/ (- now previous) 1e6)]
             (try
               (swap! simulation update :players update-vals #(advance % elapsed))
               (catch Throwable t
                 (timbre/error t "Problem advancing the bar display simulation.")))
             (recur now)))))
     (timbre/info "Bar display: simulating players" (pr-str players))
     (vec (sort (keys built))))))

(defn player-numbers
  "Returns the simulated player numbers, or an empty sequence when no
  simulation is running."
  []
  (sort (keys (:players @simulation))))

(defn track
  "Returns the simulated track data loaded on a player, which
  `bardisplay.cell` needs to build its waveform components."
  [number]
  (get-in @simulation [:players number :track]))

(defn- beat-number
  "Works out the beat number at a position in a simulated track."
  [track time]
  (when-let [^BeatGrid grid (:grid track)]
    (let [beat (.findBeatAtTime grid (long time))]
      (when (pos? beat)
        beat))))

(defn snapshot
  "Returns the state of a simulated player in the same shape as
  `bardisplay.state/snapshot`, or `nil` if that player is not being
  simulated."
  [number]
  (when-let [{:keys [track time playing pitch on-air master synced looping phrases cues]}
             (get-in @simulation [:players number])]
    (let [metadata (:metadata track)
          played   (long time)
          beat     (beat-number track played)]
      {:player     number
       :online?    true
       :playing    playing
       :on-air     on-air
       :master     master
       :synced     synced
       :bpm-only   false
       :beat       (when beat (.getBeatWithinBar ^BeatGrid (:grid track) (int beat)))
       :pitch      pitch
       :tempo      (when-let [tempo (:starting-tempo metadata)]
                     (* tempo (+ 1.0 (/ pitch 100.0))))
       :played     played
       :remain     (max 0 (- (duration-ms track) played))
       :precise    true
       :title      (:title metadata)
       :artist     (:artist metadata)
       :key        (:key metadata)
       :phrase     (analysis/phrase-at phrases beat)
       :looping    looping
       :loop-beats (when looping 4)
       :next-cue   (analysis/next-cue cues played)})))
```

- [ ] **Step 4: Rewrite `src/bardisplay/state.clj`**

```clojure
(ns bardisplay.state
  "Gathers everything the bar displays need to know about a player into
  a single immutable snapshot, so the painters never have to talk to
  Beat Link directly and the animation loop can cheaply detect which
  parts of a cell actually need repainting."
  (:require [bardisplay.analysis :as analysis]
            [bardisplay.sim :as sim]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.simulator :as simulator]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink CdjStatus DeviceFinder DeviceUpdate Util VirtualCdj]
           [org.deepsymmetry.beatlink.data AnalysisTagFinder MetadataFinder SearchableItem TimeFinder
            TrackMetadata TrackPositionUpdate WaveformDetail WaveformFinder]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$TaggedSection]))

(def ^VirtualCdj virtual-cdj (VirtualCdj/getInstance))
(def ^DeviceFinder device-finder (DeviceFinder/getInstance))
(def ^TimeFinder time-finder (TimeFinder/getInstance))
(def ^MetadataFinder metadata-finder (MetadataFinder/getInstance))
(def ^WaveformFinder waveform-finder (WaveformFinder/getInstance))
(def ^AnalysisTagFinder analysis-finder (AnalysisTagFinder/getInstance))

(defn- online?
  "Checks whether a player is currently announcing itself on the network."
  [n]
  (and (.isRunning device-finder)
       (some? (.getLatestAnnouncementFrom device-finder (int n)))))

(defn- latest-status
  "Returns the most recent status packet from a player, or `nil` if we
  have none. Absorbs the exceptions thrown while going offline."
  ^CdjStatus [n]
  (when (and (pos? n) (.isRunning virtual-cdj))
    (try
      (.getLatestStatusFor virtual-cdj (int n))
      (catch Exception _))))

(defn- time-left
  "Returns the number of milliseconds left to play on a player, given
  the time it has already played."
  [n played]
  (when played
    (when-let [^WaveformDetail detail (or (get-in (simulator/for-player n) [:track :detail])
                                          (when (.isRunning waveform-finder)
                                            (.getLatestDetailFor waveform-finder (int n))))]
      (max 0 (- (.getTotalTime detail) played)))))

(defn- current-beat
  "Determines the beat within the musical bar that a player is on, if
  known. Falls back to the status packet, because CDJ-3000s report a
  position even when no beat grid is available."
  [n]
  (let [precise (when (.isRunning time-finder)
                  (when-let [^TrackPositionUpdate update (.getLatestPositionFor time-finder (int n))]
                    (.getBeatWithinBar update)))
        beat    (if (and precise (pos? precise))
                  precise
                  (when-let [^CdjStatus status (latest-status n)]
                    (.getBeatWithinBar status)))]
    (when (and beat (<= 1 beat 4))
      beat)))

(defn- beat-number
  "Returns the beat number a player is on within its track, if known."
  [n]
  (when (.isRunning time-finder)
    (when-let [^TrackPositionUpdate update (.getLatestPositionFor time-finder (int n))]
      (let [beat (.-beatNumber update)]
        (when (pos? beat)
          beat)))))

(defn- tempo-values
  "Returns the playback pitch percentage and effective tempo of a
  player, or `nil` if we have no update from it."
  [n]
  (when-let [^DeviceUpdate update (when (.isRunning time-finder) (.getLatestUpdateFor time-finder (int n)))]
    ;; A BPM of 65535 is how players report that the tempo is not currently valid.
    [(Util/pitchToPercentage (.getPitch update))
     (when (not= 65535 (.getBpm update)) (.getEffectiveTempo update))]))

(defonce ^{:private true
           :doc "Converted analysis per kind and player, together with the Beat Link object
  it came from, so it is only converted again when the track changes."}
  converted
  (atom {}))

(defn- cached
  "Returns `(convert source)`, reusing the previous result for this kind
  and player while `source` is the same object."
  [kind n source convert]
  (when source
    (let [[previous value] (get-in @converted [kind n])]
      (if (identical? previous source)
        value
        (let [value (convert source)]
          (swap! converted assoc-in [kind n] [source value])
          value)))))

(defn- track-info
  "Returns the title, artist, key and cues of the track loaded on a
  player."
  [n]
  (when-let [^TrackMetadata metadata (try
                                       (when (.isRunning metadata-finder)
                                         (.getLatestMetadataFor metadata-finder (int n)))
                                       (catch Exception e
                                         (timbre/debug e "Unable to read metadata for player" n)))]
    {:title  (.getTitle metadata)
     :artist (when-let [^SearchableItem artist (.getArtist metadata)] (.label artist))
     :key    (when-let [^SearchableItem k (.getKey metadata)] (.label k))
     :cues   (cached :cues n (.getCueList metadata) analysis/cues)}))

(defn- phrases
  "Returns the phrase analysis of the track loaded on a player."
  [n]
  (when (.isRunning analysis-finder)
    (when-let [^RekordboxAnlz$TaggedSection tagged
               (.getLatestTrackAnalysisFor analysis-finder (int n) ".EXT" "PSSI")]
      (cached :phrases n (.body tagged) analysis/phrases))))

(def empty-snapshot
  "The snapshot describing a cell that is not currently showing any
  player."
  {:player 0})

(defn- live-snapshot
  "Collects the current state of a real player on the network."
  [n]
  (if-not (online? n)
    {:player n :online? false}
    (let [^CdjStatus status (latest-status n)
          raw-played        (players/time-played n)
          played            (when (and raw-played (>= raw-played 0)) raw-played)
          [pitch tempo]     (tempo-values n)
          info              (track-info n)]
      {:player     n
       :online?    true
       :playing    (boolean (when status (.isPlaying status)))
       :on-air     (boolean (when status (.isOnAir status)))
       :master     (boolean (when status (.isTempoMaster status)))
       :synced     (boolean (when status (.isSynced status)))
       :bpm-only   (boolean (when status (.isBpmOnlySynced status)))
       :beat       (current-beat n)
       :pitch      pitch
       :tempo      tempo
       :played     played
       :remain     (time-left n played)
       :precise    (boolean (players/precise? n))
       :title      (:title info)
       :artist     (:artist info)
       :key        (:key info)
       :phrase     (analysis/phrase-at (phrases n) (beat-number n))
       ;; nil when the player cannot tell us (anything before the CDJ-3000).
       :looping    (when (and status (.canReportLooping status)) (.isLooping status))
       :loop-beats (when status (.getActiveLoopBeats status))
       :next-cue   (analysis/next-cue (:cues info) played)})))

(defn snapshot
  "Collects the current state of a player into a map. Passing player
  number 0 yields the idle snapshot."
  [n]
  (cond
    (not (pos? n)) empty-snapshot
    (sim/active?)  (or (sim/snapshot n) {:player n :online? false})
    :else          (live-snapshot n)))
```

- [ ] **Step 5: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.` (The equality assertion ignores the keys that change between two reads a few ms apart.)

- [ ] **Step 6: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/sim.clj src/bardisplay/state.clj test/bardisplay/sim_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Snapshots: online flag, phrase, key, loop, next cue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Screens and panel lifecycle (`bardisplay.screen`, `bardisplay.core`)

**Files:**
- Rewrite: `src/bardisplay/screen.clj`, `src/bardisplay/core.clj`
- Create: `test/bardisplay/screen_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns bardisplay.screen-test
  (:require [bardisplay.screen :as screen]
            [clojure.test :refer [deftest is testing]]))

(def screens
  [{:id "A" :width 2560 :height 1440 :x 0 :y 0 :primary? true}
   {:id "B" :width 1920 :height 440 :x 2560 :y 0 :primary? false}
   {:id "C" :width 1920 :height 440 :x 4480 :y 0 :primary? false}
   {:id "D" :width 1920 :height 720 :x 6400 :y 0 :primary? false}])

(deftest resolve-screen-test
  (is (= {:screen :window :reason :window} (screen/resolve-screen :window screens #{})))
  (is (= {:screen (screens 1) :reason :exact}
         (screen/resolve-screen {:id "B" :width 1920 :height 440} screens #{})))
  (testing "same size, closest position"
    (is (= {:screen (screens 2) :reason :same-size}
           (screen/resolve-screen {:id "Z" :width 1920 :height 440 :x 4400 :y 0} screens #{}))))
  (testing "a taken screen is skipped"
    (is (= {:screen (screens 2) :reason :same-size}
           (screen/resolve-screen {:id "B" :width 1920 :height 440 :x 2560 :y 0} screens #{"B"}))))
  (testing "widest bar shaped, never the primary"
    (is (= {:screen (screens 1) :reason :widest}
           (screen/resolve-screen {:id "Z" :width 3840 :height 600} screens #{})))
    (is (= {:screen (screens 1) :reason :widest}
           (screen/resolve-screen {:id "Z"} screens #{}))))
  (testing "nothing left"
    (is (= {:screen nil :reason :not-found}
           (screen/resolve-screen {:id "Z"} screens #{"B" "C" "D"})))))

(deftest assign-screens-test
  (let [wanted        {:id "B" :width 1920 :height 440 :x 2560 :y 0}
        [a b c]       (screen/assign-screens [{:screen wanted} {:screen wanted} {:screen :window}] screens)]
    (is (= ["B" :exact] [(:id (:screen a)) (:reason a)]))
    (is (= ["C" :same-size] [(:id (:screen b)) (:reason b)]))
    (is (= :window (:screen c)))
    (is (= {:screen :window} (:panel c)))))

(deftest dim-alpha-test
  (is (zero? (screen/dim-alpha 100)))
  (is (zero? (screen/dim-alpha nil)))
  (is (< 0.49 (screen/dim-alpha 50) 0.51))
  (is (< 0.79 (screen/dim-alpha 5) 0.81) "clamped to 20 % brightness"))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat bardisplay.screen-test`
Expected: FAIL, `resolve-screen` unresolved.

- [ ] **Step 3: Rewrite `src/bardisplay/screen.clj`**

```clojure
(ns bardisplay.screen
  "Finds the screen each panel should use, and creates the window that
  fills it, divided between one or more player cells."
  (:require [bardisplay.cell :as cell]
            [bardisplay.theme :as theme]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Cursor Graphics GraphicsDevice GraphicsEnvironment GridLayout Point Rectangle
            Toolkit]
           [java.awt.image BufferedImage]
           [javax.swing JComponent JFrame JPanel KeyStroke]))

(def bar-panel-aspect
  "A screen at least this much wider than it is tall is taken to be a bar
  panel when looking for a substitute."
  2.5)

(defn screen-devices
  "Returns the screens attached to this machine, in the order the
  graphics environment reports them."
  []
  (vec (.. GraphicsEnvironment getLocalGraphicsEnvironment getScreenDevices)))

(defn describe-screens
  "Returns a description of every attached screen, to help work out
  which identifier belongs to which bar panel."
  []
  (let [primary (.getIDstring (.getDefaultScreenDevice (GraphicsEnvironment/getLocalGraphicsEnvironment)))]
    (vec (map-indexed (fn [index ^GraphicsDevice device]
                        (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
                          {:index    index
                           :id       (.getIDstring device)
                           :x        (.-x bounds)
                           :y        (.-y bounds)
                           :width    (.-width bounds)
                           :height   (.-height bounds)
                           :primary? (= primary (.getIDstring device))}))
                      (screen-devices)))))

(defn find-screen
  "Resolves a screen identifier, which may be an integer index or the
  platform's ID string, to a graphics device."
  ^GraphicsDevice [screen]
  (let [devices (screen-devices)]
    (cond
      (integer? screen) (get devices screen)
      (string? screen)  (first (filter #(= screen (.getIDstring ^GraphicsDevice %)) devices))
      :else             nil)))

(defn resolve-screen
  "Picks the screen for a panel. `wanted` is the saved screen (`{:id
  :width :height :x :y}`, only `:id` required) or `:window`; `screens`
  come from `describe-screens`; `taken` is the set of IDs other panels
  already use. Tries the same ID, then a screen of the same size (the
  nearest one), then the widest bar shaped screen that is not the
  primary one. Returns `{:screen <description or :window or nil> :reason
  <:window :exact :same-size :widest :not-found>}`."
  [wanted screens taken]
  (if (= wanted :window)
    {:screen :window :reason :window}
    (let [free     (remove #(contains? taken (:id %)) screens)
          exact    (first (filter #(= (:id %) (:id wanted)) free))
          distance (fn [s] (+ (Math/abs (long (- (:x s) (or (:x wanted) 0))))
                              (Math/abs (long (- (:y s) (or (:y wanted) 0))))))
          same     (when (and (:width wanted) (:height wanted))
                     (->> free
                          (filter #(and (= (:width %) (:width wanted)) (= (:height %) (:height wanted))))
                          (sort-by distance)
                          first))
          widest   (->> free
                        (remove :primary?)
                        (filter #(>= (/ (double (:width %)) (:height %)) bar-panel-aspect))
                        (sort-by :width >)
                        first)]
      (cond
        exact  {:screen exact :reason :exact}
        same   {:screen same :reason :same-size}
        widest {:screen widest :reason :widest}
        :else  {:screen nil :reason :not-found}))))

(defn assign-screens
  "Resolves the screen of every panel in order, so no two panels end up
  on the same screen. Returns one `resolve-screen` result per panel,
  with the panel itself under `:panel`."
  [panels screens]
  (first (reduce (fn [[results taken] panel]
                   (let [{:keys [screen] :as result} (resolve-screen (:screen panel) screens taken)]
                     [(conj results (assoc result :panel panel))
                      (cond-> taken (map? screen) (conj (:id screen)))]))
                 [[] #{}]
                 panels)))

(defn dim-alpha
  "How opaque the black dimming layer is for a brightness percentage
  (clamped to 20-100)."
  [brightness]
  (let [b (-> (or brightness 100) (max 20) (min 100))]
    (- 1.0 (/ b 100.0))))

(defn- dimmer
  "A glass pane that darkens everything below it, including the Beat
  Link waveform components whose colors we cannot change."
  ^JComponent [alpha]
  (let [shade (Color. (float 0) (float 0) (float 0) (float alpha))]
    (doto (proxy [JComponent] []
            (paintComponent [^Graphics g]
              (.setColor g shade)
              (.fillRect g 0 0 (.getWidth ^JComponent this) (.getHeight ^JComponent this))))
      (.setOpaque false))))

(def ^:private blank-cursor
  "An invisible cursor, so no mouse pointer is ever parked on a panel."
  (delay (.createCustomCursor (Toolkit/getDefaultToolkit)
                              (BufferedImage. 16 16 BufferedImage/TYPE_INT_ARGB)
                              (Point. 0 0)
                              "blank")))

(defn- bind-escape!
  "Arranges for the Escape key to run `on-escape`, so the displays can
  always be dismissed even though they have no window decorations."
  [^JFrame frame on-escape]
  (let [root (.getRootPane frame)]
    (.put (.getInputMap root JComponent/WHEN_IN_FOCUSED_WINDOW)
          (KeyStroke/getKeyStroke "ESCAPE") "bardisplay-close")
    (.put (.getActionMap root) "bardisplay-close"
          (seesaw/action :handler (fn [_] (on-escape))))))

(defn create
  "Opens a window covering one bar panel and points its cells at the
  panel's players.

  `panel` keys:

  * `:screen-id` the screen's ID string, or `:window` for an ordinary
    window
  * `:rows` and `:columns` the grid of player cells
  * `:players` the player shown in each cell, in order; extra cells stay
    empty
  * `:scale`, `:blocks`, `:title-scroll` passed on to the cells
  * `:brightness` 20-100, below 100 the panel is dimmed
  * `:always-on-top?` whether full screen panels float above other
    windows
  * `:window-bounds` `[x y width height]` in `:window` mode

  Returns a map with the `:frame`, the vector of `:cells` and a
  `:screen` label, or `nil` if the screen could not be found."
  [{:keys [screen-id rows columns players scale blocks title-scroll brightness always-on-top? window-bounds]}
   shutdown-chan on-escape]
  (let [rows                   (or rows 1)
        columns                (or columns 1)
        windowed?              (= screen-id :window)
        ^GraphicsDevice device (when-not windowed? (find-screen screen-id))]
    (if (and (not windowed?) (nil? device))
      (do
        (timbre/warn "Bar display: no screen found matching" (pr-str screen-id) "- skipping panel.")
        nil)
      (let [label         (if windowed? "Window" (.getIDstring device))
            options       {:scale scale :blocks blocks :title-scroll title-scroll}
            cells         (vec (repeatedly (* rows columns) #(cell/create shutdown-chan options)))
            content       (doto (JPanel. (GridLayout. rows columns 2 2))
                            (.setOpaque true)
                            (.setBackground (theme/color :background)))
            ^JFrame frame (seesaw/frame :title (str "Bar Display " label)
                                        :undecorated? (not windowed?)
                                        :on-close :dispose)
            alpha         (dim-alpha brightness)]
        (doseq [c cells]
          (.add content ^JComponent (:panel c)))
        (doto frame
          (.setContentPane content)
          (.setBackground (theme/color :background)))
        (when (pos? alpha)
          (let [glass (dimmer alpha)]
            (.setGlassPane frame glass)
            (.setVisible glass true)))
        (if windowed?
          (let [[x y w h] (or window-bounds [80 40 1500 1100])]
            (.setBounds frame (int x) (int y) (int w) (int h)))
          (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
            (doto frame
              (.setCursor ^Cursor @blank-cursor)
              (.setBounds bounds)
              (.setAlwaysOnTop (boolean always-on-top?)))))
        (bind-escape! frame on-escape)
        (seesaw/show! frame)
        (doseq [[c n] (map vector cells (concat players (repeat 0)))]
          ((:set-player! c) n))
        {:frame frame :cells cells :screen label}))))
```

- [ ] **Step 4: Rewrite `src/bardisplay/core.clj`**

```clojure
(ns bardisplay.core
  "Lifecycle of the bar displays. `open!` takes the panel descriptions
  built by `bardisplay.config/panels`, finds a screen for each and opens
  it with its players fixed in place. `status` reports what happened to
  each panel."
  (:require [bardisplay.screen :as screen]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc "Holds the open displays, or `nil` when they are closed."}
  displays (atom nil))

(defonce ^{:private true
           :doc "One entry per configured panel describing what happened when the
  displays were last opened. Empty while closed."}
  panel-status (atom []))

(defn close!
  "Closes the bar displays and releases everything they were watching."
  []
  (reset! panel-status [])
  (when-let [open (first (reset-vals! displays nil))]
    (async/close! (:shutdown-chan open))
    (seesaw/invoke-later
      (doseq [panel (:panels open)]
        (.dispose ^JFrame (:frame panel))))
    true))

(defn- screen-label
  "Names a screen for the status display."
  [screen]
  (cond
    (= screen :window) "Window"
    (map? screen)      (:id screen)
    :else              (str screen)))

(defn- open-panel
  "Opens one panel whose screen has been resolved. Returns its status
  entry and the opened panel (or `nil`)."
  [shutdown-chan index {:keys [panel screen reason]}]
  (let [entry {:index   index
               :players (:players panel)
               :wanted  (screen-label (:screen panel))
               :actual  (when screen (screen-label screen))
               :reason  reason}]
    (cond
      (empty? (:players panel))
      [(assoc entry :reason :no-players :open? false) nil]

      (nil? screen)
      [(assoc entry :open? false) nil]

      :else
      (let [screen-id (if (= screen :window) :window (:id screen))
            opened    (screen/create (assoc panel :screen-id screen-id) shutdown-chan close!)]
        [(assoc entry :open? (some? opened)) opened]))))

(defn open!
  "Opens the displays described by `panels` (see
  `bardisplay.config/panels`), closing any that were already open.
  Returns the open displays, or `nil` if no panel could be opened."
  [panels]
  (close!)
  (util/load-fonts)
  (let [shutdown-chan (async/promise-chan)
        resolved      (screen/assign-screens panels (screen/describe-screens))
        results       (vec (map-indexed (partial open-panel shutdown-chan) resolved))
        opened        (vec (keep second results))]
    (reset! panel-status (mapv first results))
    (if (seq opened)
      (do
        (reset! displays {:panels opened :shutdown-chan shutdown-chan})
        (timbre/info "Bar display: opened" (count opened) "panel(s) on" (pr-str (mapv :screen opened)))
        @displays)
      (do
        (async/close! shutdown-chan)
        (timbre/warn "Bar display: no panel could be opened.")
        nil))))

(defn open?
  "Checks whether the bar displays are currently open."
  []
  (some? @displays))

(defn open-panels
  "Returns the open panels, each with its `:frame` and `:cells`."
  []
  (:panels @displays))

(defn status
  "Returns one entry per configured panel: `:index`, `:players`,
  `:wanted` and `:actual` screen labels, `:reason` and `:open?`."
  []
  @panel-status)
```

- [ ] **Step 5: Run the tests**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/screen.clj src/bardisplay/core.clj test/bardisplay/screen_test.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Panels: screen fallback, fixed players, dimming, status

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Settings window (`bardisplay.settings`)

**Files:**
- Rewrite: `src/bardisplay/settings.clj`

Swing layout is checked by hand in Task 11; this task only needs the code to load and the tests to stay green.

- [ ] **Step 1: Rewrite `src/bardisplay/settings.clj`**

```clojure
(ns bardisplay.settings
  "The settings window. Everything the bar displays can be adjusted from
  lives here, so nothing has to be edited in source once it is running."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.sim :as sim]
            [bardisplay.status :as status]
            [bardisplay.theme :as theme]
            [beat-link-trigger.util :as util]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc "Holds the settings window while it is open."}
  window (atom nil))

(defrecord Choice [key label]
  Object
  (toString [_] label))

(def block-choices
  "The display blocks that can be switched on and off, with their labels."
  [[:track "Title and artist"]
   [:status "On air / master / sync"]
   [:tempo "Tempo"]
   [:time "Time"]
   [:detail "Scrolling waveform"]
   [:preview "Waveform overview"]
   [:phrase "Phrase"]
   [:key "Key"]
   [:loop "Loop"]
   [:cue "Next cue"]])

(def scroll-choices
  "The title scrolling modes."
  [(->Choice :off "Off") (->Choice :occasional "Occasionally")])

;;; Running the displays -------------------------------------------------------

(defn visible-players
  "The player numbers currently providing data: the simulated ones, or
  the real players on the network."
  []
  (cond
    (sim/active?)  (set (sim/player-numbers))
    (util/online?) (set (filter #(<= 1 % 6) (util/visible-player-numbers)))
    :else          #{}))

(defn status-lines
  "Describes what the displays are doing right now."
  []
  (status/lines {:online?     (util/online?)
                 :simulating? (sim/active?)
                 :visible     (visible-players)
                 :panels      (core/status)}))

(defn apply-settings!
  "Brings the displays in line with a settings map, starting or stopping
  the simulation as required. Returns the open displays, or `nil`."
  [{:keys [display panels simulate?] :as settings}]
  (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
  (core/close!)
  (if simulate?
    (sim/start! {:players (vec (sort (distinct (mapcat :players panels))))})
    (sim/stop!))
  (core/open! (config/panels settings)))

(defn apply-saved!
  "Opens the displays using the settings last saved from the window."
  []
  (apply-settings! (config/load-settings)))

;;; Panel rows -----------------------------------------------------------------

(defn- saved-id
  "The ID a saved screen is matched by."
  [saved]
  (if (map? saved) (:id saved) saved))

(defn- screen-model
  "The screen choices, plus the saved screen if it is not connected right
  now, so opening the window does not lose it."
  [saved]
  (let [choices (config/screen-choices)]
    (if (and (map? saved) (not-any? #(= (:id %) (:id saved)) choices))
      (conj choices (config/->ScreenChoice (:id saved) (str (:id saved) "  (not connected)") saved))
      choices)))

(defn- panel-row
  "Builds the widgets for one panel."
  [panel]
  (let [model  (screen-model (:screen panel))
        screen (seesaw/combobox :model model)
        layout (seesaw/combobox :model config/layouts)]
    (when-let [choice (first (filter #(= (:id %) (saved-id (:screen panel))) model))]
      (seesaw/selection! screen choice))
    (seesaw/selection! layout (config/layout-for (:layout panel)))
    {:screen        screen
     :layout        layout
     :players       (seesaw/text :text (str/join " " (:players panel)) :columns 8)
     :remove        (seesaw/button :text "Remove")
     :window-bounds (:window-bounds panel)}))

(defn- new-panel
  "A fresh panel for the `index`th row, its window offset so it does not
  cover the others."
  [index]
  (let [[x y w h] (:window-bounds config/default-panel)]
    (assoc config/default-panel
           :players []
           :window-bounds [(+ x (* 60 index)) (+ y (* 60 index)) w h])))

(defn- show-rows!
  "Lays the panel rows out again after one was added or removed."
  [root area rows]
  (seesaw/config! area :items
                  (into [[(seesaw/label :text "Screen") ""]
                         [(seesaw/label :text "Arrangement") ""]
                         [(seesaw/label :text "Players") ""]
                         [(seesaw/label :text "") "wrap"]]
                        (mapcat (fn [{:keys [screen layout players remove]}]
                                  [[screen ""] [layout ""] [players "growx"] [remove "wrap"]])
                                @rows)))
  (doseq [row @rows]
    (seesaw/config! (:remove row) :enabled? (> (count @rows) 1)))
  (seesaw/config! (seesaw/select root [:#add-panel]) :enabled? (< (count @rows) config/max-panels))
  (seesaw/pack! root))

(defn- add-row!
  "Adds a row for `panel`, wiring up its Remove button."
  [root area rows panel]
  (let [row (panel-row panel)]
    (seesaw/listen (:remove row) :action
                   (fn [_]
                     (swap! rows (fn [current] (filterv #(not (identical? % row)) current)))
                     (show-rows! root area rows)))
    (swap! rows conj row)))

(defn- read-panel
  "Reads one panel row. Returns `{:panel ...}` or `{:error ...}`."
  [{:keys [screen layout players window-bounds]}]
  (let [{:keys [error] :as parsed} (config/parse-players (seesaw/text players))]
    (if error
      {:error error}
      {:panel {:screen        (:spec (seesaw/selection screen))
               :layout        (:key (seesaw/selection layout))
               :players       (:players parsed)
               :window-bounds (or window-bounds (:window-bounds config/default-panel))}})))

(defn- block-id
  "The widget ID of a block's checkbox."
  [k]
  (keyword (str "block-" (name k))))

(defn- read-form
  "Collects the window into a settings map. Returns `{:settings ...}` or
  `{:errors [...]}`."
  [root rows]
  (let [results (map read-panel @rows)
        errors  (keep-indexed (fn [i {:keys [error]}] (when error (str "Panel " (inc i) ": " error))) results)
        pick    (fn [id] (seesaw/selection (seesaw/select root [(keyword (str "#" (name id)))])))]
    (if (seq errors)
      {:errors (vec errors)}
      {:settings (config/normalize
                  {:panels    (mapv :panel results)
                   :display   {:blocks         (set (for [[k _] block-choices :when (pick (block-id k))] k))
                               :brightness     (pick :brightness)
                               :title-scroll   (:key (pick :scroll))
                               :warn-seconds   (pick :warn)
                               :urgent-seconds (pick :urgent)
                               :scale          (pick :scale)
                               :always-on-top? (pick :on-top)}
                   :simulate? (pick :simulate)})})))

;;; Display section ------------------------------------------------------------

(defn- display-section
  "Lays out the options every panel shares."
  [display]
  (mig/mig-panel
    :constraints ["insets 0, hidemode 3" "[right]12[grow,fill]" "[]8[]"]
    :items
    [[(seesaw/label :text "Show") "top"]
     [(mig/mig-panel
        :constraints ["insets 0" "[]16[]" ""]
        :items (vec (map-indexed (fn [i [k label]]
                                   [(seesaw/checkbox :id (block-id k) :text label
                                                     :selected? (contains? (:blocks display) k))
                                    (if (odd? i) "wrap" "")])
                                 block-choices)))
      "wrap"]

     [(seesaw/label :text "Brightness") ""]
     [(seesaw/slider :id :brightness :min 20 :max 100 :value (:brightness display)
                     :major-tick-spacing 20 :minor-tick-spacing 10 :paint-labels? true)
      "wrap"]

     [(seesaw/label :text "Scroll long titles") ""]
     [(seesaw/combobox :id :scroll :model scroll-choices) "wrap"]

     [(seesaw/label :text "Waveform zoom") ""]
     [(seesaw/slider :id :scale :min 1 :max 16 :value (:scale display)
                     :major-tick-spacing 5 :minor-tick-spacing 1
                     :snap-to-ticks? true :paint-labels? true)
      "wrap"]

     [(seesaw/label :text "Warn when left") ""]
     [(seesaw/spinner :id :warn
                      :model (seesaw/spinner-model (:warn-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Urgent when left") ""]
     [(seesaw/spinner :id :urgent
                      :model (seesaw/spinner-model (:urgent-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Options") ""]
     [(seesaw/checkbox :id :on-top :text "Keep above other windows"
                       :selected? (:always-on-top? display))
      "wrap"]]))

;;; The window -----------------------------------------------------------------

(defn- show-status!
  "Shows status lines at the bottom of the window."
  [root lines]
  (seesaw/config! (seesaw/select root [:#status]) :text (status/html lines)))

(defn- apply!
  "Applies what the window currently shows, and remembers it."
  [root rows]
  (let [{:keys [errors settings]} (read-form root rows)]
    (if (seq errors)
      (show-status! root (mapv (fn [e] {:level :error :text e}) errors))
      (try
        (config/save-settings! settings)
        (apply-settings! settings)
        (show-status! root (status-lines))
        (catch Throwable t
          (timbre/error t "Could not apply bar display settings.")
          (show-status! root [{:level :error :text (str "Failed: " (.getMessage t))}]))))))

(defn- stop!
  "Closes the displays and stops any simulation."
  [root]
  (core/close!)
  (sim/stop!)
  (show-status! root (status-lines)))

(defn show!
  "Opens the bar display settings window, bringing it to the front if it
  is already open."
  []
  (if-let [^JFrame existing @window]
    (do (.toFront existing) existing)
    (let [settings (config/load-settings)
          display  (:display settings)
          rows     (atom [])
          area     (mig/mig-panel :constraints ["insets 0" "[][][grow,fill][]" ""])
          content  (mig/mig-panel
                     :constraints ["fillx" "[grow,fill]" ""]
                     :items [[(seesaw/label :text "<html><b>Panels</b></html>") "wrap"]
                             [area "wrap"]
                             [(seesaw/button :id :add-panel :text "Add panel") "growx 0, left, wrap 16"]
                             [(seesaw/label :text "<html><b>Display</b></html>") "wrap"]
                             [(display-section display) "wrap 12"]
                             [(seesaw/checkbox :id :simulate :text "Simulate players (no CDJs needed)"
                                               :selected? (:simulate? settings))
                              "wrap 12"]
                             [(seesaw/label :id :status :text "Not running.") "growx, wrap"]
                             [(seesaw/flow-panel :align :right
                                                 :items [(seesaw/button :id :stop :text "Stop")
                                                         (seesaw/button :id :apply :text "Apply")])
                              "growx"]])
          root     (seesaw/frame :title "Bar Display Settings"
                                 :content content
                                 :on-close :dispose
                                 :resizable? false)]
      (seesaw/selection! (seesaw/select root [:#scroll])
                         (or (first (filter #(= (:key %) (:title-scroll display)) scroll-choices))
                             (first scroll-choices)))
      (doseq [panel (:panels settings)]
        (add-row! root area rows panel))
      (seesaw/listen (seesaw/select root [:#add-panel]) :action
                     (fn [_]
                       (add-row! root area rows (new-panel (count @rows)))
                       (show-rows! root area rows)))
      (seesaw/listen (seesaw/select root [:#apply]) :action (fn [_] (apply! root rows)))
      (seesaw/listen (seesaw/select root [:#stop]) :action (fn [_] (stop! root)))
      (seesaw/listen root :window-closed (fn [_] (reset! window nil)))
      (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
      (show-rows! root area rows)
      (show-status! root (status-lines))
      (.setLocationRelativeTo root nil)
      (seesaw/show! root)
      (reset! window root))))
```

- [ ] **Step 2: Check it loads, and run the tests**

Add `bardisplay.settings` to a load check by running all tests (the test runner requires every test namespace; `settings` is not covered yet, so check it explicitly):

Create `C:\Users\bruns\AppData\Local\Temp\claude\ffcb6aae95ad6539\ca4da76b-68d2-493f-8c26-5ccd54829492\scratchpad\load-check.clj`:

```clojure
(require 'bardisplay.settings 'bardisplay.blt)
(println "loaded OK")
(System/exit 0)
```

Run (PowerShell):

```powershell
$tc="$env:LOCALAPPDATA\Programs\clojure-toolchain"; $env:JAVA_HOME="$tc\jdk-21.0.13+9"; $env:PATH="$env:JAVA_HOME\bin;$tc\bin;$env:PATH"; Set-Location O:\Beatlink-trigger; lein.bat update-in :prep-tasks empty -- run -m clojure.main C:/Users/bruns/AppData/Local/Temp/claude/ffcb6aae95ad6539/ca4da76b-68d2-493f-8c26-5ccd54829492/scratchpad/load-check.clj
```

Expected: `loaded OK`. Then `& O:\Beatlink-trigger\bar-display\run-tests.bat` → `0 failures, 0 errors.`

- [ ] **Step 3: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/settings.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Settings window: panel list and display options

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Show window status and dev helpers (`bardisplay.blt`, `bardisplay.dev`)

**Files:**
- Rewrite: `src/bardisplay/blt.clj`, `src/bardisplay/dev.clj`

- [ ] **Step 1: Rewrite `src/bardisplay/blt.clj`**

```clojure
(ns bardisplay.blt
  "The glue between the bar displays and Beat Link Trigger.

  BarDisplay.bls, at the top of the repository, is a Beat Link Trigger
  show whose expressions do nothing but put the `src` folder next to it
  on the class path and call the functions below. All the real logic
  lives here, so the show file itself almost never needs to change.

  Open the show once with File → Open Show; Beat Link Trigger reopens
  it by itself every time it starts."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.settings :as settings]
            [bardisplay.sim :as sim]
            [bardisplay.status :as status]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt.event ActionListener]
           [javax.swing Timer]))

(def ^:private load-order
  "The bar display namespaces, each after the ones it depends on."
  '[bardisplay.theme bardisplay.analysis bardisplay.sim bardisplay.state bardisplay.paint
    bardisplay.cell bardisplay.screen bardisplay.core bardisplay.config bardisplay.status
    bardisplay.settings bardisplay.blt])

(defonce ^{:private true
           :doc "The timer refreshing the status in the show window."}
  status-timer
  (atom nil))

(defn- stop-status-timer!
  "Stops refreshing the show window status."
  []
  (when-let [^Timer timer (first (reset-vals! status-timer nil))]
    (.stop timer)))

(defn- start-status-timer!
  "Refreshes `label` with the current status every second."
  [label]
  (stop-status-timer!)
  (let [timer (Timer. 1000 (reify ActionListener
                             (actionPerformed [_ _]
                               (try
                                 (seesaw/config! label :text (status/html (settings/status-lines)))
                                 (catch Throwable t
                                   (timbre/error t "Bar display: could not update the status."))))))]
    (.setInitialDelay timer 0)
    (.start timer)
    (reset! status-timer timer)))

(defn reload!
  "Reloads every bar display namespace from source. The show calls this
  when it is reopened in a running Beat Link Trigger, so edits (or a
  `git pull`) take effect without restarting."
  []
  (stop-status-timer!)
  (core/close!)
  (sim/stop!)
  (doseq [n load-order]
    (require n :reload)))

(defn- show-panel
  "Builds what the show window displays in place of the track list: the
  current status and the buttons needed to drive the displays."
  [status-label]
  (mig/mig-panel
    :constraints ["insets 16, fillx" "[grow,fill]" "[]12[]12[]"]
    :items
    [[(seesaw/label :text (str "<html><b>Bar Display</b><br>"
                               "The displays open by themselves when Beat Link Trigger goes online, "
                               "using the saved settings.</html>"))
      "wrap"]
     [status-label "wrap"]
     [(seesaw/flow-panel
        :align :left
        :items [(seesaw/button :text "Settings…"
                               :listen [:action (fn [_] (settings/show!))])
                (seesaw/button :text "Open Displays"
                               :listen [:action (fn [_] (settings/apply-saved!))])
                (seesaw/button :text "Close Displays"
                               :listen [:action (fn [_] (core/close!) (sim/stop!))])])
      "wrap"]]))

(defn setup!
  "Called from the show's Global Setup Expression. Replaces the show's
  track list with the bar display controls and status."
  [show]
  (let [status-label (seesaw/label :text "")]
    ((requiring-resolve 'beat-link-trigger.show/block-tracks) show (show-panel status-label))
    (start-status-timer! status-label)))

(defn online!
  "Called from the show's Came Online Expression. Opens the displays with
  the saved settings. Real players are now reachable, so a simulation
  left switched on from testing at home is ignored."
  []
  (let [saved (config/load-settings)]
    (when (:simulate? saved)
      (timbre/info "Bar display: online, ignoring the saved Simulate players setting."))
    (settings/apply-settings! (assoc saved :simulate? false))))

(defn shutdown!
  "Called from the show's Global Shutdown Expression."
  []
  (stop-status-timer!)
  (core/close!)
  (sim/stop!))

(comment
  ;; Handy things to evaluate from Beat Link Trigger's nREPL.
  (settings/show!)                                          ; Reopen the settings window.
  ((requiring-resolve 'bardisplay.screen/describe-screens)) ; Which screen is which?
  (core/status)                                             ; What happened to each panel?
  (core/close!)
  ((requiring-resolve 'bardisplay.dev/render-samples) "target/preview") ; Check the layout offline.
  )
```

- [ ] **Step 2: Rewrite `src/bardisplay/dev.clj`**

```clojure
(ns bardisplay.dev
  "Helpers for working on the displays without any CDJs: an offscreen
  renderer for checking layout, and a demo that runs the simulation in
  ordinary windows."
  (:require [bardisplay.cell :as cell]
            [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.screen :as screen]
            [bardisplay.sim :as sim]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [seesaw.core :as seesaw])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [javax.swing JComponent JFrame JPanel]))

(def sample-snapshot
  "A plausible player state, for checking that every element lands where
  it should and nothing collides."
  {:player     2
   :online?    true
   :playing    true
   :on-air     true
   :master     true
   :synced     true
   :bpm-only   false
   :beat       1
   :pitch      0.8
   :tempo      128.04
   :played     154320
   :remain     42150
   :precise    true
   :title      "Strobe (Original Mix) - a deliberately long title to test truncation"
   :artist     "deadmau5"
   :key        "Am"
   :phrase     {:label "Chorus" :color (Color. 255 120 180) :text-color Color/BLACK :beats-left 12}
   :looping    true
   :loop-beats 4
   :next-cue   {:ms-left 12000 :color (Color. 40 220 40)}})

(defn render
  "Paints one cell at the given size and writes it to `file`. Supply a
  `:snapshot` to control what is shown; it defaults to
  `sample-snapshot`. `:blocks`, `:title-scroll` and `:scale` are passed
  to the cell, `:brightness` dims the result like a panel would. Set
  `:outline?` to trace each visible child's bounds, which makes layout
  problems obvious."
  [{:keys [width height scale snapshot file outline? blocks title-scroll brightness]
    :or   {width 1920 height 440 scale 4 snapshot sample-snapshot}}]
  (util/load-fonts)
  ;; A closed shutdown channel stops the animation loop before its first tick,
  ;; so it cannot overwrite the snapshot we are rendering.
  (let [stopped       (doto (async/promise-chan) (async/close!))
        built         (cell/create stopped {:scale scale :blocks blocks :title-scroll title-scroll})
        ^JPanel panel (:panel built)
        image         (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        g             (.createGraphics image)
        alpha         (screen/dim-alpha brightness)]
    (reset! (:snapshot built) snapshot)
    (doto panel
      (.setSize width height)
      (.doLayout))
    (.setPaint g Color/BLACK)
    (.fillRect g 0 0 width height)
    (.paint panel g)
    (when outline?
      (.setPaint g Color/MAGENTA)
      (doseq [^java.awt.Component child (.getComponents panel)
              :when (.isVisible child)]
        (let [b (.getBounds child)]
          (.drawRect g (.-x b) (.-y b) (dec (.-width b)) (dec (.-height b))))))
    (when (pos? alpha)
      (.setPaint g (Color. (float 0) (float 0) (float 0) (float alpha)))
      (.fillRect g 0 0 width height))
    (.dispose g)
    (io/make-parents file)
    (ImageIO/write image "png" (io/file file))
    {:file   file
     :bounds (vec (for [^java.awt.Component child (.getComponents panel)
                        :when (.isVisible child)]
                    [(.getSimpleName (class child)) (str (.getBounds child))]))}))

(defn render-samples
  "Writes a set of cell renderings covering the display options into
  `directory`."
  [directory]
  (let [f #(str directory "/" %)]
    [(render {:width 1920 :height 440 :outline? true :file (f "cell-full-1920x440.png")})
     (render {:width 960 :height 440 :outline? true :file (f "cell-compact-960x440.png")})
     (render {:width 1920 :height 440 :blocks #{:track :time :detail} :file (f "cell-few-blocks.png")})
     (render {:width 1920 :height 440 :blocks #{:track :status :tempo :time :phrase :key :loop :cue}
              :file (f "cell-no-waveforms.png")})
     (render {:width 1920 :height 440 :snapshot {:player 3 :online? false} :file (f "cell-offline.png")})
     (render {:width 1920 :height 440 :brightness 40 :file (f "cell-dimmed.png")})
     (render {:width 1920 :height 440 :snapshot {:player 0} :file (f "cell-idle.png")})]))

(defn demo!
  "Starts the simulation and opens the displays in ordinary windows, so
  the whole thing can be watched running at a desk: two panels of two
  players each by default."
  ([]
   (demo! {}))
  ([{:keys [panels display]
     :or   {panels [{:screen :window :layout :column :players [1 2] :window-bounds [40 40 1100 600]}
                    {:screen :window :layout :column :players [3 4] :window-bounds [700 300 1100 600]}]}}]
   (let [settings (config/normalize {:panels panels :display display})]
     (sim/start! {:players (vec (sort (distinct (mapcat :players (:panels settings)))))})
     (core/open! (config/panels settings)))))

(defn stop-demo!
  "Closes the demo windows and stops the simulation."
  []
  (core/close!)
  (sim/stop!))

(defn capture!
  "Paints the open display panels into PNG files. This repaints their
  component trees offscreen rather than grabbing the screen, so it does
  not matter whether the windows are visible or in front."
  [directory]
  (vec (map-indexed (fn [index panel]
                      (let [^JComponent content (.getContentPane ^JFrame (:frame panel))
                            image               (BufferedImage. (max 1 (.getWidth content))
                                                                (max 1 (.getHeight content))
                                                                BufferedImage/TYPE_INT_RGB)
                            g                   (.createGraphics image)
                            file                (io/file (str directory "/display-" index ".png"))]
                        (seesaw/invoke-now (.paint content g))
                        (.dispose g)
                        (io/make-parents file)
                        (ImageIO/write image "png" file)
                        (str file)))
                    (core/open-panels))))
```

- [ ] **Step 3: Load check and tests**

Extend the scratch `load-check.clj` from Task 9 to:

```clojure
(require 'bardisplay.settings 'bardisplay.blt 'bardisplay.dev)
(println "loaded OK")
(System/exit 0)
```

Run it with the same PowerShell command as in Task 9. Expected: `loaded OK`.
Then `& O:\Beatlink-trigger\bar-display\run-tests.bat` → `0 failures, 0 errors.`

- [ ] **Step 4: Commit**

```bash
git -C /o/Beatlink-trigger/bar-display add src/bardisplay/blt.clj src/bardisplay/dev.clj
git -C /o/Beatlink-trigger/bar-display commit -m "Show window status; dev helpers for the new options

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Visual check, manual demo, docs, merge

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Render the sample cells**

Create scratch `render.clj` next to `load-check.clj`:

```clojure
(require 'bardisplay.dev)
(doseq [r (bardisplay.dev/render-samples "C:/Users/bruns/AppData/Local/Temp/claude/ffcb6aae95ad6539/ca4da76b-68d2-493f-8c26-5ccd54829492/scratchpad/preview")]
  (println (:file r)))
(System/exit 0)
```

Run it with the PowerShell command from Task 9 (replace the script name). Expected: seven file paths printed.

- [ ] **Step 2: Look at every PNG with the Read tool**

Check, and fix the code (not the expectations) if any fails:
- `cell-full-1920x440.png`: no magenta outlines overlap; info block shows `CHORUS · 12`, `KEY Am`, lit `LOOP 4`, `CUE 0:12` with green dot.
- `cell-compact-960x440.png`: all header blocks fit, title truncated with `…`.
- `cell-few-blocks.png`: only badge, title, time and detail waveform area.
- `cell-no-waveforms.png`: header fills the cell height, text larger, nothing clipped.
- `cell-offline.png`: `PLAYER 3` badge, `OFFLINE` title, everything grey.
- `cell-dimmed.png`: visibly darker than the full render.
- `cell-idle.png`: `No Player`.

- [ ] **Step 3: Manual demo with the user**

Start `O:\Beatlink-trigger\bar-display\run-display.bat` (PowerShell `Start-Process`). Ask the user to check in the settings window:
- an existing old `~/.bar-display.edn` shows up as one panel row;
- add a second panel with players `3 4`, tick *Simulate players*, Apply → two windows, fixed players, status lines green/yellow as expected;
- switch blocks off and on, brightness 40, *Scroll long titles: Occasionally* → title scrolls about every 30 s;
- put a player number that is not simulated (e.g. `5`) into a panel → its cell shows `OFFLINE`, status marks it.

Fix anything the user reports, re-run the tests, commit.

- [ ] **Step 4: Update `README.md`**

Replace the paragraph starting "The show window has buttons" with:

```markdown
The show window shows the current status of every panel (which screen it is on, whether a
substitute screen was used, which players are online) and has buttons to open the
**Settings…** and to open or close the displays. Whenever Beat Link Trigger goes online, the
displays open by themselves with the saved settings (stored in `~/.bar-display.edn`).
*Simulate players* is ignored while online.

In the settings you configure up to four **panels**. Each panel goes to a screen (or an
ordinary window for testing) and shows a fixed list of players, for example `1 2`. A player
that is not on the network keeps its place and is shown as offline. If a saved screen is not
connected, a screen of the same size, or else the widest bar-shaped screen, is used instead.

The **Display** options apply to all panels: which blocks to show (title, status, tempo, time,
waveforms, and the info items phrase, key, loop and next cue), brightness, and whether long
titles scroll through occasionally.
```

and add before "## Standalone":

```markdown
## Tests

Run `run-tests.bat` (all tests) or `run-tests.bat bardisplay.config-test` (one namespace).
It needs `bar-display/test` in the `:dev` `:source-paths` of the surrounding Beat Link
Trigger checkout, next to `bar-display/src`.
```

and change the Standalone section's path line to
`` contains `:source-paths ["bar-display/src" "bar-display/test"]`. Then run `run-display.bat`. ``

- [ ] **Step 5: Full test run**

Run: `& O:\Beatlink-trigger\bar-display\run-tests.bat`
Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Commit, merge, push**

```bash
git -C /o/Beatlink-trigger/bar-display add README.md
git -C /o/Beatlink-trigger/bar-display commit -m "Document panels, display options and tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git -C /o/Beatlink-trigger/bar-display checkout main
git -C /o/Beatlink-trigger/bar-display merge --no-ff panels -m "Merge panels, status and display options

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git -C /o/Beatlink-trigger/bar-display push
```
