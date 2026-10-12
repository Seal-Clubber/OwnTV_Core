# Android TV v1 snapshot

Saved on 12 October 2026 for the Seal-Clubber forks. The `v1` branch records this baseline; continue development on `main` and leave `v1` at this commit.

This snapshot adds persistent Live TV custom-category rules for whole provider groups, individual channel inclusions and exclusions. Dynamic membership follows provider refreshes, supports existing ordering/search, and preserves provider channels. It includes Room schema 48 and migration, source/profile remapping for backup restore, deletion/tombstone handling, and English, Dutch and Turkish category strings.

Sports support adds channel and stored-guide queries used by the TV app, including list-scoped search. Selected lists combine their channels; unavailable or empty scopes never expand into all channels.

Use this repository together with [Seal-Clubber/OwnTV at v1](https://github.com/Seal-Clubber/OwnTV/tree/v1). Follow that repository's `docs/v1-snapshot.md` for the paired build. OwnTV_Mobile was not modified.

Existing regression tests, public fixture data where applicable, SQL checks and the updated literal inventories are included. The latest TV debug APK was built successfully against this source. No additional tests were run for this snapshot commit, as requested by the user.
