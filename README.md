# FoAuction

Auction house by Carrotio. The upstream license remains unchanged.

## Stored item maintenance API (3.4.0-SNAPSHOT)

Plugins with a `softdepend: [FoAuction]` can obtain `getStoredItemApi()` after
FoAuction enables. This API updates existing item payloads without creating sales,
claims, rewards or payments. nStaffX is one consumer; the API has no staff-specific dependency.

Consume `cachedOwners()` on the server thread. For each owner, read LISTING and
CLAIM pages with `readItems(owner, kind, offset, limit)` (maximum 32 entries).
Transform cloned items outside FoAuction, then submit a maximum of 32
`Replacement(expectedSnapshot, replacementItem)` entries to `replaceItems`.
Schedule pages across ticks. Do not perform network or disk work in this API.

The replacement retains quantity, record ID, seller/owner, price, creation time,
expiry and claim notes. Money claims and sold history are excluded. Invalid,
changed, moved, missing and pending-purchase entries are rejected individually.
No uncached owner is loaded from disk. Cache and purchase index changes use the
auction transaction lock. One existing asynchronous owner save is queued per
changed batch; UPDATED does not mean disk persistence has completed.

Offsets are not stable under trades/claims between pages. Re-read on a later scan
after a stale result; never force-write a stale snapshot. The native persistence
layer snapshots the whole owner's data, so a 32-entry API batch does not impose a
hard time bound on that existing save operation for exceptionally large accounts.

Native buy screens capture a shared item revision. If any listing is updated,
an older screen cannot debit money: it reports `gui.item-updated` and refreshes.
This intentionally may refresh an unrelated listing too. Third-party purchase
callers should use the `buyListing(player, id, expectedRevision)` overload for the
same protection. The legacy two-argument overload remains compatible.

## Build and verification

Java 21, Maven: `mvn -Pitem-api-tests clean verify`. The test profile aligns Paper
with the pinned MockBukkit 1.21.11 runtime; the default upstream compile API remains
1.21.10. This requires the author's existing private
`me.foesio:fo-plugin-core:1.4` dependency. That dependency is not vendored here.
Tests use JUnit and MockBukkit to verify compare-and-replace, immutable snapshots,
record preservation, quantity/money protection, bounded pages, no cache-miss I/O,
queued saves and stale-screen rejection before economy access.

Local verification additionally compiled all source against the exact FoAuction
3.3 bundled core (rewriting core imports only in ignored build scratch files).
That fallback candidate is not proof of a clean Maven dependency-resolved build.
Live purchase, claim, restart persistence and viewer-refresh checks are still needed.

Install with a full server restart after backing up the auction database. No
database schema change or new configuration deletion is required. Existing custom
messages are preserved and the new message is added when absent.
