# VoronezhskiyAlert

Fabric client-side BedWars awareness mod for Minecraft **1.21.11**.

Author: **nikitaberg**  
Website: **https://nikitaberg.ru**

## Controls

- **H** — settings menu
- **J** — place/move the flag at the current position

## Features

- Enemy-only tracking. Scoreboard teams are authoritative.
- Conservative display-name/team-colour fallback when scoreboard teams are unavailable.
- Configurable view radius (10–500 blocks).
- Enemy equipment display and red direction arrows.
- Flag distance HUD in the top-right corner.
- Configurable flag alert radius (5–100 blocks), default **25**.
- Enemy alert message: `Оппонент у метки! <ник> — <X> блоков`.
- Vanilla bell alert sound with a menu toggle.
- Red, semi-transparent edge vignette that becomes stronger as the enemy gets closer.
- Smooth vignette fade in/out.
- Alert cooldown: **20 seconds**.
- Flag is automatically removed when changing worlds.
- **Снять метку** button in H menu.
- Settings stored in `config/nearplayer.json`.

## Build

Windows:

```text
gradlew.bat build
```

Linux/macOS:

```text
./gradlew build
```

Output: `build/libs/`
