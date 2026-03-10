# One Password Auth

A lightweight Fabric server-side mod that protects your server with a single shared password. Designed for small friend groups and private communities—no per-user accounts, no complex authentication schemes, just one password everyone shares.

## ✅ Core Features

- Single shared password for entire server access
- Secures all players/inventories before authenticating, even those with OP access
- Players authenticate once with `/login <password>` then they're IP whitelisted
- Discord webhook integration for event logging

## 🎯 Perfect For

- Close-knit friend groups sharing a Minecraft server
- Private builds that need simple, centralized access control
- Communities wanting zero authentication friction
- Servers that prioritize simplicity over per-user security

## Getting Started

Create a properties file in `config/auth_config.properties` with the following: (This otherwise will be generated once the plugin is loaded)

```
#Auth Mod Config
password=
webhook_url=
admin_webhook_url=
timeout_seconds=180
```

Restart the server and thats it! The next time anyone logs in, they will be greeted with a login dialogue.

# Security

Since this is IP whitelisted, the major risk is IP exposure/spoofing!

**TODO:**

- Feature improvement: make IP whitelist restricted to the user account for that IP. Require reauthenticating per user account (in the case that an IP somehow gets exposed)
