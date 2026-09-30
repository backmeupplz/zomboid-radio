# Zomboid Radio

Live **WFPK 91.9 Louisville, KY** on in-game **91.8 MHz** in Project Zomboid (Build 42). Tune any radio (handheld, ham, car) to 91.8 and it plays the real station instead of static.

## Install

1. Download the zip for your OS from [Releases](../../releases/latest) and unzip it.
2. Close the game and run the executable. `INSTRUCTIONS.txt` in the zip covers the Windows SmartScreen and macOS Gatekeeper prompts.
3. Tune a radio to 91.8 MHz in game.

After a game update the radio goes away (Steam restores the original file), so run the patcher again. To uninstall, use Steam > Properties > Installed Files > **Verify integrity of game files**.

## Multiplayer

The patch is client-side only. The server doesn't need it and doesn't check for it. Each player who runs the patcher hears the station on their own radios, streamed straight from WFPK. Unpatched players hear static on 91.8.

## How it works

The zip contains no game code. The executable finds your Steam install and runs a small patcher with the game's own bundled Java. The patcher uses the JDK ClassFile API to rewrite three methods inside your `projectzomboid.jar`:

- `DeviceData.updateEmitter`: a radio tuned to 91.8 plays the stream instead of the static loop.
- `FMODSoundEmitter.addSound` and `FileSound.stop`: `http` sound clips open as FMOD net streams and are closed again when stopped.

The hook logic lives in [`ZomboidRadioHook.java`](patcher/zombie/radio/devices/ZomboidRadioHook.java). The patcher checks that every hook matched exactly once and verifies the bytecode before it writes anything. If a future game version changes these methods, the patcher refuses to run and leaves the jar untouched.

To change the station or frequency, edit `CHANNEL` / `URL` in the hook and rebuild. The URL must be a plain `http` MP3 stream, and the frequency a multiple of 200 kHz.

## Build

Needs JDK 25+, Go, zip, and a local copy of the game (only for compiling against):

```sh
./build.sh   # -> dist/zomboid-radio-{windows,macos,linux}.zip
```
