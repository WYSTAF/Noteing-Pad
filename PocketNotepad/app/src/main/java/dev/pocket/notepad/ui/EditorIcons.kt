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
 * Design space. The marks were drawn around (512, 506); the union of all
 * geometry across the set spans 750 x 812 of the 1024 viewBox (measured from
 * the SVG sources). The first transform scaled the raw 1024 grid by a fixed
 * pixel constant, so a mark filled ~a third of the box with a 0.8 px stroke —
 * the "icons are bad" report. Fitting is now computed per draw from the real
 * canvas size: resolution-independent, optically centred on the union, and
 * the 30-unit design stroke lands at a visible ~4% of the mark.
 */
private const val MARK_CX = 512f
private const val MARK_CY = 506f
private const val MARK_SPAN = 812f       // the larger union dimension
private const val MARK_FILL = 0.85f      // fraction of the box the union fills

// --- source geometry -------------------------------------------------------
private val DOC_SHEET =
    "M162,212.25v599.5c0,55.23,44.77,100,100,100h500c55.23,0,100-44.77,100-100v-449.5c-97.63-97.63-152.37-152.37-250-250h-350c-55.23,0-100,44.77-100,100Z"
private val DOC_FOLD = "M612,112.25v150c0,55.23,44.77,100,100,100h150"
private val CLIPBOARD =
    "M686.99,161.75h75.01c55.23,0,100,44.77,100,100v550c0,55.23-44.77,100-100,100h-500c-55.23,0-100-44.77-100-100V261.75c0-55.23,44.77-100,100-100h75.01"
private val TRAY_LID =
    "M250,200.12l36.19-72.46c8.47-16.95,25.78-27.66,44.73-27.66h362.31c18.87,0,36.14,10.63,44.64,27.47l36.14,71.6"
private val TRAY_BODY =
    "M214.32,358.43l79,498.89c3.85,24.29,24.79,42.18,49.38,42.18h338.59c24.6,0,45.54-17.89,49.38-42.18l79-498.89c4.81-30.35-18.65-57.82-49.38-57.82h-496.58c-30.73,0-54.19,27.47-49.38,57.82Z"
private val FOLDER_TAB = "M274.7,300.61v-49.8c0-27.61,22.39-50,50-50h151.69c27.61,0,50,22.39,50,50v49.8"
private val FOLDER_BODY =
    "M174.5,200v599.5c0,55.23,44.77,100,100,100h500c55.23,0,100-44.77,100-100v-498.89c0-55.23-44.77-100-100-100h-150c-.34,0-.61-.27-.61-.61h0c0-55.23-44.77-100-100-100h-249.39c-55.23,0-100,44.77-100,100Z"
private val UNDO_ARC =
    "M415.36,377.65h163.19c64.18,0,116.2,52.02,116.2,116.2,0,32.09-13.01,61.14-34.03,82.17-21.03,21.03-50.08,34.03-82.17,34.03h-18.27"
private val REDO_ARC =
    "M463.72,610.05h-18.27c-32.09,0-61.14-13-82.17-34.03-21.02-21.03-34.03-50.08-34.03-82.17,0-64.18,52.02-116.2,116.2-116.2h163.19"
private val SAVE_LID = "M762,212.25v155.6c0,27.61-22.39,50-50,50h-374.1c-27.61,0-50-22.39-50-50V112.25"
private val SAVE_BODY =
    "M162,212.25v599.5c0,55.23,44.77,100,100,100h500c55.23,0,100-44.77,100-100v-499.5c-78.1-78.1-121.9-121.9-200-200h-400c-55.23,0-100,44.77-100,100Z"
private val SAVE_SLOT = "M312.1,509.39h399.9c27.6,0,50,22.4,50,50v352.36h-499.9v-352.36c0-27.6,22.4-50,50-50Z"

/** Per-symbol slots: stroked source paths in SVG document order. */
internal fun sourcesFor(symbol: EditorSymbol): List<String> = when (symbol) {
    EditorSymbol.NEW, EditorSymbol.ADD -> listOf(DOC_SHEET, DOC_FOLD)
    EditorSymbol.IMPORT, EditorSymbol.OPEN, EditorSymbol.PASTE, EditorSymbol.SHARE ->
        listOf(CLIPBOARD)
    EditorSymbol.UNDO -> listOf(UNDO_ARC)
    EditorSymbol.REDO -> listOf(REDO_ARC)
    EditorSymbol.CLEAR, EditorSymbol.ERASE, EditorSymbol.DELETE -> listOf(TRAY_LID, TRAY_BODY)
    EditorSymbol.EXPORT -> listOf(SAVE_LID, SAVE_BODY, SAVE_SLOT)
    EditorSymbol.TRASH -> listOf(FOLDER_TAB, FOLDER_BODY)
    // Pure geometry icons need no stroked source paths.
    EditorSymbol.MORE, EditorSymbol.BACK, EditorSymbol.NEXT -> emptyList()
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
 *
 * The very first parser matched only M/L/C/Z, so a relative 'v' or 'h' was
 * never treated as a command; the number stream desynchronised and it walked
 * off the end of the token list — IndexOutOfBoundsException on the first icon
 * drawn, which killed v1.7.0 at launch.
 *
 * Total by construction: every command bounds-checks its arguments, and an
 * incomplete command ends the parse instead of throwing. `strict` is used by
 * the tests to assert no input is silently truncated.
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
    // True when the next n tokens are numbers — a token starting with a letter
    // is a command, so the implicit-lineto loop below must stop there rather
    // than trying to parse "v" as a coordinate (which threw NumberFormatException).
    fun numsAhead(n: Int): Boolean {
        if (!room(n)) return false
        for (k in 0 until n) if (tokens[i + k].first().isLetter()) return false
        return true
    }

    while (i < tokens.size) {
        val head = tokens[i]
        val isCommand = head.length == 1 && head[0].isLetter()
        // Keep the original case: 'v' is V-relative, and the relative flag has
        // to be read before the command is folded to upper case.
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
                // Per SVG, extra pairs after a moveto are implicit linetos.
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
            // Not used by this icon set; consume arguments so the stream cannot
            // desynchronise if one ever is.
            'S', 'Q' -> { if (!room(4)) return out; num(); num(); num(); num() }
            'T' -> { if (!room(2)) return out; num(); num() }
            'A' -> { if (!room(7)) return out; repeat(7) { num() } }
            else -> if (!isCommand) i++   // stray number with no command
        }
    }
    return out
}

// --- caching + rendering ---------------------------------------------------

/** Geometry parsed once per symbol, kept in DESIGN space (untransformed):
 *  the transform depends on the canvas size, which is only known at draw. */
private val segCache = ConcurrentHashMap<EditorSymbol, List<List<Seg>>>()

private fun segmentsFor(symbol: EditorSymbol): List<List<Seg>> =
    segCache.getOrPut(symbol) { sourcesFor(symbol).map { parseSegments(it) } }

/** Transformed paths, memoised per symbol AND scale. The scale key only
 *  changes with canvas size/density, so every frame reuses one list. */
private val pathCache = ConcurrentHashMap<Pair<EditorSymbol, Float>, List<Path>>()

/** Design-space segments -> Path through the draw-time transform. */
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
        // The SVG set is stroke-based, but its 30-unit stroke (3% of the
        // viewBox) renders hairline-thin in a 22dp box. Icons in this app's
        // Material context read best at ~8% of the box; the stroke is pinned
        // to that weight so every mark stays visibly crisp across densities.
        val stroke = Stroke(width = 0.08f * min(size.width, size.height),
            cap = StrokeCap.Round, join = StrokeJoin.Round)

        val paths = pathCache.getOrPut(symbol to s) {
            segmentsFor(symbol).map { buildPath(it, s, ox, oy) }
        }
        fun strokeSlot(slot: Int, c: Color = color) =
            paths.getOrNull(slot)?.let { drawPath(it, c, style = stroke) }
        fun line(x1: Number, y1: Number, x2: Number, y2: Number, c: Color = color) =
            drawLine(c, Offset(fx(x1), fy(y1)), Offset(fx(x2), fy(y2)), stroke.width, StrokeCap.Round)
        fun dot(cx: Number, cy: Number, r: Number, c: Color) =
            drawCircle(c, radius = r.toFloat() * s, center = Offset(fx(cx), fy(cy)))
        // STROKED rounded outline (SVG rects: fill:none, stroke 30). The first
        // port filled these solid, turning clips and folder fronts into blobs.
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
        // Filled closed polygon (SVG polygon: filled arrowheads/tips).
        fun fillPoly(points: String, c: Color) = drawPath(polyPath(points, close = true), c)
        // Open STROKED polyline (SVG polyline: the import/share trays). Must
        // NOT close — a closing edge would stroke a line the design omits.
        fun strokePoly(points: String, c: Color = color) =
            drawPath(polyPath(points, close = false), c, style = stroke)

        when (symbol) {
            // Sheet with a plus; the horizontal arm of the + is red.
            EditorSymbol.NEW -> {
                strokeSlot(0); strokeSlot(1)
                line(512, 621.3, 512, 561.75)
                line(512, 761.75, 512, 701.9)
                line(412, 661.75, 612, 661.75, AccentRed)
            }
            // Sheet with a 3x2 dot block (markdown insert).
            EditorSymbol.ADD -> {
                strokeSlot(0); strokeSlot(1)
                dot(399.5, 628.97, 35.78, color)
                dot(624.5, 628.97, 35.78, AccentRed)
                dot(512, 628.97, 35.78, color)
                dot(512, 741.47, 35.78, color)
                dot(624.5, 741.47, 35.78, color)
                dot(399.5, 741.47, 35.78, color)
            }
            // Clipboard with a red down arrow (import / open a file).
            EditorSymbol.IMPORT, EditorSymbol.OPEN -> {
                strokeSlot(0)                                  // clipboard outline
                outlineBox(337, 112.25, 350, 100, 50)         // clip pill: stroked
                strokePoly("412 661.75 412 762.25 612 762.25 612 661.75")  // open tray
                line(512, 623.75, 512, 524.25, AccentRed)      // arrow shaft
                fillPoly("457.33 616.42 566.67 616.42 512 671.07 457.33 616.42", AccentRed)
            }
            // Clipboard with three rules, the middle one red.
            EditorSymbol.PASTE -> {
                strokeSlot(0)
                outlineBox(337, 112.25, 350, 100, 50)
                line(412, 661.75, 612, 661.75)
                line(412, 562.25, 612, 562.25, AccentRed)
                line(412, 762.25, 612, 762.25)
            }
            // Clipboard with a red up arrow (share the note as a file).
            EditorSymbol.SHARE -> {
                strokeSlot(0)
                outlineBox(337, 112.25, 350, 100, 50)
                strokePoly("412 661.75 412 762.25 612 762.25 612 661.75")
                line(512, 661.75, 512, 562.25, AccentRed)
                fillPoly("457.33 569.58 566.67 569.58 512 514.93 457.33 569.58", AccentRed)
            }
            EditorSymbol.UNDO -> {
                strokeSlot(0)
                fillPoly("432.87 437.49 329.25 377.66 432.87 317.82 432.87 437.49", color)
                dot(459.52, 610.58, 35.78, AccentRed)
            }
            EditorSymbol.REDO -> {
                strokeSlot(0)
                fillPoly("591.13 437.49 694.75 377.66 591.13 317.82 591.13 437.49", color)
                dot(564.48, 610.57, 35.78, AccentRed)
            }
            // Tray with an X; one stroke of the X is red.
            EditorSymbol.CLEAR, EditorSymbol.ERASE, EditorSymbol.DELETE -> {
                strokeSlot(0); strokeSlot(1)
                line(483.4, 620.9, 441.29, 578.79)
                line(582.71, 720.21, 540.39, 677.89)
                line(441.29, 720.21, 582.71, 578.79, AccentRed)
                line(137, 200.12, 887, 200.12)
            }
            // Floppy: save-as / export. Shutter is a STROKED red outline
            // (SVG rect cls-1), not the solid slab the first port drew.
            EditorSymbol.EXPORT -> {
                strokeSlot(0); strokeSlot(1); strokeSlot(2)
                outlineBox(347.58, 200.61, 100, 161.64, 0f, AccentRed)
            }
            // Three dots, the middle one red.
            EditorSymbol.MORE -> {
                dot(399.5, 512, 35.78, color)
                dot(624.5, 512, 35.78, AccentRed)
                dot(512, 512, 35.78, color)
            }
            // Left arrow with a red dot (back to library).
            EditorSymbol.BACK -> {
                line(405.2, 511.36, 568.39, 511.36)
                fillPoly("422.71 571.2 319.09 511.37 422.71 451.53 422.71 571.2", color)
                dot(669.13, 512, 35.78, AccentRed)
            }
            // Right arrow with a red dot (restore from trash). Shaft is
            // BLACK in the source (cls-1 in this file's stylesheet), not red.
            EditorSymbol.NEXT -> {
                line(326.57, 512, 489.77, 512)
                fillPoly("472.26 571.84 575.88 512.01 472.26 452.17 472.26 571.84", color)
                dot(661.65, 512, 35.78, AccentRed)
            }
            // Folder with an X (trash). Folder TAB is RED in the source
            // (cls-1 in this file's stylesheet); the front is a stroked
            // rounded outline, not the solid blob the first port drew.
            EditorSymbol.TRASH -> {
                strokeSlot(0, AccentRed)
                strokeSlot(1)
                outlineBox(174.5, 300.61, 700, 598.89, 100)
                line(483.4, 617.4, 441.29, 575.29)
                line(582.71, 716.71, 540.39, 674.39)
                line(441.29, 716.71, 582.71, 575.29, AccentRed)
            }
        }
    }
}
