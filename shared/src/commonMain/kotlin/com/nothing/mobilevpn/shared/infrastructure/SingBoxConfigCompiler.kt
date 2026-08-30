package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.application.SingBoxConfigCompilerPort
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.isIpHost
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class SingBoxConfigCompiler : SingBoxConfigCompilerPort {

    /** Без prettyPrint — меньше утечки структуры при дампе файла. */
    private val format = Json { prettyPrint = false }

    override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = runCatching {
        val json = buildJsonObject {
            put("log", buildJsonObject { put("level", JsonPrimitive(policy.singBoxLogLevel)) })
            buildDns(policy)?.let { put("dns", it) }
            put("inbounds", buildJsonArray { add(buildTunInbound(profile, policy)) })
            put("outbounds", buildOutbounds(profile))
            put("route", buildRoute())
        }
        format.encodeToString(JsonElement.serializer(), json)
    }

    private fun buildTunInbound(profile: VlessProfile, policy: TunnelPolicy): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("tun"))
            put("tag", JsonPrimitive("tun-in"))
            put("address", buildJsonArray { add(JsonPrimitive(policy.tunIpv4Cidr)) })
            put("mtu", JsonPrimitive(policy.mtu))
            // Android + libbox: маршруты и DNS приходят в TunOptions для VpnService.Builder (как в SFA).
            put("auto_route", JsonPrimitive(true))
            put("strict_route", JsonPrimitive(true))
            val stack = policy.tunStack.trim().lowercase().ifEmpty { "mixed" }
            put("stack", JsonPrimitive(stack))
        }

    private fun buildOutbounds(profile: VlessProfile): JsonArray = buildJsonArray {
        add(buildVlessOutbound(profile))
        add(buildJsonObject {
            // Expose the proxy as a group so libbox can URL-test the real outbound dialer.
            put("type", JsonPrimitive("selector"))
            put("tag", JsonPrimitive("proxy"))
            put("outbounds", buildJsonArray { add(JsonPrimitive("proxy-node")) })
            put("default", JsonPrimitive("proxy-node"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("direct"))
            put("tag", JsonPrimitive("direct"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("block"))
            put("tag", JsonPrimitive("block"))
        })
    }

    private fun buildVlessOutbound(profile: VlessProfile): JsonObject = buildJsonObject {
        require(profile.uuid.isNotBlank()) { "profile_secret_unavailable" }
        put("type", JsonPrimitive("vless"))
        put("tag", JsonPrimitive("proxy-node"))
        put("server", JsonPrimitive(profile.host))
        put("server_port", JsonPrimitive(profile.port))
        put("uuid", JsonPrimitive(profile.uuid))
        profile.flow?.let { put("flow", JsonPrimitive(it)) }
        buildTls(profile)?.let { put("tls", it) }
        buildTransport(profile)?.let { put("transport", it) }
    }

    private fun buildTls(profile: VlessProfile): JsonObject? {
        if (profile.security == "none") return null
        val serverName = profile.sni?.ifBlank { null } ?: profile.host
        return buildJsonObject {
            put("enabled", JsonPrimitive(true))
            put("server_name", JsonPrimitive(serverName))
            if (profile.alpn.isNotEmpty()) {
                put("alpn", buildJsonArray { profile.alpn.forEach { add(JsonPrimitive(it)) } })
            }
            profile.fingerprint?.let { fp ->
                put("utls", buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put("fingerprint", JsonPrimitive(fp))
                })
            }
            if (profile.security == "reality") {
                val publicKey = profile.realityPublicKey?.takeIf { it.isNotBlank() }
                    ?: error("reality_public_key_missing")
                val shortId = profile.realityShortId?.takeIf { it.isNotBlank() }
                    ?: error("reality_short_id_missing")
                put("reality", buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put("public_key", JsonPrimitive(publicKey))
                    put("short_id", JsonPrimitive(shortId))
                })
            }
        }
    }

    private fun buildTransport(profile: VlessProfile): JsonObject? =
        when (profile.type) {
            "tcp" -> null
            "ws" -> buildJsonObject {
                put("type", JsonPrimitive("ws"))
                put("path", JsonPrimitive(profile.wsPath ?: "/"))
                profile.wsHost?.let { host ->
                    put("headers", buildJsonObject { put("Host", JsonPrimitive(host)) })
                }
            }
            "grpc" -> buildJsonObject {
                put("type", JsonPrimitive("grpc"))
                put("service_name", JsonPrimitive(profile.grpcServiceName ?: "default"))
            }
            else -> null
        }

    private fun buildDns(policy: TunnelPolicy): JsonObject? =
        when (policy.dnsMode) {
            DnsMode.DOH -> buildJsonObject {
                put("final", JsonPrimitive("doh"))
                put("reverse_mapping", JsonPrimitive(true))
                if (policy.localDnsDomains.isNotEmpty() || policy.systemDnsPackageNames.isNotEmpty()) {
                    put("rules", buildJsonArray {
                        if (policy.localDnsDomains.isNotEmpty()) {
                            add(buildJsonObject {
                                put("domain_suffix", buildJsonArray {
                                    policy.localDnsDomains.forEach { add(JsonPrimitive(it.trim().trimStart('.'))) }
                                })
                                put("action", JsonPrimitive("route"))
                                put("server", JsonPrimitive("local"))
                            })
                        }
                        if (policy.systemDnsPackageNames.isNotEmpty()) {
                            add(buildJsonObject {
                                put("package_name", buildJsonArray {
                                    policy.systemDnsPackageNames.forEach { add(JsonPrimitive(it)) }
                                })
                                put("action", JsonPrimitive("route"))
                                put("server", JsonPrimitive("local"))
                            })
                        }
                    })
                }
                put("servers", buildJsonArray {
                    add(buildLocalDnsServer())
                    add(buildJsonObject {
                        put("type", JsonPrimitive("https"))
                        put("tag", JsonPrimitive("doh"))
                        val dohHost = policy.dohServer.trim()
                        put("server", JsonPrimitive(dohHost))
                        put("server_port", JsonPrimitive(443))
                        put("path", JsonPrimitive(policy.dohPath))
                        policy.dohSni?.let { sni ->
                            put("tls", buildJsonObject {
                                put("enabled", JsonPrimitive(true))
                                put("server_name", JsonPrimitive(sni))
                            })
                        }
                        if (!isIpHost(dohHost)) {
                            put("domain_resolver", JsonPrimitive("local"))
                        }
                    })
                })
            }
            DnsMode.SYSTEM -> buildJsonObject {
                put("final", JsonPrimitive("local"))
                put("reverse_mapping", JsonPrimitive(true))
                put("servers", buildJsonArray { add(buildLocalDnsServer()) })
            }
        }

    private fun buildLocalDnsServer(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("local"))
        put("tag", JsonPrimitive("local"))
        put("prefer_go", JsonPrimitive(false))
    }

    private fun buildRoute(): JsonObject {
        val rules = buildJsonArray {
            add(
                buildJsonObject {
                    put("port", buildJsonArray { add(JsonPrimitive(53)) })
                    put("action", JsonPrimitive("hijack-dns"))
                },
            )
        }
        return buildJsonObject {
            put("final", JsonPrimitive("proxy"))
            put("auto_detect_interface", JsonPrimitive(true))
            put("rules", rules)
        }
    }
}
