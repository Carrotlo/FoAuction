# Changelog

## 3.4.0-SNAPSHOT

- Add a cache-only API for bounded, compare-and-replace updates of listed and
  unclaimed items, preserving transaction metadata and quantity.
- Refresh both stored and indexed item copies and queue native persistence.
- Reject purchases from screens rendered before a listing item update, before
  charging the buyer. Add a configurable refresh message with missing-key migration.
- Add regression tests for record preservation and rejected stale/invalid updates.
