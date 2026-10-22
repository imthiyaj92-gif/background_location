# Native Android trip and alarm contract

Channel: `com.almoullim.background_location/methods`.
Existing location and notification APIs are unchanged. These methods are Android-only.

| Method | Arguments | Behavior |
| --- | --- | --- |
| `startTrip` | `tripId`, `destinationName` (nonblank strings), `destinationLatitude`, `destinationLongitude`, `radiusMeters` (finite numbers; positive radius) | Validate, persist and request the existing foreground location service. Permissions must already be granted. |
| `getTripState` | none | Read persisted state, without starting tracking or requiring a Flutter callback. |
| `stopTrip` | `tripId` | Persist STOPPED, then stop native audio and use existing native GPS/notification cleanup. A mismatched ID is rejected. |

Each success returns `{trip: <map or null>, serviceRunning: <bool>}`.
`trip` is null before the first start. Otherwise it includes `schemaVersion: 1`,
the five configuration fields, `phase`, `createdAtMs`, `updatedAtMs`,
`lastDistanceMeters`, `lastLocationTimeMs`, and `stopReason`.
Distances/radius are metres; timestamps are Unix milliseconds.
Fresh, sufficiently accurate GPS fixes update distance and coordinates. Crossing the radius persists RINGING once and starts the service-owned looping MediaPlayer with USAGE_ALARM. The sound is bundled in Android raw resources. Audio status/errors are persisted and logged; preparation failures fall back to the system alarm with bounded retries.

TRACKING represents persisted intent. `startTrip` acknowledges storage and an
accepted service-start request, not successful GPS registration. `serviceRunning`
is an observation of the foreground service, not proof of GPS delivery; it can
still be false while Android processes a queued start. Native starts subscribe to GPS without a Flutter binding. Sticky service recreation restores the persisted active phase, including RINGING. The Flutter UI mirrors state on launch/resume and must not run its former Android radius callback or page audio. Force-stop, reboot and OEM service suppression are not overcome; physical swipe-away and screen-lock testing is required.

Repeating an active ID with identical settings returns the snapshot without queuing a duplicate start; a missing service may be restarted. Changed settings
with that ID fail. A different active trip must be stopped first. Reusing the
last stopped ID fails: use a fresh unique ID for every new trip. A queued start
for a stopped/replaced trip is ignored. IDs are checked against the current
snapshot; this is not a historical registry of all previous trips.

EXIT ALARM and the legacy stop-service path also persist STOPPED when a trip
exists. The existing EXIT cleanup still runs if storage fails. `onDestroy()`
does not cancel the persisted trip. No-trip legacy use remains supported.

Errors use Flutter PlatformException: INVALID_ARGUMENT, permissionError,
TRIP_ID_CONFLICT, ACTIVE_TRIP_EXISTS, TRIP_ALREADY_STOPPED, TRIP_ID_MISMATCH,
TRIP_STATE_CORRUPT, TRIP_PERSIST_FAILED, TRIP_START_FAILED, NO_CONTEXT, or
TRIP_OPERATION_FAILED. Storage uses one versioned JSON snapshot in private
SharedPreferences with synchronous commit; a failed commit is reported.
No existing Flutter App State preferences are changed or migrated.


Local testing uses a pubspec_overrides.yaml path dependency to this uncommitted checkout. The remote Git dependency is unchanged until separate commit/push approval. Notification sound is silent; only the native player owns alarm audio. EXIT ALARM and I'M AWAKE both stop that owner.
