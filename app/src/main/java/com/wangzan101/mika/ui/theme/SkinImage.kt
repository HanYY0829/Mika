package com.wangzan101.mika.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.SkinBlur
import com.wangzan101.mika.settings.SkinSpec
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 背景图片:用户从相册选的一张图,存在 App 私有目录 `files/skin/background.webp`。
 * 导入时按屏幕缩小、重新编码,原图的 EXIF(拍摄地点、时间等)不带进来;顺带取出图上的主色(见 [SkinAccent])。
 * 怎么显示(遮罩、模糊、位置、强调色跟不跟图片)在 [SkinSpec]。
 */
object SkinImage {
    private const val DIR = "skin"
    private const val FILE = "background.webp"

    /** 模糊靠先缩到很小再放大:长边缩到这么多像素。安卓 11 也能用,显示时不另算。 */
    private const val LIGHT_BLUR_SIDE = 120
    private const val STRONG_BLUR_SIDE = 48

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    /** 读图、缩到不大于屏幕长边、存下,成功后换新版本让界面当场换上。读不出返回 false,原来的不动。 */
    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics
        val maxSide = max(metrics.widthPixels, metrics.heightPixels)
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / max(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            return@withContext false
        } catch (e: RuntimeException) {
            return@withContext false
        }
        val hue = SkinAccent.hue(pixels(shrink(bitmap, 48)))
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        val written = try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        } catch (e: IOException) {
            false
        } finally {
            bitmap.recycle()
        }
        if (!written || !tmp.renameTo(target)) {
            tmp.delete()
            return@withContext false
        }
        val flags = FeatureFlags.get(context)
        // 新图从居中开始;遮罩、模糊、跟不跟图片取色沿用上次的选择
        flags.setSkin(flags.skin().copy(version = System.currentTimeMillis(), focus = 0f, hue = hue))
        true
    }

    /** 换回纯色底,删掉存下的图。 */
    fun clear(context: Context) {
        val flags = FeatureFlags.get(context)
        flags.setSkin(flags.skin().copy(version = 0L, hue = null))
        file(context).delete()
        cached = null
    }

    /** 进程里只留一份解码好的图(按版本和模糊程度),各页面共用。 */
    @Volatile private var cached: Pair<Pair<Long, SkinBlur>, ImageBitmap>? = null

    fun cachedFor(version: Long, blur: SkinBlur): ImageBitmap? = cached?.takeIf { it.first == version to blur }?.second

    fun load(context: Context, version: Long, blur: SkinBlur): ImageBitmap? {
        if (version == 0L) return null
        cachedFor(version, blur)?.let { return it }
        val full = BitmapFactory.decodeFile(file(context).path) ?: return null
        val shown = when (blur) {
            SkinBlur.Off -> full
            SkinBlur.Light -> shrink(full, LIGHT_BLUR_SIDE).also { full.recycle() }
            SkinBlur.Strong -> shrink(full, STRONG_BLUR_SIDE).also { full.recycle() }
        }
        return shown.asImageBitmap().also { cached = (version to blur) to it }
    }

    /** 一半一半地缩到长边不超过 [side],比一步缩到位平滑,放大回去就是柔和的模糊。 */
    private fun shrink(src: Bitmap, side: Int): Bitmap {
        var b = src
        while (max(b.width, b.height) > side) {
            val f = max(0.5f, side.toFloat() / max(b.width, b.height))
            val next = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
            if (b !== src) b.recycle()
            b = next
        }
        return if (b === src) src.copy(Bitmap.Config.ARGB_8888, false) else b
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
        b.recycle()
    }
}

/**
 * 页面背景(图片 + 遮罩)。页面底([com.wangzan101.mika.ui.components.PageBackground])画它;
 * 顶栏、输入栏底下的渐隐这些要挡住滚动内容的地方,照着同一位置再画一遍,看起来和页面底连成一片。
 */
@Stable
class SkinBackdrop(image: ImageBitmap) {
    // 拖滑块时只换这几个值,不重建对象:量好的页面位置留着,画面不闪
    var image by mutableStateOf(image)
    var focus by mutableStateOf(0f)
    var scrim by mutableStateOf(Color.Transparent)

    /** 页面底在窗口里的位置和大小,由页面底量好。 */
    var pageOrigin by mutableStateOf(Offset.Zero)
    var pageSize by mutableStateOf(IntSize.Zero)

    /** 在 [scope] 里画出页面背景对应的那一块;[origin] 是这块在窗口里的位置。 */
    fun draw(scope: DrawScope, origin: Offset) = with(scope) {
        val pw = pageSize.width.toFloat()
        val ph = pageSize.height.toFloat()
        clipRect {
            if (pw > 0f && ph > 0f) {
                // 和图片铺满页面(裁掉多出来的部分)一样的位置,左右居中,上下按 focus
                val scale = max(pw / image.width, ph / image.height)
                val dw = image.width * scale
                val dh = image.height * scale
                val left = (pw - dw) / 2f - (origin.x - pageOrigin.x)
                val top = (ph - dh) * (focus + 1f) / 2f - (origin.y - pageOrigin.y)
                drawImage(
                    image, IntOffset.Zero, IntSize(image.width, image.height),
                    IntOffset(left.roundToInt(), top.roundToInt()), IntSize(dw.roundToInt(), dh.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
            drawRect(scrim)
        }
    }
}

/** 页面底下有背景图片时为它;顶栏、渐隐据此决定画纯色还是画背景。 */
val LocalSkinBackdrop = staticCompositionLocalOf<SkinBackdrop?> { null }

/** 设置里的背景图片怎么显示。改了当场换,不重建界面。 */
@Composable
fun appearanceSkin(): SkinSpec {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val spec by remember(flags) { flags.skinChanges() }.collectAsState(flags.skin())
    return spec
}

/** 此刻的页面背景;没设背景图片(或图读不出)时为 null。 */
@Composable
fun rememberSkinBackdrop(): SkinBackdrop? {
    val context = LocalContext.current
    val spec = appearanceSkin()
    val image by produceState(SkinImage.cachedFor(spec.version, spec.blur), spec.version, spec.blur) {
        value = withContext(Dispatchers.IO) { SkinImage.load(context, spec.version, spec.blur) }
    }
    val bg = MikaTheme.colors.bg
    val img = image ?: return null
    val backdrop = remember { SkinBackdrop(img) }
    // 这几个值只在画的时候读,组合时直接换上
    backdrop.image = img
    backdrop.focus = spec.focus
    backdrop.scrim = bg.copy(alpha = spec.scrim / 100f)
    return backdrop
}
