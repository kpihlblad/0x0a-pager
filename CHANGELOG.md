# Changelog

## v1.2.0

- **Scope:** a filter button beside go-to and search opens the scope panel; pressing it again closes the panel.
- **Layout:** `ui-layout` preference: `most` (status and prompt bars at the bottom, the default) or `win` (status row merged into the window title bar, prompt bar directly below).
- **Input prompts:** the input bar flashes twice when it wants input, then reminds you every 6 seconds while it waits.
- **Action bar:** right-side buttons share one size and style; the status and action bars are easier to tell apart.
- **Preferences:** the theme is set here instead of the menu. Two-option settings are horizontal toggles.
- **Light mode:** Blockies use pastel colours; icons are drawn in a colour that reads on the light background.

## v1.1.0

- **Scope:** Narrow/Exclude field filters, multiple ORed values per field, and a Narrow/Exclude action on selections.
- **Suggested fields:** recalculated on filter change, with loading state.
- **Mood view:** keyboard help is consolidated here, with an on-demand night sky.
- **Interaction:** drag-select copy, double-tap Tab selects the word at cursor, copy toast, tooltips stay inside the window.
- **Light mode** added.
- **Keepalive:** pings the session every 5 min for up to 4 h of inactivity.
- **Fonts:** Monaspace Neon NF upgraded to v1.400.
- **Packaging:** Linux `.deb` and snap, macOS jar, and a 0x app icon.
