# Security model & assumed risks

One Password Auth trades per-user identity for simplicity: one shared password,
plus an `(IP, UUID)` whitelist so returning players skip the prompt. That is a
good fit for a small, trusted group — but it is **not** an account system, and
this document is the honest version of the trade-offs before you expose a server
more widely.

## Trust model

The security boundary is **the shared password**. Anyone who knows it is treated
as fully trusted. There is no per-user identity, no per-user revocation, and no
record of *who* used the password — only *that* the correct password was entered
from a given IP and name.

## Assumed risks

### 1. The password is a single shared secret
- One leak compromises everyone; you can't revoke one person without changing the
  password for all.
- It is stored readably in the config so you can share it. Anyone with host /
  filesystem access can read it — but at that point they already control the
  server.
- **Mitigation:** rotate it periodically; keep the config file and any Discord
  webhook channel private; don't paste it anywhere it can be screenshotted.
  Changing the password does **not** retroactively lock out already-whitelisted
  `(IP, UUID)` pairs — delete the whitelist file if you need a hard reset.

### 2. On an offline server, identity is the source IP plus a forgeable name
On an offline (cracked) server a player's UUID is derived deterministically from
their username, so usernames — and therefore UUIDs — are public and forgeable.
The one thing an attacker can't trivially forge is the **source IP of a TCP
connection** (blind spoofing doesn't survive the TCP handshake). So the whitelist
really trusts *a network location*, not a person. Where that is weaker than it
looks:
- **Shared egress IP.** Anyone behind the same NAT as a whitelisted player — same
  house / LAN, the same mobile carrier or CGNAT, or the same VPN exit — can
  connect under that player's username and be let in with no password.
- **Recycled IPs.** Residential IPs change hands, and whitelist entries never
  expire, so a future holder of an IP, using the old username, matches.
- **Not a realistic remote vector:** harvesting a player's IP (e.g. luring them to
  a link) and then "spoofing" it to connect — TCP requires completing a handshake
  at that address, so it doesn't work without on-path control of the victim's
  network.

**Mitigation:** prefer **online mode** (Mojang-authenticated identities) if your
use case allows it — that removes username forgery entirely. If you must run
offline, treat the whitelist as "trusted networks," not "trusted people," and
delete the whitelist file to reset.

### 3. Operator status is restored on login
If a player who is an operator logs in successfully, their op is restored.
Combined with risk #2, a password-holder who connects under an operator's username
regains that operator's powers. This is intended for the trust model (everyone
with the password is trusted), but it means op level is **not** a security
boundary between people who share the password.

**Mitigation:** don't use op level to separate trusted-but-not-admin users; use
online mode if you need that separation.

### 4. There is no client-side credential without a client mod
A vanilla client keeps no server-specific secret across a disconnect (Minecraft's
"cookie" mechanism is cleared on a normal disconnect and only survives proxy
*transfers* between servers in a network), so a true per-client login token would
require a client-side mod. The IP whitelist is the deliberate no-client-mod
substitute, with the trade-offs in risk #2.

## What the mod does defend

- **World griefing before login:** unauthenticated players are held in spectator,
  invulnerable, blind, and frozen, and block-breaking is additionally cancelled.
- **Automated abuse / flooding:** per-IP connection and login-attempt rate
  limiting that survives reconnects, a bounded pending-session store, and a
  login-attempt cap, so a connection flood can't exhaust memory, disk, or the
  main server thread.
- **Online-session takeover:** a second connection for an already-authenticated
  UUID is refused.
- **Webhook abuse:** player-supplied text cannot ping your Discord
  (`@everyone` / `@here` / role mentions are neutralized), though it is still
  logged — by design.
- **IP privacy:** geolocation lookups use HTTPS and can be disabled entirely by
  leaving `geolocation_url` blank, so you need not send player IPs to any third
  party.

## Reporting

Found something not covered here? Report it privately to the maintainer of the
server / this mod rather than opening a public issue with exploit details.
