# Bar Display

Player displays for bar-type screens, driven by [Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger).

## Use inside Beat Link Trigger (live CDJ data)

See [blt/shared_functions.clj](blt/shared_functions.clj): copy `bar-display-load!` into the
Triggers window's Shared Functions (adjust the source path), then add the hooks to the
Global Setup, Came Online and Global Shutdown expressions. Settings are stored in
`~/.bar-display.edn`.

## Standalone (simulated players only)

Expects this folder inside a Beat Link Trigger checkout whose `project.clj` `:dev` profile
contains `:source-paths ["bar-display/src"]`. Then run `run-display.bat`.
