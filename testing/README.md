# Peripheral Testing

Install this on a server to let players use Peripheral's packet tools on it: `packet.cancel()`,
`packet.hold()` and `packet.send()`. Without it, those only work in singleplayer. The packet log
works everywhere either way.

| Server | Jar | Where it goes |
|---|---|---|
| Paper, Spigot, Purpur (1.21.4 and newer) | `peripheral-testing-paper-<version>.jar` | `plugins/` |
| Fabric (26.2, with Fabric API) | `peripheral-testing-fabric-<version>.jar` | `mods/` |

It lets everyone on the server use the tools, so use it on servers you're testing.

How it works: the server listens on the `peripheral:testing` plugin channel and tells joining
clients so. Peripheral checks for that channel. Nothing is ever sent on it.

Build both jars:

```bash
./gradlew -p testing build   # from the Peripheral folder
```

They land in `paper/build/libs/` and `fabric/build/libs/`.
