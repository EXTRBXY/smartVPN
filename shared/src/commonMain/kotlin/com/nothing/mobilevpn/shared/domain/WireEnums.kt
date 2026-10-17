package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Значение, у которого есть строковое представление в ссылке vless://, конфиге sing-box и хранилище. */
interface WireValue {
    val wire: String
}

/**
 * Сериализует enum строкой [WireValue.wire] — JSON совпадает с тем, что хранилось, пока поля
 * были `String`. Чтение мягкое (trim + без учёта регистра): неизвестное значение превращается в
 * [fallback] вместо исключения, иначе одно испорченное поле делало бы нечитаемым весь список
 * профилей. [fallback] выбран так, чтобы повторять прежнее поведение компилятора конфига для
 * нераспознанных строк.
 */
abstract class LenientWireSerializer<T>(
    serialName: String,
    private val values: List<T>,
    private val fallback: T,
) : KSerializer<T> where T : Enum<T>, T : WireValue {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): T = fromWire(values, decoder.decodeString()) ?: fallback
}

/** Строгий разбор: null для неизвестного значения (для ссылок и пользовательского ввода). */
fun <T : WireValue> fromWire(values: List<T>, raw: String?): T? {
    val normalized = raw?.trim()?.lowercase() ?: return null
    return values.firstOrNull { it.wire == normalized }
}

/** Транспорт VLESS. Раньше неизвестный тип компилировался как TCP — это и есть fallback. */
@Serializable(with = VlessTransport.Serializer::class)
enum class VlessTransport(override val wire: String) : WireValue {
    TCP("tcp"),
    WS("ws"),
    GRPC("grpc"),
    ;

    object Serializer : LenientWireSerializer<VlessTransport>("VlessTransport", VlessTransport.entries, TCP)

    companion object {
        fun parse(raw: String?): VlessTransport? = fromWire(VlessTransport.entries, raw)
    }
}

/** Безопасность VLESS. Раньше всё, кроме «none»/«reality», компилировалось как TLS — fallback. */
@Serializable(with = VlessSecurity.Serializer::class)
enum class VlessSecurity(override val wire: String) : WireValue {
    TLS("tls"),
    REALITY("reality"),
    NONE("none"),
    ;

    object Serializer : LenientWireSerializer<VlessSecurity>("VlessSecurity", VlessSecurity.entries, TLS)

    companion object {
        fun parse(raw: String?): VlessSecurity? = fromWire(VlessSecurity.entries, raw)
    }
}

/** Шифрование VLESS: поддерживается только «none». */
@Serializable(with = VlessEncryption.Serializer::class)
enum class VlessEncryption(override val wire: String) : WireValue {
    NONE("none"),
    ;

    object Serializer : LenientWireSerializer<VlessEncryption>("VlessEncryption", VlessEncryption.entries, NONE)

    companion object {
        fun parse(raw: String?): VlessEncryption? = fromWire(VlessEncryption.entries, raw)
    }
}

/** Стек TUN sing-box. */
@Serializable(with = TunStack.Serializer::class)
enum class TunStack(override val wire: String) : WireValue {
    MIXED("mixed"),
    SYSTEM("system"),
    GVISOR("gvisor"),
    ;

    object Serializer : LenientWireSerializer<TunStack>("TunStack", TunStack.entries, MIXED)
}

/** Уровень журнала ядра sing-box. */
@Serializable(with = SingBoxLogLevel.Serializer::class)
enum class SingBoxLogLevel(override val wire: String) : WireValue {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    ;

    object Serializer : LenientWireSerializer<SingBoxLogLevel>("SingBoxLogLevel", SingBoxLogLevel.entries, INFO)
}
