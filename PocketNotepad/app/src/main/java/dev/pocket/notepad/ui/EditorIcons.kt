package dev.pocket.notepad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class EditorSymbol { NEW, OPEN, IMPORT, ADD, PASTE, SHARE, UNDO, REDO, CLEAR, EXPORT, MORE, BACK, NEXT, DELETE, TRASH, ERASE }

/**
 * Icons from the user's Noteing Pad icon set (1024x1024 SVGs, geometry copied
 * verbatim). Drawn in Canvas rather than loaded as tinted vector drawables
 * because each icon carries a #d92c35 accent that a tint would flatten;
 * strokes take LocalContentColor, so light/dark still work.
 */
private val AccentRed = Color(0xFFD92C35)

/**
 * Uniform design space:
 * All icons in the new 44px-stroke set share the 1024x1024 viewBox with
 * uniform center (512, 512) and uniform bounding span (~844px).
 * A single transform centers and scales all marks identically with no distortion.
 */
private const val MARK_CX = 512f
private const val MARK_CY = 512f
private const val MARK_SPAN = 844f
private const val MARK_FILL = 0.88f

// --- source geometry -------------------------------------------------------
private val DOC_SHEET =
    "M162,212.06v599.87c0,55.26,44.77,100.06,100,100.06h500c55.23,0,100-44.8,100-100.06v-449.78c-97.63-97.69-152.37-152.46-250-250.16h-350c-55.23,0-100,44.8-100,100.06Z"
private val DOC_FOLD =
    "M612,112v150.09c0,55.26,44.77,100.06,100,100.06h150"
private val DOC_SHEET_NO_FOLD =
    "M162,212.06v599.87c0,55.26,44.77,100.06,100,100.06h500c55.23,0,100-44.8,100-100.06V112H262c-55.23,0-100,44.8-100,100.06Z"
private val CLIPBOARD =
    "M686.99,161.53h75.01c55.23,0,100,44.8,100,100.06v550.34c0,55.26-44.77,100.06-100,100.06h-500c-55.23,0-100-44.8-100-100.06V261.59c0-55.26,44.77-100.06,100-100.06h75.01"
private val TRAY_LID =
    "M250,200.08l36.19-72.48c8.47-16.95,25.78-27.67,44.73-27.67h362.31c18.87,0,36.14,10.63,44.64,27.48l36.14,71.61"
private val TRAY_BODY =
    "M212.63,358.11l79.45,499.58c3.87,24.33,24.93,42.24,49.67,42.24h340.51c24.74,0,45.8-17.91,49.67-42.24l79.45-499.58c4.83-30.4-18.76-57.9-49.67-57.9h-499.41c-30.91,0-54.5,27.5-49.67,57.9Z"
private val FOLDER_TAB =
    "M274.7,300.61v-49.8c0-27.61,22.39-50,50-50h151.69c27.61,0,50,22.39,50,50v49.8"
private val FOLDER_BODY =
    "M174.5,199.81v599.87c0,55.26,44.77,100.06,100,100.06h500c55.23,0,100-44.8,100-100.06v-499.2c0-55.26-44.77-100.06-100-100.06h-150c-.34,0-.61-.27-.61-.61h0c0-55.26-44.77-100.06-100-100.06h-249.39c-55.23,0-100,44.8-100,100.06Z"
private val UNDO_ARC =
    "M455.54,421.53h123.01c64.18,0,116.2,52.02,116.2,116.2,0,32.09-13.01,61.14-34.03,82.17-21.03,21.03-50.08,34.03-82.17,34.03h-18.27"
private val REDO_ARC =
    "M463.72,653.93h-18.27c-32.09,0-61.14-13-82.17-34.03-21.02-21.03-34.03-50.08-34.03-82.17,0-64.18,52.02-116.2,116.2-116.2h123.01"
private val SAVE_LID =
    "M762,212.25v155.6c0,27.61-22.39,50-50,50h-374.1c-27.61,0-50-22.39-50-50V112.25"
private val SAVE_BODY =
    "M162,212.06v599.87c0,55.26,44.77,100.06,100,100.06h500c55.23,0,100-44.8,100-100.06v-499.81c-78.1-78.15-121.9-121.97-200-200.13h-400c-55.23,0-100,44.8-100,100.06Z"
private val SAVE_SLOT =
    "M312.1,509.39h399.9c27.6,0,50,22.4,50,50v352.36h-499.9v-352.36c0-27.6,22.4-50,50-50Z"

/** Per-symbol slots: stroked source paths in SVG document order. */
internal fun sourcesFor(symbol: EditorSymbol): List<String> = when (symbol) {
    EditorSymbol.NEW, EditorSymbol.ADD -> listOf(DOC_SHEET, DOC_FOLD)
    EditorSymbol.BACK, EditorSymbol.NEXT, EditorSymbol.MORE -> listOf(DOC_SHEET_NO_FOLD)
    EditorSymbol.UNDO -> listOf(DOC_SHEET_NO_FOLD, UNDO_ARC)
    EditorSymbol.REDO -> listOf(DOC_SHEET_NO_FOLD, REDO_ARC)
    EditorSymbol.IMPORT, EditorSymbol.OPEN, EditorSymbol.PASTE, EditorSymbol.SHARE -> listOf(CLIPBOARD)
    EditorSymbol.CLEAR, EditorSymbol.ERASE, EditorSymbol.DELETE -> listOf(TRAY_LID, TRAY_BODY)
    EditorSymbol.EXPORT -> listOf(SAVE_LID, SAVE_BODY, SAVE_SLOT)
    EditorSymbol.TRASH -> listOf(FOLDER_TAB, FOLDER_BODY)
}

// --- parsing ---------------------------------------------------------------

/**
 * A parsed, Android-free segment stream. Kept separate from Path so the
 * geometry can be unit-tested on the JVM (android.graphics.Path is a stub
 * there and throws on any call).
 */
internal data class Seg(
    val command: Char,            // 'M' 'L' 'H' 'V' 'C' 'Z'
    val args: FloatArray
) {
    override fun equals(other: Any?) = other is Seg && command == other.command &&
        args.contentEquals(other.args)
    override fun hashCode() = 31 * command.hashCode() + args.contentHashCode()
}

/**
 * SVG path parser covering the command set these icons use, absolute and
 * relative (M L H V C Z), resolving relatives against a tracked cursor.
 */
internal fun parseSegments(d: String, strict: Boolean = false): List<Seg> {
    val tokens = Regex("[MmLlHhVvCcZz]|-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
        .findAll(d.trim()).map { it.value }.toList()
    val out = ArrayList<Seg>()
    var i = 0
    var cx = 0f
    var cy = 0f
    var subX = 0f
    var subY = 0f

    fun num(): Float = tokens[i++].toFloat()
    fun room(n: Int): Boolean = i + n <= tokens.size
    fun numsAhead(n: Int): Boolean {
        if (!room(n)) return false
        for (k in 0 until n) if (tokens[i + k].first().isLetter()) return false
        return true
    }

    while (i < tokens.size) {
        val head = tokens[i]
        val isCommand = head.length == 1 && head[0].isLetter()
        val original = if (isCommand) head[0] else ' '
        val command = original.uppercaseChar()
        if (isCommand) i++
        val relative = original.isLowerCase()
        when (command) {
            'M' -> {
                if (!room(2)) { if (strict) error("truncated moveto") else return out }
                val x = num(); val y = num()
                cx = if (relative) cx + x else x
                cy = if (relative) cy + y else y
                subX = cx; subY = cy
                out += Seg('M', floatArrayOf(cx, cy))
                while (numsAhead(2)) {
                    val lx = num(); val ly = num()
                    cx += lx; cy += ly
                    out += Seg('L', floatArrayOf(cx, cy))
                }
            }
            'L' -> {
                if (!room(2)) { if (strict) error("truncated lineto") else return out }
                val x = num(); val y = num()
                cx = if (relative) cx + x else x
                cy = if (relative) cy + y else y
                out += Seg('L', floatArrayOf(cx, cy))
            }
            'H' -> {
                if (!room(1)) { if (strict) error("truncated h") else return out }
                val x = num()
                cx = if (relative) cx + x else x
                out += Seg('H', floatArrayOf(cx))
            }
            'V' -> {
                if (!room(1)) { if (strict) error("truncated v") else return out }
                val y = num()
                cy = if (relative) cy + y else y
                out += Seg('V', floatArrayOf(cy))
            }
            'C' -> {
                if (!room(6)) { if (strict) error("truncated curveto") else return out }
                val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                val x3 = num(); val y3 = num()
                val ax = if (relative) cx + x1 else x1
                val ay = if (relative) cy + y1 else y1
                val bx = if (relative) cx + x2 else x2
                val by = if (relative) cy + y2 else y2
                val ex = if (relative) cx + x3 else x3
                val ey = if (relative) cy + y3 else y3
                out += Seg('C', floatArrayOf(ax, ay, bx, by, ex, ey))
                cx = ex; cy = ey
            }
            'Z' -> { out += Seg('Z', FloatArray(0)); cx = subX; cy = subY }
            'S', 'Q' -> { if (!room(4)) return out; num(); num(); num(); num() }
            'T' -> { if (!room(2)) return out; num(); num() }
            'A' -> { if (!room(7)) return out; repeat(7) { num() } }
            else -> if (!isCommand) i++
        }
    }
    return out
}

// --- caching + rendering ---------------------------------------------------

private val segCache = ConcurrentHashMap<EditorSymbol, List<List<Seg>>>()

private fun segmentsFor(symbol: EditorSymbol): List<List<Seg>> =
    segCache.getOrPut(symbol) { sourcesFor(symbol).map { parseSegments(it) } }

private val pathCache = ConcurrentHashMap<Pair<EditorSymbol, Float>, List<Path>>()

private fun buildPath(segs: List<Seg>, s: Float, ox: Float, oy: Float): Path {
    val p = Path()
    var cx = 0f
    var cy = 0f
    fun fx(x: Float) = (x - MARK_CX) * s + ox
    fun fy(y: Float) = (y - MARK_CY) * s + oy
    for (seg in segs) {
        when (seg.command) {
            'M' -> { cx = seg.args[0]; cy = seg.args[1]; p.moveTo(fx(cx), fy(cy)) }
            'L' -> { cx = seg.args[0]; cy = seg.args[1]; p.lineTo(fx(cx), fy(cy)) }
            'H' -> { cx = seg.args[0]; p.lineTo(fx(cx), fy(cy)) }
            'V' -> { cy = seg.args[0]; p.lineTo(fx(cx), fy(cy)) }
            'C' -> p.cubicTo(fx(seg.args[0]), fy(seg.args[1]), fx(seg.args[2]), fy(seg.args[3]),
                fx(seg.args[4]), fy(seg.args[5]))
            'Z' -> p.close()
        }
    }
    return p
}

@Composable
fun EditorIcon(symbol: EditorSymbol, description: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { contentDescription = description }) {
        val s = MARK_FILL * min(size.width, size.height) / MARK_SPAN
        val ox = size.width / 2f
        val oy = size.height / 2f
        fun fx(x: Number): Float = (x.toFloat() - MARK_CX) * s + ox
        fun fy(y: Number): Float = (y.toFloat() - MARK_CY) * s + oy
        val stroke = Stroke(width = 44f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val paths = pathCache.getOrPut(symbol to s) {
            segmentsFor(symbol).map { buildPath(it, s, ox, oy) }
        }
        fun strokeSlot(slot: Int, c: Color = color) =
            paths.getOrNull(slot)?.let { drawPath(it, c, style = stroke) }
        fun line(x1: Number, y1: Number, x2: Number, y2: Number, c: Color = color) =
            drawLine(c, Offset(fx(x1), fy(y1)), Offset(fx(x2), fy(y2)), stroke.width, StrokeCap.Round)
        fun dot(cx: Number, cy: Number, r: Number, c: Color) =
            drawCircle(c, radius = r.toFloat() * s, center = Offset(fx(cx), fy(cy)))
        fun outlineBox(x: Number, y: Number, w: Number, h: Number, r: Number, c: Color = color) =
            drawRoundRect(c, topLeft = Offset(fx(x), fy(y)),
                size = Size(w.toFloat() * s, h.toFloat() * s),
                cornerRadius = CornerRadius(r.toFloat() * s, r.toFloat() * s),
                style = stroke)
        fun polyPath(points: String, close: Boolean): Path {
            val p = Path()
            val nums = points.trim().split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
            if (nums.size < 4) return p
            p.moveTo(fx(nums[0]), fy(nums[1]))
            var i = 2
            while (i + 1 < nums.size) {
                p.lineTo(fx(nums[i]), fy(nums[i + 1]))
                i += 2
            }
            if (close) p.close()
            return p
        }
        fun fillPoly(points: String, c: Color) = drawPath(polyPath(points, close = true), c)
        fun strokePoly(points: String, c: Color = color) =
            drawPath(polyPath(points, close = false), c, style = stroke)

        when (symbol) {
            EditorSymbol.NEW -> {
                strokeSlot(0); strokeSlot(1)
                line(512, 588.8, 512, 504.58)
                line(512, 787.42, 512, 702.78)
                line(370.58, 646, 653.42, 646, AccentRed)
            }
            EditorSymbol.ADD -> {
                strokeSlot(0); strokeSlot(1)
                dot(399.5, 628.97, 35.78, color)
                dot(624.5, 628.97, 35.78, AccentRed)
                dot(512, 628.97, 35.78, color)
                dot(512, 741.47, 35.78, color)
                dot(624.5, 741.47, 35.78, color)
                dot(399.5, 741.47, 35.78, color)
            }
            EditorSymbol.BACK -> {
                strokeSlot(0)
                line(445.39, 511.36, 568.39, 511.36)
                fillPoly("471.06 599.12 319.09 511.37 471.06 423.61 471.06 599.12", color)
                dot(669.13, 512, 35.78, AccentRed)
            }
            EditorSymbol.NEXT -> {
                strokeSlot(0)
                line(326.57, 512, 449.58, 512)
                fillPoly("423.91 599.76 575.88 512.01 423.91 424.25 423.91 599.76", color)
                dot(661.65, 512, 35.78, AccentRed)
            }
            EditorSymbol.MORE -> {
                strokeSlot(0)
                dot(399.5, 512, 35.78, color)
                dot(624.5, 512, 35.78, AccentRed)
                dot(512, 512, 35.78, color)
            }
            EditorSymbol.UNDO -> {
                strokeSlot(0); strokeSlot(1)
                fillPoly("481.22 509.28 329.25 421.53 481.22 333.77 481.22 509.28", color)
                dot(459.52, 654.45, 35.78, AccentRed)
            }
            EditorSymbol.REDO -> {
                strokeSlot(0); strokeSlot(1)
                fillPoly("542.78 509.28 694.75 421.53 542.78 333.77 542.78 509.28", color)
                dot(564.48, 654.45, 35.78, AccentRed)
            }
            EditorSymbol.IMPORT, EditorSymbol.OPEN -> {
                strokeSlot(0)
                outlineBox(337, 112, 350, 100.06, 50)
                strokePoly("412 661.84 412 762.41 612 762.41 612 661.84")
                line(512, 623.82, 512, 514.75, AccentRed)
                fillPoly("431.81 613.07 592.18 613.07 512.01 693.22 431.81 613.07", AccentRed)
            }
            EditorSymbol.PASTE -> {
                strokeSlot(0)
                outlineBox(337, 112, 350, 100.06, 50)
                line(412, 661.84, 612, 661.84)
                line(412, 562.28, 612, 562.28, AccentRed)
                line(412, 762.41, 612, 762.41)
            }
            EditorSymbol.SHARE -> {
                strokeSlot(0)
                outlineBox(337, 112, 350, 100.06, 50)
                strokePoly("412.06 661.84 412.06 762.34 611.94 762.34 611.94 661.84")
                line(512, 661.84, 512, 562.34, AccentRed)
                fillPoly("431.81 573.09 592.18 573.09 512.01 492.94 431.81 573.09", AccentRed)
            }
            EditorSymbol.CLEAR, EditorSymbol.ERASE, EditorSymbol.DELETE -> {
                strokeSlot(0); strokeSlot(1)
                line(471.55, 609.05, 412, 549.5)
                line(612, 749.5, 552.15, 689.65)
                line(412, 749.5, 612, 549.5, AccentRed)
                line(162, 200.08, 862, 200.08)
            }
            EditorSymbol.EXPORT -> {
                strokeSlot(0); strokeSlot(1); strokeSlot(2)
                outlineBox(371.92, 195.86, 100, 138.44, 0, AccentRed)
            }
            EditorSymbol.TRASH -> {
                strokeSlot(0, AccentRed)
                strokeSlot(1)
                outlineBox(174.5, 300.61, 700, 598.89, 100)
                line(471.55, 605.55, 412, 546)
                line(612, 746, 552.15, 686.15)
                line(412, 746, 612, 546, AccentRed)
            }
        }
    }
}
