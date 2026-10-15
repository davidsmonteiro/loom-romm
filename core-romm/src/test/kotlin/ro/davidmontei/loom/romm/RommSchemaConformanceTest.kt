package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.api.SAVE_FILE_FIELD
import ro.davidmontei.loom.romm.dto.ArchiveMemberDto
import ro.davidmontei.loom.romm.dto.ClientSaveStateDto
import ro.davidmontei.loom.romm.dto.ClientTokenCreateDto
import ro.davidmontei.loom.romm.dto.ClientTokenExchangeRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenResponse
import ro.davidmontei.loom.romm.dto.DeviceCapabilitiesDto
import ro.davidmontei.loom.romm.dto.DeviceDto
import ro.davidmontei.loom.romm.dto.DeviceInstallDto
import ro.davidmontei.loom.romm.dto.DeviceSyncDto
import ro.davidmontei.loom.romm.dto.DeviceUpdateRequest
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.dto.InstallReportRequest
import ro.davidmontei.loom.romm.dto.InstallRequestDto
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.romm.dto.RomFileDto
import ro.davidmontei.loom.romm.dto.RomPageDto
import ro.davidmontei.loom.romm.dto.SaveDeviceRequest
import ro.davidmontei.loom.romm.dto.SaveDownloadedRequest
import ro.davidmontei.loom.romm.dto.SaveDto
import ro.davidmontei.loom.romm.dto.SyncCompleteRequest
import ro.davidmontei.loom.romm.dto.SyncCompleteResponseDto
import ro.davidmontei.loom.romm.dto.SyncNegotiateRequest
import ro.davidmontei.loom.romm.dto.SyncNegotiateResponseDto
import ro.davidmontei.loom.romm.dto.SyncOperationDto
import ro.davidmontei.loom.romm.dto.SyncSessionDto
import ro.davidmontei.loom.romm.dto.SystemDto
import ro.davidmontei.loom.romm.dto.UserDto

/**
 * Checks the hand-written DTOs and the Retrofit interface against the OpenAPI schema served by
 * RomM [ROMM_CONTRACT_VERSION] (snapshot in test resources). Re-generate the snapshot from a
 * server's `/openapi.json` when bumping the contract; this test then lists every drift. The
 * oldest snapshot kept, RomM 5.3.1's, checks that the response DTOs still read older servers.
 */
class RommSchemaConformanceTest {

    private val openApi = snapshot(ROMM_CONTRACT_VERSION)
    private val schemas = openApi.schemas()

    @Test fun snapshotIsTheContractVersion() {
        val version = openApi["info"]!!.jsonObject["version"]!!.jsonPrimitive.content
        assertWithMessage("snapshot version").that(version).isEqualTo(ROMM_CONTRACT_VERSION)
    }

    @Test fun responseDtosMatchSchemas() {
        for ((serializer, schemaName) in RESPONSES + RESPONSES_SINCE_5_4) check(serializer, schemaName)
    }

    /**
     * Loom still supports RomM 5.0 to 5.3, so a field it can't do without (neither optional nor
     * nullable in Kotlin) must be required, and never null, in 5.3.1's schema too. A field newer
     * RomMs added must be optional or nullable.
     */
    @Test fun responseDtosStillReadRomm531() {
        val older = snapshot("5.3.1").schemas()
        for ((serializer, schemaName) in RESPONSES) {
            val schema = older[schemaName]?.jsonObject
            assertWithMessage("5.3.1 has schema $schemaName").that(schema).isNotNull()
            val properties = schema!!["properties"]!!.jsonObject
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
            val d = serializer.descriptor
            for (i in 0 until d.elementsCount) {
                val name = d.getElementName(i)
                val where = "${d.serialName}.$name vs 5.3.1 $schemaName"
                val property = properties[name]?.jsonObject
                if (!d.getElementDescriptor(i).isNullable && !d.isElementOptional(i)) {
                    assertWithMessage("$where: must be required").that(required).contains(name)
                    assertWithMessage("$where: schema allows null, Kotlin doesn't")
                        .that(isNullable(older, property!!)).isFalse()
                }
                if (property != null) {
                    assertWithMessage("$where: JSON type").that(kindOf(d.getElementDescriptor(i)))
                        .isIn(schemaTypes(older, property))
                }
            }
        }
    }

    @Test fun requestDtosMatchSchemas() {
        check(DeviceAuthInitRequest.serializer(), "DeviceAuthInitPayload", isRequest = true)
        check(DeviceAuthTokenRequest.serializer(), "DeviceAuthTokenPayload", isRequest = true)
        check(ClientTokenExchangeRequest.serializer(), "ClientTokenExchangePayload", isRequest = true)
        check(SyncNegotiateRequest.serializer(), "SyncNegotiatePayload", isRequest = true)
        check(ClientSaveStateDto.serializer(), "ClientSaveState", isRequest = true)
        check(SyncCompleteRequest.serializer(), "SyncCompletePayload", isRequest = true)
        check(SaveDownloadedRequest.serializer(), "Body_confirm_download_api_saves__id__downloaded_post", isRequest = true)
        check(SaveDeviceRequest.serializer(), "Body_track_save_api_saves__id__track_post", isRequest = true)
        check(SaveDeviceRequest.serializer(), "Body_untrack_save_api_saves__id__untrack_post", isRequest = true)
        check(DeviceUpdateRequest.serializer(), "DeviceUpdatePayload", isRequest = true)
        check(InstallReportRequest.serializer(), "InstallRequestUpdatePayload", isRequest = true)
    }

    @Test fun saveUploadSendsTheFileInTheSchemasField() {
        val body = schemas["Body_add_save_api_saves_post"]!!.jsonObject
        assertWithMessage("required multipart fields").that(body["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly(SAVE_FILE_FIELD)
    }

    @Test fun everyApiMethodExistsInSchema() {
        val paths = openApi["paths"]!!.jsonObject
        for (method in RommApi::class.java.declaredMethods) {
            val (verb, annotated) = method.getAnnotation(GET::class.java)?.let { "get" to it.value }
                ?: method.getAnnotation(POST::class.java)?.let { "post" to it.value }
                ?: method.getAnnotation(PUT::class.java)?.let { "put" to it.value }
                ?: method.getAnnotation(DELETE::class.java)?.let { "delete" to it.value }
                ?: continue
            // A query fixed in the annotation ("…/content?optimistic=false") is checked like a @Query.
            val path = annotated.substringBefore('?')
            val fixedQuery = annotated.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') }
            val operation = paths["/$path"]?.jsonObject?.get(verb)?.jsonObject
            assertWithMessage("${method.name}: ${verb.uppercase()} /$path in schema").that(operation).isNotNull()
            val declared = operation!!["parameters"]?.jsonArray.orEmpty()
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet() +
                NEWER_QUERY_PARAMS["${verb.uppercase()} /$path"].orEmpty()
            val queryNames = method.parameterAnnotations.flatMap { it.toList() }
                .filterIsInstance<Query>().map { it.value } + fixedQuery
            for (name in queryNames) {
                assertWithMessage("${method.name}: query param '$name'").that(declared).contains(name)
            }
        }
    }

    /** Newer parameters must stay out of the snapshot: bumping it then shows they're now plain. */
    @Test fun newerQueryParamsAreNotInTheSnapshot() {
        val paths = openApi["paths"]!!.jsonObject
        for ((route, names) in NEWER_QUERY_PARAMS) {
            val (verb, path) = route.split(' ')
            val declared = paths[path]!!.jsonObject[verb.lowercase()]!!.jsonObject["parameters"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertWithMessage("$route already declares").that(declared).containsNoneIn(names)
        }
    }

    private fun check(serializer: KSerializer<*>, schemaName: String, isRequest: Boolean = false) {
        val schema = schemas[schemaName]?.jsonObject
        assertWithMessage("schema $schemaName exists").that(schema).isNotNull()
        val properties = schema!!["properties"]!!.jsonObject
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
        val d = serializer.descriptor
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            val where = "${d.serialName}.$name vs $schemaName"
            val property = properties[name]?.jsonObject
            assertWithMessage("$where: field exists").that(property).isNotNull()
            val element = d.getElementDescriptor(i)
            val schemaNullable = isNullable(schemas, property!!)
            if (isRequest) {
                // We may omit optional fields, but must always send required ones.
                if (name in required) {
                    assertWithMessage("$where: required request field must not be optional")
                        .that(d.isElementOptional(i)).isFalse()
                }
            } else {
                // A non-null Kotlin property must be guaranteed present and non-null by the server.
                if (!element.isNullable && !d.isElementOptional(i)) {
                    assertWithMessage("$where: must be required").that(required).contains(name)
                    assertWithMessage("$where: schema allows null, Kotlin doesn't").that(schemaNullable).isFalse()
                }
            }
            assertWithMessage("$where: JSON type").that(kindOf(element)).isIn(schemaTypes(schemas, property))
        }
    }

    private fun snapshot(version: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/romm-openapi-$version.json")!!.readText()).jsonObject

    private fun JsonObject.schemas(): JsonObject = this["components"]!!.jsonObject["schemas"]!!.jsonObject

    private fun isNullable(schemas: JsonObject, property: JsonObject): Boolean =
        schemaTypes(schemas, property).contains("null")

    /** JSON types a property allows, following anyOf and $ref (enums resolve to their type). */
    private fun schemaTypes(schemas: JsonObject, property: JsonObject): Set<String> = when {
        property["\$ref"] != null -> {
            val target = schemas[property["\$ref"]!!.jsonPrimitive.content.substringAfterLast('/')]!!.jsonObject
            if (target["type"] != null || target["anyOf"] != null) schemaTypes(schemas, target) else setOf("object")
        }
        property["anyOf"] != null -> property["anyOf"]!!.jsonArray.flatMap { schemaTypes(schemas, it.jsonObject) }.toSet()
        property["type"] is JsonPrimitive -> setOf(property["type"]!!.jsonPrimitive.content)
        property["type"] is JsonArray -> property["type"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        else -> setOf("object") // untyped {} accepts anything we'd send as an object
    }

    private fun kindOf(d: SerialDescriptor): String = when (d.kind) {
        PrimitiveKind.STRING -> "string"
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.BOOLEAN -> "boolean"
        StructureKind.LIST -> "array"
        StructureKind.MAP -> "object"
        else -> "object"
    }

    private companion object {
        /** Responses every supported RomM serves; [responseDtosStillReadRomm531] checks them too. */
        val RESPONSES: List<Pair<KSerializer<*>, String>> = listOf(
            HeartbeatDto.serializer() to "HeartbeatResponse",
            SystemDto.serializer() to "SystemDict",
            UserDto.serializer() to "UserSchema",
            PlatformDto.serializer() to "PlatformSchema",
            RomDto.serializer() to "SimpleRomSchema",
            RomDto.serializer() to "DetailedRomSchema",
            RomFileDto.serializer() to "RomFileSchema",
            ArchiveMemberDto.serializer() to "RomArchiveMember",
            RomPageDto.serializer() to "CustomLimitOffsetPage_SimpleRomSchema_",
            DeviceAuthInitResponse.serializer() to "DeviceAuthInitResponse",
            DeviceAuthTokenResponse.serializer() to "DeviceAuthTokenResponse",
            ClientTokenCreateDto.serializer() to "ClientTokenCreateSchema",
            SyncNegotiateResponseDto.serializer() to "SyncNegotiateResponse",
            SyncOperationDto.serializer() to "SyncOperationSchema",
            SyncCompleteResponseDto.serializer() to "SyncCompleteResponse",
            SyncSessionDto.serializer() to "SyncSessionSchema",
            SaveDto.serializer() to "SaveSchema",
            DeviceSyncDto.serializer() to "DeviceSyncSchema",
            DeviceDto.serializer() to "DeviceSchema",
            DeviceCapabilitiesDto.serializer() to "DeviceSchema",
        )

        /** Responses only RomM 5.4 serves, to calls Loom makes once it knows the server is 5.4. */
        val RESPONSES_SINCE_5_4: List<Pair<KSerializer<*>, String>> = listOf(
            InstallRequestDto.serializer() to "InstallRequestSchema",
            DeviceInstallDto.serializer() to "DeviceInstallDict",
        )

        /**
         * Query parameters Loom sends for a newer RomM. FastAPI ignores parameters a route doesn't
         * declare, so the contract version accepts them unread.
         */
        val NEWER_QUERY_PARAMS = emptyMap<String, Set<String>>()
    }
}
