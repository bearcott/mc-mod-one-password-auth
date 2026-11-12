# One Password Auth

A lightweight server-side mod/plugin that protects your offline (or online) server with a single shared password. Designed for small friend groups and private communities—no per-user accounts, no complex authentication schemes, just one password everyone shares!

## ✅ Core Features

- Single shared password for entire server access
- Secures all players/inventories before authenticating, even those with OP access
- Mitigates common attack vectors (see [SECURITY.md](SECURITY.md)) including block-breaking packets from hacked clients
- Players authenticate once with `/login <password>` then they're IP whitelisted
- Customizable login title and description
- Discord webhook integration for event logging

## 🚀 Getting Started

**Fabric:** drop `one-password-auth-fabric-*.jar` into `mods/`. Needs Fabric API.

**Paper:** drop `one-password-auth-paper-*.jar` into `plugins/`. Nothing else needed.

That's it! Everything should work out of the box! The next time anyone logs in, they will be greeted with a login dialogue.

> **Note:** On first boot a phonetic password (e.g. `komipu42!`) is generated and written to the config file. The password is **not** printed to the console (logs are often shared/retained) — open the config file to view or change it, or write the config file yourself beforehand to choose your own.

### Where the files live

| | Fabric | Paper |
| --- | --- | --- |
| Config | `config/one_password_auth_config.properties` | `plugins/OnePasswordAuth/one_password_auth_config.properties` |
| IP + UUID whitelist | `config/one_password_auth_ip_whitelist.txt` | `plugins/OnePasswordAuth/one_password_auth_ip_whitelist.txt` |
| Pending sessions | `config/one_password_auth_sessions.json` | `plugins/OnePasswordAuth/one_password_auth_sessions.json` |

## ⚙️ Customization

Upon first boot the plugin will generate a config file with a default password (such as `komipu42!`) and all additional customizable settings (see the table above for where it is). The generated password is written only to that file, not the console, so open the file to read it.

All the lines except for `password=` are optional! The server will kick everyone if no password is set.

To change any settings, just edit the config file and restart the server.

### Settings

| Setting | Default | What it does |
| --- | --- | --- |
| `password` | generated | The shared password everyone types with `/login`. The only required line. |
| `webhook_url` | none | Discord webhook for the player feed: joins, login attempts (including what was typed), successes, timeouts, and advancements earned after logging in. |
| `admin_webhook_url` | none | Discord webhook for security: server start, stop and crash, every connect and disconnect, and advancements earned while still unauthenticated. |
| `login_title` | built in | Big title on the lockdown screen. Supports `§` codes. |
| `login_description` | built in | Subtitle under it. Supports `§` codes. |
| `timeout_seconds` | `180` | Kick an unauthenticated player after this many seconds of inactivity. |
| `geolocation_url` | `https://ipwho.is/` | Base URL for best-effort IP geolocation shown in Discord logs. Uses **HTTPS**. Leave **blank** to disable third-party IP lookups entirely (e.g. for privacy). |

### Example

```properties
password=komipu42!
webhook_url=https://discord.com/api/webhooks/xxx/yyy
admin_webhook_url=https://discord.com/api/webhooks/xxx/zzz
login_title=§6Welcome to my server!
login_description=§7Type /login <password> to join
timeout_seconds=180
```

Use `§` followed by a color/format code in `login_title` or `login_description` for styling (e.g. `§6` gold, `§l` bold). See https://minecraft.wiki/w/Formatting_codes for the full list.

## 🛡️ Attack Vectors Defended

Before they log in, players are frozen in place, made invincible, blinded, and put into spectator. On top of that, this mod also blocks:

- **Breaking blocks** — via hacked clients that send fake packets
- **Moving or teleporting away** — players are locked in position on every tick
- **External state drift & Mod interference** — lockdown effects are re-asserted every tick
- **Admin commands & OP powers** — all op permissions are taken away on lockdown
- **Minimized IP-spoofing** — successful logins are saved as a user UUID and IP pair
- **Same-account session hijacking** — if they're already logged in, a second login gets denied instead of kicking them
- **Bruteforcing** — a per-IP minimum interval between `/login` attempts caps brute-force speed; still kicked after 7 tries and kicked if idle too long. The per-IP throttle is keyed by IP, so it survives reconnects (you can't reset it by rejoining).
- **Connection flooding (DoS)** — a per-IP connection rate limit (token bucket) refuses connection floods at the door, and it too survives reconnects. The pending-session store is bounded and persisted off the main thread, so a flood of unique joins can't stall the server or grow memory/the session file without limit.

See [SECURITY.md](SECURITY.md) for the full threat model and the limits of each mitigation.

## 🔨 Future Development

Upon request I may add features to this mod. It runs on Fabric and Paper; other platforms may follow if others are interested.
