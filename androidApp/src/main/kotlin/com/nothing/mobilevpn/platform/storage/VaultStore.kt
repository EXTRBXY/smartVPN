package com.nothing.mobilecore.platform.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Состояние хранилища в памяти + запись на диск. Чтения берут [state] и ничего не пишут;
 * каждое изменение сначала атомарно записывается в [VaultFile], и только после успешной
 * записи становится видимым. Ошибка записи пробрасывается вызывающему (раньше `apply()`
 * мог молча потерять изменение при убийстве процесса).
 */
internal class VaultStore(
    private val file: VaultFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(StoredVault())

    @Volatile
    private var writable = true

    val state: StateFlow<StoredVault> = mutableState.asStateFlow()

    suspend fun awaitReady() = ready.await()

    /** Вызывается один раз из [VaultBootstrap] с загруженным или перенесённым содержимым. */
    fun initialize(loaded: StoredVault) {
        mutableState.value = loaded
        ready.complete(Unit)
    }

    /**
     * Загрузка упала неожиданно: что лежит на диске, неизвестно. Стартуем пустыми и только
     * для чтения — иначе первая же запись затёрла бы настоящие данные.
     */
    fun initializeReadOnly() {
        writable = false
        initialize(StoredVault())
    }

    suspend fun update(transform: (StoredVault) -> StoredVault): StoredVault {
        awaitReady()
        check(writable) { "storage_read_only" }
        return mutex.withLock {
            val current = mutableState.value
            val next = transform(current)
            if (next != current) {
                withContext(io) { file.write(StoredVault.encode(next)) }
                mutableState.value = next
            }
            next
        }
    }
}
