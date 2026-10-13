package ro.davidmontei.loom.romm

import kotlinx.serialization.json.Json

/** JSON settings for RomM: tolerate fields added by newer servers, never emit nulls we didn't set. */
val RommJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
