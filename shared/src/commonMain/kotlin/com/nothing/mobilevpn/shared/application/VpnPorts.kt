package com.nothing.mobilevpn.shared.application

interface RuntimePort {
    suspend fun start(configPath: String)
    suspend fun stop()
    suspend fun version(): String
}

interface SecureStorePort {
    suspend fun save(key: String, value: String)
    suspend fun read(key: String): String?
}
