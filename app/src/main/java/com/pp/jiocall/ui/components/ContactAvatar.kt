package com.pp.jiocall.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.R
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.GooglePhoneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Thread-safe memory cache storing ready-to-draw [ImageBitmap] avatars and known missing URIs
 * so scrolling 60/120fps LazyColumns never allocates wrapper objects or repeats ContentResolver queries.
 */
private object AvatarCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 10).coerceIn(4 * 1024, 32 * 1024)
    val cache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, image: ImageBitmap): Int =
            (image.width * image.height * 2) / 1024
    }
    val failedUris: MutableSet<String> = Collections.synchronizedSet(HashSet())
}

/**
 * Efficiently downsamples contact photos to thumbnail size (e.g. 128px) in RGB_565
 * off the main thread.
 */
private fun decodeThumbnail(context: Context, uriString: String, targetSizePx: Int = 128): ImageBitmap? {
    return runCatching {
        val uri = Uri.parse(uriString)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        var sampleSize = 1
        val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        if (maxDim > targetSizePx) {
            while (maxDim / (sampleSize * 2) >= targetSizePx) {
                sampleSize *= 2
            }
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)?.asImageBitmap()
        }
    }.getOrNull()
}

/**
 * Fast, memory-cached Contact Avatar for smooth 60/120fps scrolling.
 */
@Composable
fun ContactAvatar(
    name: String,
    photoUri: String? = null,
    size: Dp = 44.dp,
    fontSize: TextUnit = (size.value * 0.42).sp,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriKey = photoUri?.takeIf { it.isNotBlank() }
    val cachedBitmap = if (uriKey != null) AvatarCache.cache.get(uriKey) else null
    val alreadyFailed = uriKey != null && uriKey in AvatarCache.failedUris

    val bitmapState = if (uriKey != null && cachedBitmap == null && !alreadyFailed) {
        produceState<ImageBitmap?>(initialValue = null, uriKey) {
            val decoded = withContext(Dispatchers.IO) {
                decodeThumbnail(context, uriKey)
            }
            if (decoded != null) {
                AvatarCache.cache.put(uriKey, decoded)
            } else {
                if (AvatarCache.failedUris.size < 512) AvatarCache.failedUris.add(uriKey)
            }
            value = decoded
        }
    } else {
        null
    }

    val image = cachedBitmap ?: bitmapState?.value
    val avatarColor = remember(name) { GooglePhoneColors.getAvatarColor(name) }
    val initial = remember(name) {
        name.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
            ?: name.firstOrNull { it.isDigit() }?.toString()
            ?: ""
    }

    val borderModifier = if (borderColor != null) {
        Modifier.border(borderWidth, borderColor, CircleShape)
    } else {
        Modifier
    }

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .then(borderModifier),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarColor)
                .then(borderModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (initial.isNotEmpty()) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_person),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size((size.value * 0.55).dp),
                )
            }
        }
    }
}

/**
 * Convenient overload taking a [ContactItem].
 */
@Composable
fun ContactAvatar(
    contact: ContactItem,
    size: Dp = 44.dp,
    fontSize: TextUnit = (size.value * 0.42).sp,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    modifier: Modifier = Modifier,
) {
    ContactAvatar(
        name = contact.name,
        photoUri = contact.photoUri,
        size = size,
        fontSize = fontSize,
        borderColor = borderColor,
        borderWidth = borderWidth,
        modifier = modifier,
    )
}
