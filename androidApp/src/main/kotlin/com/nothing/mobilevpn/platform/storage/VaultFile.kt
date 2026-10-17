package com.nothing.mobilecore.platform.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Зашифрованный файл хранилища с атомарной записью: пишем во временный файл рядом, делаем
 * fsync и переименовываем поверх основного. При обрыве на любом шаге на диске остаётся либо
 * старая версия целиком, либо новая целиком.
 */
internal class VaultFile(
    private val file: File,
    private val cipher: VaultCipher,
) {
    fun exists(): Boolean = file.exists()

    /** null — файла нет. Ошибка расшифровки пробрасывается: решение принимает вызывающий. */
    fun read(): ByteArray? {
        if (!file.exists()) return null
        return cipher.decrypt(file.readBytes())
    }

    fun write(plain: ByteArray) {
        val dir = file.parentFile ?: error("vault file has no parent directory")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val sealed = cipher.encrypt(plain)
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(sealed)
            out.flush()
            out.fd.sync()
        }
        try {
            // Явная атомарная замена; File.renameTo поверх существующего файла поведением платформы
            // не гарантирован (на Windows, например, не заменяет).
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    /** Нерасшифровываемый файл не удаляем молча: откладываем в сторону для разбора. */
    fun quarantine(nowMillis: Long): File? {
        if (!file.exists()) return null
        val target = File(file.parentFile, "${file.name}.corrupt-$nowMillis")
        return runCatching { Files.move(file.toPath(), target.toPath()); target }.getOrNull()
    }
}
