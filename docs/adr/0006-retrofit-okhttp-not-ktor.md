# Retrofit + OkHttp + kotlinx.serialization rather than Ktor

The brief left the HTTP client open. Loom uses Retrofit 3 on OkHttp 5 with kotlinx.serialization: the acceptance tests use MockWebServer, which is OkHttp's own; resumable Range downloads need OkHttp-level control (identity encoding, If-Range, streaming to disk) that a Ktor engine would wrap anyway; Coil 3 shares the same OkHttp client for cover art; and Retrofit's interface reads one-to-one against RomM's OpenAPI paths.
