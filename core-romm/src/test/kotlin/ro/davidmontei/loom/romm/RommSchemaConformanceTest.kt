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
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.ArchiveMemberDto
import ro.davidmontei.loom.romm.dto.ClientTokenCreateDto
import ro.davidmontei.loom.romm.dto.ClientTokenExchangeRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenResponse
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.romm.dto.RomFileDto
import ro.davidmontei.loom.romm.dto.RomPageDto
import ro.davidmontei.loom.romm.dto.SystemDto
import ro.davidmontei.loom.romm.dto.UserDto

/**
 * Checks the hand-written DTOs and the Retrofit interface against the OpenAPI schema served by
 * RomM [ROMM_CONTRACT_VERSION] (snapshot in test resources). Re-generate the snapshot from a
 * server's `/openapi.json` when bumping the contract; this test then lists every drift.
 */
class RommSchemaConformanceTest {

    private val openApi: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/romm-openapi-$ROMM_CONTRACT_VERSION.json")!!.readText(),
    ).jsonObject
    private val schemas = openApi["components"]!!.jsonObject["schemas"]!!.jsonObject

    @Test fun snapshotIsTheContractVersion() {
        val version = openApi["info"]!!.jsonObject["version"]!!.jsonPrimitive.content
        assertWithMessage("snapshot version").that(version).isEqualTo(ROMM_CONTRACT_VERSION)
    }

    @Test fun responseDtosMatchSchemas() {
        check(HeartbeatDto.serializer(), "HeartbeatResponse")
        check(SystemDto.serializer(), "SystemDict")
        check(UserDto.serializer(), "UserSchema")
        check(PlatformDto.serializer(), "PlatformSchema")
        check(RomDto.serializer(), "SimpleRomSchema")
        check(RomDto.serializer(), "DetailedRomSchema")
        check(RomFileDto.serializer(), "RomFileSchema")
        check(ArchiveMemberDto.serializer(), "RomArchiveMember")
        check(RomPageDto.serializer(), "CustomLimitOffsetPage_SimpleRomSchema_")
        check(DeviceAuthInitResponse.serializer(), "DeviceAuthInitResponse")
        check(DeviceAuthTokenResponse.serializer(), "DeviceAuthTokenResponse")
        check(ClientTokenCreateDto.serializer(), "ClientTokenCreateSchema")
    }

    @Test fun requestDtosMatchSchemas() {
        check(DeviceAuthInitRequest.serializer(), "DeviceAuthInitPayload", isRequest = true)
        check(DeviceAuthTokenRequest.serializer(), "DeviceAuthTokenPayload", isRequest = true)
        check(ClientTokenExchangeRequest.serializer(), "ClientTokenExchangePayload", isRequest = true)
    }

    @Test fun everyApiMethodExistsInSchema() {
        val paths = openApi["paths"]!!.jsonObject
        for (method in RommApi::class.java.declaredMethods) {
            val (verb, path) = method.getAnnotation(GET::class.java)?.let { "get" to it.value }
                ?: method.getAnnotation(POST::class.java)?.let { "post" to it.value }
                ?: continue
            val operation = paths["/$path"]?.jsonObject?.get(verb)?.jsonObject
            assertWithMessage("${method.name}: ${verb.uppercase()} /$path in schema").that(operation).isNotNull()
            val declared = operation!!["parameters"]?.jsonArray.orEmpty()
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            val queryNames = method.parameterAnnotations.flatMap { it.toList() }
                .filterIsInstance<Query>().map { it.value }
            for (name in queryNames) {
                assertWithMessage("${method.name}: query param '$name'").that(declared).contains(name)
            }
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
            val schemaNullable = isNullable(property!!)
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
            assertWithMessage("$where: JSON type").that(kindOf(element)).isIn(schemaTypes(property))
        }
    }

    private fun isNullable(property: JsonObject): Boolean =
        schemaTypes(property).contains("null")

    /** JSON types a property allows, following anyOf and $ref (enums resolve to their type). */
    private fun schemaTypes(property: JsonObject): Set<String> = when {
        property["\$ref"] != null -> {
            val target = schemas[property["\$ref"]!!.jsonPrimitive.content.substringAfterLast('/')]!!.jsonObject
            if (target["type"] != null || target["anyOf"] != null) schemaTypes(target) else setOf("object")
        }
        property["anyOf"] != null -> property["anyOf"]!!.jsonArray.flatMap { schemaTypes(it.jsonObject) }.toSet()
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
}
