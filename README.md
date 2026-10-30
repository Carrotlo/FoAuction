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

Permanent auction slot purchases use `auction.gem-slots.enabled` (default `false`). Existing
configs gain this setting as `false` without changing other values. Set it to `true` and reload
FoAuction to allow new purchases through PlayerPoints. If PlayerPoints is unavailable, the shop
stays disabled and admins receive a warning on join or `/foauctionadmin reload`. Purchased slots
remain active when the shop is disabled.

</details>

<details>
<summary><strong>Developer API</strong></summary>

### Maven dependency example

Install the matching `FoAuction-3.5.jar` locally if it is not in your Maven repository. These coordinates do not claim a published artifact. Use `provided` scope so the server supplies FoAuction:

```xml
<dependency>
  <groupId>me.foesio</groupId>
  <artifactId>FoAuction</artifactId>
  <version>3.5</version>
  <scope>provided</scope>
</dependency>
```

FoAuction registers `me.foesio.foAuction.api.AuctionApi` with Bukkit's `ServicesManager` after auction data loads. It unregisters the service on disable. Public API and event types live in FoAuction's own packages, outside its relocated FoPluginCore code. Compile against the matching FoAuction jar as `provided` or `compileOnly`; do not bundle another copy. This checkout builds Java 21 bytecode. The jar is produced locally by `mvn clean package`; no public API artifact repository is promised.

For a `plugin.yml` consumer, add `softdepend: [FoAuction]` (or `depend` if required). Load a class that directly references FoAuction types only after checking that FoAuction is enabled. Reacquire the service after a disable/re-enable; a cached service rejects reads and returns `UNAVAILABLE` for writes while disabled.

```java
// Inside an integration class loaded only when FoAuction is enabled:
RegisteredServiceProvider<AuctionApi> registration = Bukkit.getServicesManager()
        .getRegistration(AuctionApi.class);
if (registration == null) return;
AuctionApi auctions = registration.getProvider();
List<AuctionListingSnapshot> firstPage = auctions.getActiveListings(0, 20);
```

Imports: `me.foesio.foAuction.api.AuctionApi`, `AuctionListingSnapshot`, `org.bukkit.Bukkit`, `org.bukkit.plugin.RegisteredServiceProvider`, and `java.util.List`.

### Queries and creation

- `findActiveListing(UUID)` returns an active listing or empty. Expired listings are excluded even before the periodic purge.
- `getActiveListings(offset, limit)` returns a detached page, newest first. Offset is nonnegative; limit is 1–100. Pages can move as the market changes.
- Snapshot fields are listing ID, seller UUID, item, price, creation and expiry time in epoch milliseconds, display name, and `virtualSeller`. The returned item is a defensive copy.
- `createListingFromHand(Player, price)` uses the normal player sell path: permission, price, blacklist, effective slot limit, fee, and removal of the main-hand item. Use this for player-owned inventory items.
- `createListingForPlayer(Player, ItemStack, price)` applies the same player checks and fee to an item supplied by the caller. FoAuction clones that item **but does not remove it from any inventory**. The caller must escrow/remove its source item before the call and restore it if the result fails. The player must be online.
- `createListingForNpc(name, ItemStack, price)` creates a virtual seller. Names are 1–32 ASCII letters, digits, spaces, `_`, `.`, or `-` (case-insensitive identity). NPC listings use the same price and item blacklist checks, but no player slot or listing fee. The caller must escrow/remove the source item before the call and restore it if creation fails. Buyer money is withdrawn and not deposited elsewhere. Expired NPC items are discarded; admins can **Take** such listings but cannot send them to nonexistent seller claims. NPC identity and name persist in `userdata.db`.

All creation methods return `CreateListingResult` with a status and, on `SUCCESS`, a snapshot with the new listing ID. Denied, cancelled, or invalid requests create no listing. Do not retry a successful request blindly: no idempotency key is provided. A player seller receives sale proceeds through Vault, including when another plugin supplied the listed item.

```java
CreateListingResult result = auctions.createListingForNpc(
        "Village Trader", new ItemStack(Material.DIAMOND, 1), 250.0);
if (result.status() == CreateListingResult.Status.SUCCESS) {
    UUID listingId = result.listing().id();
}
```

For Paper plugin metadata, declare `FoAuction` under `dependencies.server` and ensure its classes are on the consumer's classpath (`join-classpath: true` where required). Optional integrations still need a provider-presence check before loading directly referencing classes.

### Bukkit events

All events are in `me.foesio.foAuction.api.event`. They execute on the thread that made the change; there is no automatic scheduler hop. Player transactions come from the player's server execution context; periodic expiry comes from FoAuction's global scheduler. On regionized servers, do not assume a listener is on Bukkit's primary thread. The Bukkit asynchronous flag is set from `Bukkit.isPrimaryThread()` at dispatch. Schedule world or player work onto its owning thread. Do not call these APIs from a general async worker.

| Event | Timing and meaning |
| --- | --- |
| `ListingPreCreateEvent` | Cancellable. After item, price, blacklist, slot, and initial fee checks (as applicable); before fee withdrawal, item removal, or listing storage. `getSellerPlayer()` is null for NPCs. Item is copied. |
| `ListingCreatedEvent` | Non-cancellable. After listing enters the in-memory market and a database save is attempted; before webhook and player success feedback. Includes actual listing fee (zero for NPCs). |
| `ListingPrePurchaseEvent` | Cancellable. After listing, expiry, ownership, price, and initial buyer balance checks; before market removal, withdrawal, payout, or delivery. A cancelled purchase leaves the listing available. |
| `ListingPurchasedEvent` | Non-cancellable. After buyer withdrawal, player-seller payout when applicable, item delivery, history update, and database save attempts; before webhook and buyer GUI feedback. Includes delivery result. For NPCs, the withdrawn money leaves circulation. |
| `ListingEndedEvent` | Non-cancellable. Reports unsold endings: seller cancellation, expiry, admin return to seller claims, or admin take. Fires after removal and item disposition, following a database save attempt. `getActorId()` is null for expiry. A purchase uses `ListingPurchasedEvent` instead. |

The post events report completed **in-memory** outcomes and attempted saves. Current SQLite write failures are logged internally, so these events do not certify durable disk persistence. No event fires when startup merely loads old listings, a request fails validation, or a purchase is rolled back. A lazy expiry during a purchase attempt does fire `ListingEndedEvent(EXPIRED)`.

`ListingEndedEvent` cannot be cancelled because the item may already have been returned or delivered. To block listing creation or purchase, cancel the respective pre event. A cancelled pre event has no fee, withdrawal, delivery, or listing mutation. Pre-event listeners must treat item and listing values as read-only; use cancellation to reject, not mutation to rewrite them.

### Event listener example

Inside a Bukkit `Listener` registered by your plugin (import this event class, `org.bukkit.event.EventHandler`, and `org.bukkit.Bukkit` where used):

```java
@EventHandler
public void onListingPrePurchase(ListingPrePurchaseEvent event) {
    Bukkit.getLogger().info("Auction purchase attempted: " + event.getListing().id());
}
```

### Complete public API method index

Signatures use short type names; import Fo types from this plugin's `api` package and Bukkit types from Bukkit.

#### `AuctionApi`

```java
Optional<AuctionListingSnapshot> findActiveListing(UUID);
List<AuctionListingSnapshot> getActiveListings(int, int);
CreateListingResult createListingFromHand(Player, double);
CreateListingResult createListingForPlayer(Player, ItemStack, double);
CreateListingResult createListingForNpc(String, ItemStack, double);
```

**Other public contract types:** `AuctionListingSnapshot`, `CreateListingResult`.

### Complete Bukkit event index

- `ListingCreatedEvent`.
- `ListingEndedEvent`.
- `ListingPreCreateEvent`.
- `ListingPrePurchaseEvent`.
- `ListingPurchasedEvent`.

</details>
