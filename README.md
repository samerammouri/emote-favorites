# Emote Favorites

RuneLite external plugin for favoriting and sorting the emote tab.

## Features

- Sort emotes alphabetically or with favorites first.
- Favorite emotes for quick grouping.
- Optionally show favorites only.

## Behavior

The plugin follows the same general integration pattern RuneLite uses for script-driven interfaces such as prayers and spellbooks:

- It reapplies widget state after emote tab redraws instead of assuming one initial rebuild is enough.
- It keeps the favorite context action available on emotes as the tab rebuilds.
- It avoids adding extra tab controls and relies on plugin config instead.

## Running locally

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

## Packaging

```bash
./gradlew shadowJar
```
