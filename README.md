# FoAuction

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/foauction) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/foauction/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

A player-friendly auction house with search, claims, history, and admin controls.

*Developed by Carrotio.*

## Features

- Runs a GUI auction house with listings, previews, search, and seller views.
- Lets players sell items, browse claims, and inspect their own listings.
- Supports sorting, filtering, price limits, listing fees, and per-player slot limits.
- Uses Vault for economy transactions and keeps sale history for administrators.
- Includes item blacklists, optional Discord webhooks, and PlaceholderAPI statistics.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/foauction). Check the [versions page](https://modrinth.com/plugin/foauction/versions) for supported Minecraft versions and server platforms.

- **Required:** `Vault`
- **Optional integrations:** `PlaceholderAPI`

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/auction [sell <price>|claims|search <text>|view [player]]` — Main FoAuction command Aliases: `/foauction`, `/ah`.
- `/foauctionadmin <version|reload|history <player>|editor>` — FoAuction admin command Aliases: `/auctionadmin`.

### Permissions

- `foauction.use` — Allows use of FoAuction player commands Default: `true`.
- `foauction.admin` — Allows use of FoAuction admin commands Default: `op`.
- `foauction.admin.return` — Allows admins to remove a listing and return it to seller claims Default: `op`.
- `foauction.admin.take` — Allows admins to remove a listing and take the item Default: `op`.
- `foauction.admin.*` — Wildcard admin permission Default: `op`.
- `foauction.slots.*` — Wildcard listing slots permission Default: `false`.
- `foauction.slots.1` — Allows 1 active listing Default: `false`.
- `foauction.slots.3` — Allows 3 active listings Default: `false`.
- `foauction.slots.5` — Allows 5 active listings Default: `false`.
- `foauction.slots.10` — Allows 10 active listings Default: `false`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

Requires PlaceholderAPI.

- `%foauctions_sell_limit%` — Maximum active listings allowed for the player.
- `%foauctions_sell_count%` — Player's current listing count.
- `%foauctions_expired_count%` — Expired items waiting in claims.
- `%foauctions_active_count%` — Player's active listing count.
- `%foauctions_sort_name%` — Player's selected sort mode.
- `%foauctions_filter_name%` — Player's selected filter mode.
- `%foauctions_total_sold%` — Player's completed sale count.
- `%foauctions_total_earned% / %foauctions_total_earned_raw%` — Formatted or raw lifetime earnings.
- `%foauctions_min_price% / %foauctions_min_price_raw%` — Formatted or raw minimum listing price.
- `%foauctions_max_price% / %foauctions_max_price_raw%` — Formatted or raw maximum listing price.
- `%foauctions_listing_fee% / %foauctions_listing_fee_raw%` — Formatted or raw listing fee percentage.
- `%foauctions_total_listings%` — Total listings currently stored.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.
- `dialogs/*.yml` — Native dialog layouts and text.
- `guis/*.yml` — GUI layouts, slots, labels, and lore.

Main config areas: `file-logging`, `native-dialogs`, `auction`, `gui`, `blacklisted-items`, `discord-webhook`, `history`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

This plugin does not expose a public developer API.

</details>
