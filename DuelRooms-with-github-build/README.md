# DuelRooms 1.0.0

Automatic 1v1 duel rooms for Paper/Spigot 1.21.x. Players walk into a room entrance, wait for an opponent,
fight, loot for 35 seconds and leave. No `/duel <player>` challenges.

## Build
Requires JDK 21 and Maven.

    mvn clean package

The plugin jar is `target/DuelRooms-1.0.0.jar`. The API dependency is `spigot-api 1.21.4` (works on all 1.21.x).

## Install
1. Put the jar in `plugins/` and start the server once (config files are generated in `plugins/DuelRooms/`).
2. Load the worlds you use before the plugin enables (Multiverse is a soft-dependency).
3. Set up a room (as OP / `duel.admin`):

       /duel rooms create arena1          (note the room number, shown on creation and in /duel rooms list)
       /duel rooms setspawn1 arena1       (stand where player 1 should start)
       /duel rooms setspawn2 arena1       (stand where player 2 should start)
       /duel rooms setexit arena1         (where players are sent after the duel)
       /duel rooms setentrance arena1     (stand in the doorway players will walk into)
       /duel rooms hologram 1             (stand above the entrance; 1 = room number)

4. Build a door/portal at the entrance. Walking within `entrance-radius` of it joins the room.
   Keep the exit away from the entrance so nobody re-triggers it.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/duel leave` (aliases `/duelrooms`, `/dr`) | duel.use | Leave while waiting, or as the winner while looting |
| `/duel rooms create <name>` | duel.admin | Create a room (name: letters, digits, `_`, `-`) |
| `/duel rooms delete <name>` | duel.admin | Delete a room (players inside are sent out) |
| `/duel rooms setspawn1 <name>` | duel.admin | First player spawn |
| `/duel rooms setspawn2 <name>` | duel.admin | Second player spawn |
| `/duel rooms setentrance <name>` | duel.admin | Entrance (block center) |
| `/duel rooms setexit <name>` | duel.admin | Exit location |
| `/duel rooms enable <name>` / `disable <name>` | duel.admin | Enable/disable (disabling ends a running duel) |
| `/duel rooms set <name> <countdown\|looting> <seconds\|reset>` | duel.admin | Per-room timing override |
| `/duel rooms list` | duel.admin | List rooms, status, missing setup |
| `/duel rooms reload` | duel.admin | Reload config, messages, rooms (idle rooms) and holograms |
| `/duel rooms hologram <number>` | duel.admin | Create/move the hologram of room number at your location |
| `/duel rooms hologram remove <number>` | duel.admin | Remove a hologram |
| `/duel rooms hologram list` / `reload` | duel.admin | List / reload holograms |

## Permissions
- `duel.use` (default: everyone): enter duel rooms.
- `duel.admin` (default: op): all `/duel rooms ...` commands. OPs always have access.
- `duel.bypass` (default: op): ignore room restrictions (block protection, teleport/command blocks, escape prevention, PvP interference rules).
  By default this also applies while an OP is dueling; set `settings.bypass-while-in-duel: false` to test the restrictions as OP.

## Files
- `config.yml`: countdown (5), looting (35), padding, entrance radius, spawn commands, allowed commands, toggles.
- `rooms.yml`: rooms, locations, enabled flag, informational state, players pending exit (crash recovery).
- `holograms.yml`: hologram locations, `default-height`, `default-line-spacing`, per-hologram `height`/`line-spacing`, text per status.
- `messages.yml`: every player/admin message.

## How it works
- Statuses: AVAILABLE -> WAITING (1 player) -> ACTIVE (2 players, room locked) -> LOOTING (winner, 35s) -> AVAILABLE. DISABLED when an admin disables the room.
- ACTIVE: 5s freeze countdown (movement and damage blocked, head rotation free), then PvP opens.
- Death ends the fight at once; the winner loots for 35s (action bar + hologram). Then the winner is teleported to the exit, leftover duel items are removed and the room resets.
- `/spawn` (and aliases in `spawn-commands`, e.g. EssentialsX) by the winner releases and resets the room immediately and is not cancelled, so EssentialsX performs the teleport. A waiting player may use it too. If the spawn command never teleports them, they are sent to the exit after `spawn-fallback-ticks`.
- Disconnects: waiting -> room freed; during countdown -> opponent returns to WAITING; during the fight -> quitter is killed and the opponent wins and loots; looting -> room resets. Offline players are sent to the exit on next join. Shutdown/reload sends everyone out.
- Holograms are non-persistent TextDisplay entities, one per non-blank line, updated only when the text changes (once per second while looting). Chunk loads re-spawn missing lines.
- Room area = sphere around the midpoint of both spawns, radius = half spawn distance + `room-padding`. It drives block protection, escape prevention and duel item tracking.

## Manual test checklist (two accounts)
1. Create/configure a room, place the hologram: shows AVAILABLE, Players 0/2.
2. Player A walks into the entrance: teleported to spawn1, "Waiting for an opponent...", hologram WAITING 1/2. A cannot join another room.
3. Player B enters: teleported to spawn2, hologram ACTIVE 2/2; a third player gets "occupied".
4. Countdown 5..1 with both frozen; hits do nothing; after "FIGHT!" PvP works; breaking/placing blocks is blocked.
5. Kill one player: winner announced, hologram LOOTING with time ticking, action bar shows time, loser respawns at the exit.
6. Winner `/spawn` -> room immediately AVAILABLE. Repeat and wait 35s -> winner sent to exit, drops removed, room AVAILABLE.
7. Quit during each phase; `/duel rooms disable <name>` mid-duel; restart server while a duel is running.

## Notes
- Items on the ground inside the room area are only cleaned if they were spawned during ACTIVE/LOOTING.
- The physical room is yours to build: the plugin blocks joining, interference and escape, but does not wall off the entrance.
