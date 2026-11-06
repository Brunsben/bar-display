# Bar Display

Player displays for bar-type screens, driven by [Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger).

## Use in Beat Link Trigger (live CDJ data)

1. Clone this repository anywhere (or copy the folder).
2. In Beat Link Trigger choose **File → Open Show** and pick `BarDisplay.bls` from this folder.

That's it. Beat Link Trigger reopens the show by itself every time it starts. The show
finds the code in the `src` folder next to it, so nothing needs to be configured or pasted.

The show window has buttons to open the **Settings…** and to open or close the displays.
Whenever Beat Link Trigger goes online, the displays open by themselves with the saved
settings (stored in `~/.bar-display.edn`). *Simulate players* is ignored while online.

To update: `git pull`, then restart Beat Link Trigger (or close and reopen the show).
Beat Link Trigger saves the show window position into `BarDisplay.bls`. If `git pull`
complains about it, run `git checkout BarDisplay.bls` first.

All the integration code is in [src/bardisplay/blt.clj](src/bardisplay/blt.clj); the show's
expressions only call into it.

## Standalone (simulated players only, for development)

Expects this folder inside a Beat Link Trigger checkout whose `project.clj` `:dev` profile
contains `:source-paths ["bar-display/src"]`. Then run `run-display.bat`.
