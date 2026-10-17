package com.nothing.mobilecore.ui.apps

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val appIconCacheMaxBytes =
    (Runtime.getRuntime().maxMemory() / 24).toInt().coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)

private val appIconBitmapCache: LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(appIconCacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

private fun appIconCacheKey(packageName: String, sizePx: Int): String = "$packageName#$sizePx"

internal data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

@Composable
internal fun AppPickerRow(
    pm: PackageManager,
    packageName: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        AppPackageIcon(pm = pm, packageName = packageName, label = label)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppPackageIcon(
    pm: PackageManager,
    packageName: String,
    label: String,
) {
    val density = LocalDensity.current
    val sizePx = remember(density) {
        with(density) { 40.dp.roundToPx().coerceAtLeast(1) }
    }
    val cacheKey = remember(packageName, sizePx) { appIconCacheKey(packageName, sizePx) }

    var bitmap by remember(packageName, sizePx) {
        mutableStateOf(
            synchronized(appIconBitmapCache) { appIconBitmapCache.get(cacheKey) },
        )
    }

    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            synchronized(appIconBitmapCache) {
                appIconBitmapCache.get(cacheKey)
            } ?: runCatching {
                pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
            }.getOrNull()?.also { decoded ->
                synchronized(appIconBitmapCache) {
                    appIconBitmapCache.put(cacheKey, decoded)
                }
            }
        }
        if (decoded != null) bitmap = decoded
    }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = label,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = label.take(1).uppercase(Locale.getDefault()).ifEmpty { "·" },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
