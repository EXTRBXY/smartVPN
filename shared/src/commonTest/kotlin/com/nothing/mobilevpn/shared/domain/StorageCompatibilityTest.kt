package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Профили и политика хранятся как JSON. Переход String → enum не должен менять формат
 * и не должен делать нечитаемыми ранее сохранённые данные.
 */
class StorageCompatibilityTest {
    // Те же настройки, что у ProfileRepositoryAndroid.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyProfileJson_isReadWithEnums() {
        val legacy = """[{"id":"a","name":"n","uuid":"","host":"h","port":443,"type":"ws","security":"reality","encryption":"none"}]"""

        val profile = json.decodeFromString(ListSerializer(VlessProfile.serializer()), legacy).single()

        assertEquals(VlessTransport.WS, profile.type)
        assertEquals(VlessSecurity.REALITY, profile.security)
        assertEquals(VlessEncryption.NONE, profile.encryption)
    }

    @Test
    fun enums_areWrittenAsSameStrings() {
        val encoded = json.encodeToString(
            VlessProfile.serializer(),
            VlessProfile(id = "a", name = "n", uuid = "", host = "h", port = 443, type = VlessTransport.GRPC),
        )

        assertEquals(true, encoded.contains("\"type\":\"grpc\""))
        assertEquals(true, encoded.contains("\"security\":\"tls\""))
        assertEquals(true, encoded.contains("\"encryption\":\"none\""))
    }

    @Test
    fun unknownOrOddValues_fallBackInsteadOfFailingWholeList() {
        // Старая форма позволяла сохранить security свободным текстом; компилятор тогда
        // собирал всё, кроме none/reality, как TLS. Прочитать список целиком важнее строгости.
        val legacy = """[
            {"id":"a","name":"n","uuid":"","host":"h","port":1,"type":"xhttp","security":"xtls"},
            {"id":"b","name":"n","uuid":"","host":"h","port":1,"type":" WS ","security":"Reality "}
        ]"""

        val profiles = json.decodeFromString(ListSerializer(VlessProfile.serializer()), legacy)

        assertEquals(VlessTransport.TCP, profiles[0].type)
        assertEquals(VlessSecurity.TLS, profiles[0].security)
        assertEquals(VlessTransport.WS, profiles[1].type)
        assertEquals(VlessSecurity.REALITY, profiles[1].security)
    }

    @Test
    fun legacyPolicyJson_keepsFailClosedAndEnums() {
        val legacy = """{"failClosed":false,"singBoxLogLevel":"WARN","tunStack":"gvisor","unknownField":1}"""

        val policy = json.decodeFromString(TunnelPolicy.serializer(), legacy)

        assertFalse(policy.singleConnectAttempt)
        assertEquals(SingBoxLogLevel.WARN, policy.singBoxLogLevel)
        assertEquals(TunStack.GVISOR, policy.tunStack)
    }

    @Test
    fun policy_isWrittenWithLegacyKeys() {
        val encoded = json.encodeToString(TunnelPolicy.serializer(), TunnelPolicy(singleConnectAttempt = false))

        assertEquals(true, encoded.contains("\"failClosed\":false"))
        assertEquals(true, encoded.contains("\"singBoxLogLevel\":\"info\""))
        assertEquals(true, encoded.contains("\"tunStack\":\"mixed\""))
    }
}
