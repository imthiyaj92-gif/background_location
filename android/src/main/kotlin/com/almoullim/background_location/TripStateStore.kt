package com.almoullim.background_location

import android.content.Context
import org.json.JSONObject

internal class TripStateException(val code: String, message: String) : RuntimeException(message)

/** Durable trip intent. Service liveness is reported separately by the channel. */
internal class TripStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        internal const val PREFERENCES = "map_alarm_trip_state"
        private const val KEY = "trip"
        private val lock = Any()

        fun configuration(arguments: Any?): Map<String, Any> {
            val args = arguments as? Map<*, *>
                ?: throw TripStateException("INVALID_ARGUMENT", "Expected a trip argument map")
            fun text(key: String): String {
                val value = args[key] as? String
                if (value.isNullOrBlank()) throw TripStateException("INVALID_ARGUMENT", "$key must be nonblank")
                return value
            }
            fun number(key: String): Double {
                val value = (args[key] as? Number)?.toDouble()
                if (value == null || !value.isFinite()) {
                    throw TripStateException("INVALID_ARGUMENT", "$key must be a finite number")
                }
                return value
            }
            val latitude = number("destinationLatitude")
            val longitude = number("destinationLongitude")
            val radius = number("radiusMeters")
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || radius <= 0.0) {
                throw TripStateException("INVALID_ARGUMENT", "Coordinates are out of range or radiusMeters is not positive")
            }
            return linkedMapOf(
                "tripId" to text("tripId"),
                "destinationName" to text("destinationName"),
                "destinationLatitude" to latitude,
                "destinationLongitude" to longitude,
                "radiusMeters" to radius,
            )
        }
    }

    fun get(): Map<String, Any?>? = synchronized(lock) { read() }

    /** The same active ID/configuration is idempotent. A stopped ID cannot restart. */
    fun start(arguments: Any?): Map<String, Any?> = synchronized(lock) {
        val config = configuration(arguments)
        val current = read()
        if (current != null) {
            if (current["tripId"] == config["tripId"]) {
                if (config.any { (key, value) -> current[key] != value }) {
                    throw TripStateException("TRIP_ID_CONFLICT", "This tripId already has different settings")
                }
                if (current["phase"] == "STOPPED") {
                    throw TripStateException("TRIP_ALREADY_STOPPED", "Use a new tripId to start another trip")
                }
                return@synchronized current
            }
            if (current["phase"] != "STOPPED") {
                throw TripStateException("ACTIVE_TRIP_EXISTS", "Stop the active trip before starting a different trip")
            }
        }
        val now = System.currentTimeMillis()
        val state = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            *config.toList().toTypedArray(),
            "phase" to "TRACKING",
            "createdAtMs" to now,
            "updatedAtMs" to now,
            "lastDistanceMeters" to null,
            "lastLocationTimeMs" to null,
            "stopReason" to null,
        )
        write(state)
        state
    }

    /** Null expectedTripId is reserved for native/legacy stop of the current trip. */
    fun stop(expectedTripId: String? = null, reason: String = "SERVICE_STOP"): Map<String, Any?>? = synchronized(lock) {
        val current = read() ?: return@synchronized null
        if (expectedTripId != null && current["tripId"] != expectedTripId) {
            throw TripStateException("TRIP_ID_MISMATCH", "A different trip is current; nothing was stopped")
        }
        if (current["phase"] == "STOPPED") return@synchronized current
        val stopped = current.toMutableMap().apply {
            put("phase", "STOPPED")
            put("audioStatus", "STOPPED")
            put("updatedAtMs", System.currentTimeMillis())
            put("stopReason", reason)
        }
        write(stopped)
        stopped
    }

    /** Accept only this trip's fresh, accurate fixes; persist before starting audio. */
    fun location(tripId: String, location: android.location.Location): Map<String, Any?>? = synchronized(lock) {
        val current = read() ?: return@synchronized null
        if (current["tripId"] != tripId || current["phase"] == "STOPPED") return@synchronized null
        val radius = current["radiusMeters"] as Double
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs !in 0..120_000 || !location.hasAccuracy() || !location.accuracy.isFinite() ||
            location.accuracy > maxOf(50.0, radius / 2) ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return@synchronized null
        val previousTime = (current["lastLocationTimeMs"] as? Number)?.toLong() ?: 0L
        if (location.time < (current["createdAtMs"] as Number).toLong() || location.time <= previousTime) return@synchronized null
        val result = FloatArray(1)
        android.location.Location.distanceBetween(location.latitude, location.longitude,
            current["destinationLatitude"] as Double, current["destinationLongitude"] as Double, result)
        val distance = result[0].toDouble()
        if (!distance.isFinite()) return@synchronized null
        val next = current.toMutableMap().apply {
            put("lastLatitude", location.latitude)
            put("lastLongitude", location.longitude)
            put("lastDistanceMeters", distance)
            put("lastLocationTimeMs", location.time)
            put("updatedAtMs", System.currentTimeMillis())
            if (current["phase"] == "TRACKING" && distance <= radius) {
                put("phase", "RINGING")
                put("triggeredAtMs", System.currentTimeMillis())
                put("audioStatus", "REQUESTED")
            }
        }
        write(next)
        next
    }

    fun audio(tripId: String, status: String, error: String? = null) = synchronized(lock) {
        val current = read() ?: return@synchronized
        if (current["tripId"] != tripId || current["phase"] != "RINGING") return@synchronized
        write(current.toMutableMap().apply {
            put("audioStatus", status)
            put("audioError", error)
        })
    }

    private fun read(): Map<String, Any?>? {
        val raw = preferences.getString(KEY, null) ?: return null
        try {
            val json = JSONObject(raw)
            if (json.getInt("schemaVersion") != 1) throw IllegalStateException("Unsupported schema")
            val map = json.keys().asSequence().associateWith { key ->
                json.get(key).let { if (it == JSONObject.NULL) null else it }
            }.toMutableMap()
            // JSON may decode whole-valued doubles as integers. Normalize contract types.
            configuration(map).forEach { (key, value) -> map[key] = value }
            if (map["phase"] !in listOf("TRACKING", "RINGING", "STOPPED")) {
                throw IllegalStateException("Invalid phase")
            }
            map["createdAtMs"] = json.getLong("createdAtMs")
            map["updatedAtMs"] = json.getLong("updatedAtMs")
            return map
        } catch (error: Exception) {
            throw TripStateException("TRIP_STATE_CORRUPT", "Stored trip could not be read: ${error.message}")
        }
    }

    private fun write(state: Map<String, Any?>) {
        val json = JSONObject()
        state.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        if (!preferences.edit().putString(KEY, json.toString()).commit()) {
            throw TripStateException("TRIP_PERSIST_FAILED", "Android could not persist trip state")
        }
    }
}
