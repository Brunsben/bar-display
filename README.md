# Bar Display

Player displays for bar-type screens, driven by [Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger).

## Use in Beat Link Trigger (live CDJ data)

1. Clone this repository anywhere (or copy the folder).
2. In Beat Link Trigger choose **File → Open Show** and pick `BarDisplay.bls` from this folder.

That's it. Beat Link Trigger reopens the show by itself every time it starts. The show
finds the code in the `src` folder next to it, so nothing needs to be configured or pasted.

The show window shows the current status of every panel (which screen it is on, whether a
substitute screen was used, which players are online) and has buttons to open the
**Settings…** and to open or close the displays. Whenever Beat Link Trigger goes online, the
displays open by themselves with the saved settings (stored in `~/.bar-display.edn`).
*Simulate players* is ignored while online.

In the settings you configure up to four **panels**. Each panel goes to a screen (or an
ordinary window for testing, which remembers its position and size) and shows a fixed list of
players, for example `1 2`. A player that is not on the network keeps its place and is shown
as offline. If a saved screen is not connected, a screen of the same size, or else the widest
bar-shaped screen, is used instead.

The **Display** options apply to all panels: which blocks to show (title, status, tempo, time,
waveforms, and the info items phrase, key, loop and next cue), brightness, and whether long
titles scroll through occasionally. When a cell is too narrow for every block at a readable
size, blocks are left out in this order: tempo, on air/master/sync, time, info.

To update: `git pull`, then restart Beat Link Trigger (or close and reopen the show).
Beat Link Trigger saves the show window position into `BarDisplay.bls`. If `git pull`
complains about it, run `git checkout BarDisplay.bls` first.

All the integration code is in [src/bardisplay/blt.clj](src/bardisplay/blt.clj); the show's
expressions only call into it.

## Standalone (simulated players only, for development)

Expects this folder inside a Beat Link Trigger checkout whose `project.clj` `:dev` profile
contains `:source-paths ["bar-display/src" "bar-display/test"]`. Then run `run-display.bat`.
The simulation plays players 1-4; put 5 or 6 in a panel to see an offline player.

## Tests

Run `run-tests.bat` (all tests) or `run-tests.bat bardisplay.config-test` (one namespace).
It needs `bar-display/test` in the `:dev` `:source-paths` as shown above.
