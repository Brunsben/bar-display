# LJ Features, Club Operation and Translations Implementation Plan

> Executed inline in the session that wrote it, so tasks list interfaces and test cases rather
> than full code. Spec: `docs/superpowers/specs/2026-11-06-lj-features-i18n-design.md`.
> Conventions (branch, test commands, commit trailer) as in
> `2026-11-06-panels-status-display-options.md`. Branch: `lj-features`.

**Goal:** Next-phrase countdown, off-air dimming, beat counter and downbeat flash, screen
identification, automatic reopening on screen changes, presets, and translatable UI (en, de).

---

### Task 1: `bardisplay.i18n` and language files
- Create `src/bardisplay/i18n.clj`: `available` (codes + names from the `bardisplay/i18n/` resource
  folder), pure `resolve-code [setting codes system]`, pure `lookup [texts fallback k]`,
  `set-language! [setting]`, `t [k & args]` (format when args), `current-code`.
  English is loaded at namespace load so `t` works before any setting is applied.
- Create `src/bardisplay/i18n/en.edn` (texts identical to today's UI strings) and `de.edn`.
- Tests `i18n_test.clj`: lookup falls back to English then to the key name; `resolve-code` picks the
  setting, else the system language if available, else "en"; `t` formats args; every key in
  `en.edn` exists in `de.edn`; `available` lists en and de.

### Task 2: Translate existing strings
- `status`, `paint`, `config` (layout labels via `toString`, screen choice labels, player errors),
  `settings`, `blt` use `i18n/t`. Existing tests stay green because English is the default.

### Task 3: Next phrase
- `analysis/phrase-at` adds `:next`. Tests: `:next` is the following entry, `nil` in the last phrase.
- `paint/phrase-count [beats-left unit]` → `[n unit-key]`; `paint/info-content` takes an `opts`
  map (`:phrase-unit`), phrase text `CURRENT → NEXT · n unit` (or END).
- `paint/info-slots [items w h gap]` → `[[item x y w h] ...]`: phrase spans the top row when present.
- Tests for both units, END, and slot geometry (no overlap, inside the block).

### Task 4: Beat block, dimming, flash
- `cell/all-blocks` + `:beat`; `header-order [:info :beat :status :tempo :time]`;
  `drop-order [:tempo :status :beat :time :info]`; theme metric `:beat {:full 110 :compact 90}`.
- `paint/beat-counter` painter; repaint group `:beat`.
- `state/any-on-air?` (live statuses or simulation).
- Pure `cell/dim? [snap any-on-air? enabled?]`, `cell/downbeat? [previous current]`.
- Cell panel overlay (dim fill, flash border) via `paintChildren`; tick repaints the cell when the
  overlay state changes. `cell/create` options gain `:dim-off-air?`, `:flash-downbeat?`,
  `:phrase-unit`; `config/panels` passes them through.
- Tests: layout with `:beat`, drop order, `dim?`, `downbeat?`.

### Task 5: Screens
- `screen/identify!`: one undecorated window per screen for 4 s, click closes.
- Pure `screen/screens-changed? [before after]`; `core/open!` starts a go-loop (ends with the
  shutdown channel) that every 3 s compares screen IDs and reopens with the same panels/options.
- Test: `screens-changed?`.

### Task 6: Presets and language in settings
- `config`: defaults gain `:language :auto`, `:presets {}`, display defaults
  `:phrase-unit :bars :dim-off-air? true :flash-downbeat? false`; `normalize` keeps and normalizes
  `:presets` and `:language`; `save-settings!` writes them; pure `save-preset`, `load-preset`,
  `delete-preset`.
- `settings`: preset row (combobox, Load, Save as…, Delete), language combobox (rebuilds window),
  new display options (phrase unit, dim off-air, flash), Identify screens button; Apply keeps
  presets and language; `apply-settings!` calls `i18n/set-language!`.
- `blt`: load order gains `bardisplay.i18n`; setup applies the saved language.
- Tests: preset functions, normalize of presets/language.

### Task 7: Check and finish
- Render samples (phrase row, beat block, dimmed cell), manual demo with the user, README, merge.
