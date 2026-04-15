# Tauri issue #12167 repro

Minimal Tauri 2 app to test the behavior reported in `tauri-apps/tauri#12167`.

## Issue summary

The report describes a macOS problem where a new `WebviewWindow` is created with explicit `x`/`y` coordinates that belong to a secondary monitor, but the window still opens at `(0,0)` on the primary display. In the original report, this also happened with `fullscreen: true`.

## What this app does

- Shows the current monitor list and each monitor position.
- Lets you pick `x` and `y` coordinates.
- Lets you create a second webview with `fullscreen` enabled or disabled.
- Opens the second webview using the same frontend entrypoint, but with query params that make the new window identify itself.

## Run

```bash
npm install
npm run tauri dev
```

## Suggested repro flow

1. Start the app on a machine with two monitors.
2. Check the monitor coordinates listed on the main window.
3. Use coordinates that belong to the secondary monitor.
4. Click `Create second webview`.
5. Verify whether the new webview opens on the expected monitor or falls back to the primary monitor.

## Build verification

This repo was built successfully with:

```bash
npm run build
npm run tauri build
```
