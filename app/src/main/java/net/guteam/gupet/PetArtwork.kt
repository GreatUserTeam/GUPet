package com.frizovdanya.pixpet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

private const val GRID_W = 18
private const val GRID_H = 13

enum class PetSpecies(val key: String, val title: String) {
    GUPET("gupet", "GUPet"),
    CAT("cat", "Кошка"),
    DOG("dog", "Собака");

    companion object {
        fun fromKey(key: String?): PetSpecies = entries.firstOrNull { it.key == key } ?: GUPET
    }
}

internal object PetPalettes {
    val names = listOf("Классический", "Мятный", "Лавандовый", "Небесный", "Розовый", "Графит")
    private val body = intArrayOf(
        0xFFD97757.toInt(), 0xFF4FBF98.toInt(), 0xFF9B8AEE.toInt(),
        0xFF4FA8E8.toInt(), 0xFFEE85AE.toInt(), 0xFF697580.toInt(),
    )
    private val eye = intArrayOf(
        0xFF37221E.toInt(), 0xFF163328.toInt(), 0xFF29234A.toInt(),
        0xFF163248.toInt(), 0xFF4B1F35.toInt(), 0xFFF4F1E8.toInt(),
    )
    private val shade = intArrayOf(
        0xFFB7553F.toInt(), 0xFF2D9875.toInt(), 0xFF7866CB.toInt(),
        0xFF317DAC.toInt(), 0xFFC95783.toInt(), 0xFF45505D.toInt(),
    )
    private val outline = intArrayOf(
        0xFF743927.toInt(), 0xFF205E4A.toInt(), 0xFF4B3D8B.toInt(),
        0xFF20577F.toInt(), 0xFF873958.toInt(), 0xFF303842.toInt(),
    )
    private val highlight = intArrayOf(
        0xFFFFAC85.toInt(), 0xFF9CE4C6.toInt(), 0xFFD3CAFF.toInt(),
        0xFFB4E2FF.toInt(), 0xFFFFC4D6.toInt(), 0xFFAEB8C1.toInt(),
    )

    fun body(index: Int) = body[index.coerceIn(body.indices)]
    fun eye(index: Int) = eye[index.coerceIn(eye.indices)]
    fun shade(index: Int) = shade[index.coerceIn(shade.indices)]
    fun outline(index: Int) = outline[index.coerceIn(outline.indices)]
    fun highlight(index: Int) = highlight[index.coerceIn(highlight.indices)]
    fun blush(index: Int): Int = blend(body(index), 0xFFFF6F91.toInt(), 0.58f)

    private fun blend(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * t).toInt()
        val g = (((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * t).toInt()
        val blue = ((a and 255) + ((b and 255) - (a and 255)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or blue
    }
}

internal enum class PetPose { IDLE, WALK, PRESS, DRAG, FLY, PET, SLEEP, HANG, PEEK, STRETCH, YAWN, GROOM, SPIN, DANCE }
internal enum class PetExpression { OPEN, BLINK, HAPPY, SURPRISED, DIZZY, ANGRY, SLEEP, LOVE }
internal enum class ParticleKind { HEART, SPARK, ZZZ, NOTE, STAR }

internal data class PetFrame(
    val pose: PetPose = PetPose.IDLE,
    val expression: PetExpression = PetExpression.OPEN,
    val look: Int = 0,
    val waveSide: Int = 0,
    val blush: Boolean = false,
    val mouthOpen: Boolean = false,
    val squash: Float = 0f,
    val rotation: Float = 0f,
    val hopOffset: Float = 0f,
    val phase: Float = 0f,
)

internal data class PetParticle(
    val kind: ParticleKind,
    val x: Float,
    val y: Float,
    val driftX: Float,
    val bornAtMs: Long,
    val durationMs: Long,
)

class PetArtView(context: Context) : View(context) {
    internal var species: PetSpecies = PetSpecies.GUPET
        set(value) { field = value; invalidate() }
    internal var paletteIndex: Int = 0
        set(value) { field = value.coerceIn(0, PetPalettes.names.lastIndex); invalidate() }
    internal var customSkin: GUPetSkin? = null
        set(value) { field = value; invalidate() }
    internal var frame: PetFrame = PetFrame()
        set(value) { field = value; invalidate() }
    internal var particles: List<PetParticle> = emptyList()
        set(value) { field = value; invalidate() }
    internal var frameTimeMs: Long = 0L
        set(value) { field = value; invalidate() }

    private val pixelPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        isFilterBitmap = false
    }

    init {
        isClickable = true
        isFocusable = false
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        PetRenderer.draw(canvas, pixelPaint, width.toFloat(), height.toFloat(), species, paletteIndex, frame, frameTimeMs, particles, customSkin)
    }
}

internal object PetRenderer {
    private val bodyLeft = intArrayOf(5, 4, 3, 2, 2, 2, 2, 2, 3, 4)
    private val bodyRight = intArrayOf(13, 14, 15, 16, 16, 16, 16, 16, 15, 14)
    private val gupetBodyLeft = intArrayOf(5, 4, 3, 3, 3, 4, 5)
    private val gupetBodyRight = intArrayOf(13, 14, 15, 15, 15, 14, 13)
    private val legX = intArrayOf(4, 7, 11, 14)
    private val gupetLegX = intArrayOf(4, 6, 11, 13)
    private val heart = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
    private val spark = arrayOf("..X..", "..X..", "XX.XX", "..X..", "..X..")
    private val star = arrayOf("..X..", ".XXX.", "XXXXX", ".XXX.", "..X..")
    private val zzz = arrayOf("XXXXX", "...X.", "..X..", ".X...", "XXXXX")
    private val note = arrayOf("..XXXXX", "..X...X", "..X...X", "..X...X", "XXX.XXX", "XXX.XXX")
    private val bitmapPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = false
        isFilterBitmap = false
    }

    fun draw(
        canvas: Canvas,
        paint: Paint,
        width: Float,
        height: Float,
        species: PetSpecies,
        palette: Int,
        frame: PetFrame,
        nowMs: Long,
        particles: List<PetParticle>,
        customSkin: GUPetSkin? = null,
    ) {
        if (width <= 0f || height <= 0f) return
        val unit = min(width / GRID_W, height / (GRID_H + 2f))
        if (unit <= 0f) return
        val topPad = unit * 2f
        val centerX = width / 2f
        val centerY = topPad + unit * 6.5f
        val idleBreath = if (frame.pose == PetPose.IDLE || frame.pose == PetPose.PET || frame.pose == PetPose.SLEEP) {
            sin(frame.phase * if (frame.pose == PetPose.SLEEP) 2.2f else 3.1f) * 0.34f * unit
        } else 0f
        val danceBob = if (frame.pose == PetPose.DANCE) abs(sin(frame.phase * 10f)) * unit * 0.8f else 0f
        val squashX = when {
            frame.pose == PetPose.STRETCH -> 0.97f
            frame.squash > 0f -> 1.08f
            frame.squash < 0f -> 0.93f
            else -> 1f
        }
        val squashY = when {
            frame.pose == PetPose.STRETCH -> 1.06f
            frame.squash > 0f -> 0.91f
            frame.squash < 0f -> 1.08f
            else -> 1f
        }

        canvas.save()
        canvas.rotate(frame.rotation, centerX, centerY + frame.hopOffset)
        canvas.translate(0f, frame.hopOffset - idleBreath - danceBob)
        canvas.scale(squashX, squashY, centerX, centerY)

        if (customSkin != null) {
            val bitmap = customSkin.frameFor(frame)
            val destination = RectF(0f, topPad, GRID_W * unit, topPad + GRID_H * unit)
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
            drawParticles(canvas, paint, unit, nowMs, particles)
            canvas.restore()
            return
        }

        val body = PetPalettes.body(palette)
        val eye = PetPalettes.eye(palette)
        val shade = PetPalettes.shade(palette)
        val outline = PetPalettes.outline(palette)
        val highlight = PetPalettes.highlight(palette)
        val blush = PetPalettes.blush(palette)
        val alternate = (frame.phase * if (species == PetSpecies.DOG) 13f else 5f).toInt() and 1
        val tailLift = when {
            species == PetSpecies.CAT -> if (alternate == 0) 0 else 1
            species == PetSpecies.DOG -> if (alternate == 0) 1 else -1
            else -> 0
        }
        when (species) {
            PetSpecies.CAT -> {
                px(canvas, paint, 14, 8, 2, 1, outline, unit, topPad)
                px(canvas, paint, 15, 7 - tailLift, 2, 1, shade, unit, topPad)
                px(canvas, paint, 16, 5 - tailLift, 1, 3, body, unit, topPad)
                px(canvas, paint, 15, 4 - tailLift, 1, 1, highlight, unit, topPad)
                px(canvas, paint, 4, 2, 4, 4, outline, unit, topPad)
                px(canvas, paint, 5, 1, 3, 4, body, unit, topPad)
                px(canvas, paint, 6, 1, 1, 2, 0xFFFFB2BC.toInt(), unit, topPad)
                px(canvas, paint, 11, 1, 3, 4, body, unit, topPad)
                px(canvas, paint, 11, 2, 4, 4, outline, unit, topPad)
                px(canvas, paint, 12, 1, 1, 2, 0xFFFFB2BC.toInt(), unit, topPad)
            }
            PetSpecies.DOG -> {
                px(canvas, paint, 14, 6 + tailLift, 2, 2, outline, unit, topPad)
                px(canvas, paint, 15, 5 + tailLift, 2, 2, body, unit, topPad)
                px(canvas, paint, 16, 5 + tailLift, 1, 1, highlight, unit, topPad)
                px(canvas, paint, 1, 3, 4, 4, outline, unit, topPad)
                px(canvas, paint, 2, 3, 3, 4, shade, unit, topPad)
                px(canvas, paint, 13, 3, 4, 4, outline, unit, topPad)
                px(canvas, paint, 13, 3, 3, 4, shade, unit, topPad)
                px(canvas, paint, 3, 4, 1, 2, highlight, unit, topPad)
            }
            PetSpecies.GUPET -> Unit
        }
        paint.color = 0x55352520
        canvas.drawRect(unit * 4f, topPad + unit * 12.5f, unit * 14f, topPad + unit * 13f, paint)
        paint.color = 0xFF000000.toInt()

        val outlineExpansion = if (frame.pose == PetPose.PET || frame.pose == PetPose.STRETCH) 1 else 0
        val activeLeft = if (species == PetSpecies.GUPET) gupetBodyLeft else bodyLeft
        val activeRight = if (species == PetSpecies.GUPET) gupetBodyRight else bodyRight
        val bodyStartY = if (species == PetSpecies.GUPET) 3 else 2
        for (row in activeLeft.indices) {
            val y = row + bodyStartY
            val left = activeLeft[row] - outlineExpansion
            val right = activeRight[row] + outlineExpansion
            px(canvas, paint, left - 1, y, right - left + 2, 1, outline, unit, topPad)
            px(canvas, paint, left, y, right - left, 1, body, unit, topPad)
            if (row in 2..7) px(canvas, paint, right - 2, y, 1, 1, shade, unit, topPad)
            if (row in 1..4) px(canvas, paint, left + 1, y, 1, 1, highlight, unit, topPad)
        }

        val walk = frame.pose == PetPose.WALK || frame.pose == PetPose.PEEK || frame.pose == PetPose.DRAG
        val step = ((frame.phase * 8f).toInt() and 1)
        val activeLegs = if (species == PetSpecies.GUPET) gupetLegX else legX
        val legY = if (species == PetSpecies.GUPET) 10 else 11
        for (i in activeLegs.indices) {
            val footHeight = if (frame.pose == PetPose.SLEEP) 1 else if (walk && ((i + step) and 1) == 0) 2 else 1
            px(canvas, paint, activeLegs[i], legY, 2, footHeight, outline, unit, topPad)
            px(canvas, paint, activeLegs[i], legY, 1, footHeight, body, unit, topPad)
        }

        val armsUp = frame.pose == PetPose.DRAG || frame.pose == PetPose.FLY || frame.pose == PetPose.HANG ||
            frame.pose == PetPose.STRETCH || frame.pose == PetPose.DANCE
        val groomLeft = frame.pose == PetPose.GROOM && alternate == 0
        val groomRight = frame.pose == PetPose.GROOM && alternate != 0
        drawArm(canvas, paint, side = -1, raised = armsUp || frame.waveSide < 0 || groomLeft, body = body, outline = outline, highlight = highlight, unit = unit, top = topPad)
        drawArm(canvas, paint, side = 1, raised = armsUp || frame.waveSide > 0 || groomRight, body = body, outline = outline, highlight = highlight, unit = unit, top = topPad)

        val leftEye = (if (species == PetSpecies.GUPET) 6 else 5) + frame.look.coerceIn(-1, 1)
        val rightEye = 11 + frame.look.coerceIn(-1, 1)
        val eyeY = 5
        val expression = if (frame.pose == PetPose.SLEEP || frame.pose == PetPose.YAWN) PetExpression.SLEEP else frame.expression
        drawEye(canvas, paint, leftEye, eyeY, eye, outline, expression, unit, topPad, species)
        drawEye(canvas, paint, rightEye, eyeY, eye, outline, expression, unit, topPad, species)

        if (frame.blush || frame.pose == PetPose.PET || frame.expression == PetExpression.LOVE) {
            px(canvas, paint, 4, 7, 2, 1, blush, unit, topPad)
            px(canvas, paint, 13, 7, 2, 1, blush, unit, topPad)
        }

        when (species) {
            PetSpecies.DOG -> {
                px(canvas, paint, 7, 7, 4, 2, 0xFFFFE2BE.toInt(), unit, topPad)
                px(canvas, paint, 8, 7, 2, 1, outline, unit, topPad)
                if (frame.mouthOpen || frame.pose == PetPose.YAWN) px(canvas, paint, 8, 8, 2, 1, 0xFFEA6688.toInt(), unit, topPad)
            }
            PetSpecies.CAT -> {
                px(canvas, paint, 8, 7, 1, 1, 0xFFFFA9B7.toInt(), unit, topPad)
                px(canvas, paint, 7, 8, 2, 1, outline, unit, topPad)
                if (frame.mouthOpen || frame.pose == PetPose.YAWN) px(canvas, paint, 8, 8, 1, 1, 0xFFEA6688.toInt(), unit, topPad)
            }
            PetSpecies.GUPET -> {
                if (frame.mouthOpen || frame.pose == PetPose.YAWN) {
                    px(canvas, paint, 8, 7, 2, 2, outline, unit, topPad)
                    px(canvas, paint, 8, 8, 2, 1, 0xFFEA6688.toInt(), unit, topPad)
                } else {
                    px(canvas, paint, 8, 7, 1, 1, outline, unit, topPad)
                    px(canvas, paint, 9, 8, 1, 1, outline, unit, topPad)
                }
            }
        }

        drawParticles(canvas, paint, unit, nowMs, particles)
        canvas.restore()
    }

    private fun drawArm(canvas: Canvas, paint: Paint, side: Int, raised: Boolean, body: Int, outline: Int, highlight: Int, unit: Float, top: Float) {
        val baseX = if (side < 0) 1 else 16
        if (raised) {
            px(canvas, paint, baseX, 5, 2, 3, outline, unit, top)
            px(canvas, paint, baseX + if (side < 0) 1 else 0, 5, 1, 2, body, unit, top)
            px(canvas, paint, if (side < 0) 0 else 17, 4, 1, 2, body, unit, top)
        } else {
            px(canvas, paint, baseX, 7, 2, 2, outline, unit, top)
            px(canvas, paint, baseX + if (side < 0) 1 else 0, 7, 1, 2, body, unit, top)
            px(canvas, paint, if (side < 0) 0 else 17, 8, 1, 1, highlight, unit, top)
        }
    }

    private fun drawEye(canvas: Canvas, paint: Paint, x: Int, y: Int, color: Int, outline: Int, expression: PetExpression, unit: Float, top: Float, species: PetSpecies) {
        if (species == PetSpecies.GUPET) {
            when (expression) {
                PetExpression.BLINK, PetExpression.SLEEP -> px(canvas, paint, x - 1, y + 1, 3, 1, color, unit, top)
                PetExpression.HAPPY -> {
                    px(canvas, paint, x - 1, y + 1, 1, 1, color, unit, top)
                    px(canvas, paint, x, y, 1, 1, color, unit, top)
                    px(canvas, paint, x + 1, y + 1, 1, 1, color, unit, top)
                }
                PetExpression.SURPRISED -> {
                    px(canvas, paint, x, y, 1, 2, color, unit, top)
                    px(canvas, paint, x, y, 1, 1, 0xFFFFFFFF.toInt(), unit, top)
                }
                PetExpression.DIZZY -> {
                    px(canvas, paint, x - 1, y, 1, 1, color, unit, top)
                    px(canvas, paint, x + 1, y, 1, 1, color, unit, top)
                    px(canvas, paint, x, y + 1, 1, 1, color, unit, top)
                    px(canvas, paint, x - 1, y + 2, 1, 1, color, unit, top)
                    px(canvas, paint, x + 1, y + 2, 1, 1, color, unit, top)
                }
                PetExpression.ANGRY -> {
                    px(canvas, paint, x, y, 1, 2, color, unit, top)
                    px(canvas, paint, x - 1, y - 1, 2, 1, color, unit, top)
                }
                PetExpression.LOVE -> {
                    px(canvas, paint, x - 1, y, 1, 1, 0xFFFF6485.toInt(), unit, top)
                    px(canvas, paint, x + 1, y, 1, 1, 0xFFFF6485.toInt(), unit, top)
                    px(canvas, paint, x, y + 1, 1, 1, 0xFFFF6485.toInt(), unit, top)
                }
                PetExpression.OPEN -> {
                    px(canvas, paint, x, y, 1, 2, color, unit, top)
                    px(canvas, paint, x, y, 1, 1, 0xFFFFFFFF.toInt(), unit, top)
                }
            }
            return
        }
        when (expression) {
            PetExpression.BLINK, PetExpression.SLEEP -> {
                px(canvas, paint, x - 1, y + 1, 3, 1, color, unit, top)
                px(canvas, paint, x, y, 1, 1, outline, unit, top)
            }
            PetExpression.HAPPY -> {
                px(canvas, paint, x - 1, y + 1, 1, 1, color, unit, top)
                px(canvas, paint, x, y, 1, 1, color, unit, top)
                px(canvas, paint, x + 1, y + 1, 1, 1, color, unit, top)
            }
            PetExpression.SURPRISED -> {
                px(canvas, paint, x, y, 2, 3, 0xFFFFF5E9.toInt(), unit, top)
                px(canvas, paint, x, y + 1, 1, 1, color, unit, top)
                px(canvas, paint, x + 1, y, 1, 1, 0xFFFFFFFF.toInt(), unit, top)
            }
            PetExpression.DIZZY -> {
                px(canvas, paint, x - 1, y, 1, 1, color, unit, top)
                px(canvas, paint, x + 1, y, 1, 1, color, unit, top)
                px(canvas, paint, x, y + 1, 1, 1, color, unit, top)
                px(canvas, paint, x - 1, y + 2, 1, 1, color, unit, top)
                px(canvas, paint, x + 1, y + 2, 1, 1, color, unit, top)
            }
            PetExpression.ANGRY -> {
                px(canvas, paint, x - 1, y, 3, 1, color, unit, top)
                px(canvas, paint, x, y + 1, 1, 2, color, unit, top)
            }
            PetExpression.LOVE -> {
                px(canvas, paint, x - 1, y, 1, 1, 0xFFFF6485.toInt(), unit, top)
                px(canvas, paint, x + 1, y, 1, 1, 0xFFFF6485.toInt(), unit, top)
                px(canvas, paint, x, y + 1, 1, 1, 0xFFFF6485.toInt(), unit, top)
                px(canvas, paint, x, y + 2, 1, 1, 0xFFFF6485.toInt(), unit, top)
            }
            PetExpression.OPEN -> {
                px(canvas, paint, x - 1, y, 3, 3, 0xFFFFF7EC.toInt(), unit, top)
                px(canvas, paint, x, y + 1, 1, 2, color, unit, top)
                px(canvas, paint, x - 1, y, 1, 1, 0xFFFFFFFF.toInt(), unit, top)
            }
        }
    }

    private fun drawParticles(canvas: Canvas, paint: Paint, unit: Float, nowMs: Long, particles: List<PetParticle>) {
        for (particle in particles) {
            val elapsed = (nowMs - particle.bornAtMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / particle.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val alpha = (255f * (1f - progress)).toInt()
            val cell = unit * 0.72f
            val x = particle.x + particle.driftX * progress
            val y = particle.y - unit * (2.5f * progress)
            drawParticle(canvas, paint, particle.kind, x, y, cell, alpha)
        }
    }

    private fun drawParticle(canvas: Canvas, paint: Paint, kind: ParticleKind, x: Float, y: Float, cell: Float, alpha: Int) {
        val rows = when (kind) {
            ParticleKind.HEART -> heart
            ParticleKind.SPARK -> spark
            ParticleKind.STAR -> star
            ParticleKind.ZZZ -> zzz
            ParticleKind.NOTE -> note
        }
        val color = when (kind) {
            ParticleKind.HEART -> 0xFFFF5C7A.toInt()
            ParticleKind.SPARK -> 0xFFFFC94D.toInt()
            ParticleKind.STAR -> 0xFFFFE07A.toInt()
            ParticleKind.ZZZ -> 0xFF9EAEE5.toInt()
            ParticleKind.NOTE -> 0xFF64B5F6.toInt()
        }
        paint.color = color
        paint.alpha = alpha
        for (row in rows.indices) {
            for (col in rows[row].indices) {
                if (rows[row][col] == 'X') {
                    canvas.drawRect(x + col * cell, y + row * cell, x + (col + 1) * cell, y + (row + 1) * cell, paint)
                }
            }
        }
        paint.alpha = 255
    }

    private fun px(canvas: Canvas, paint: Paint, x: Int, y: Int, w: Int, h: Int, color: Int, unit: Float, top: Float) {
        paint.color = color
        paint.alpha = color ushr 24
        canvas.drawRect(x * unit, top + y * unit, (x + w) * unit, top + (y + h) * unit, paint)
        paint.alpha = 255
    }
}

@SuppressLint("ViewConstructor")
class PoopArtView(context: Context, private val onClean: () -> Unit) : View(context) {
    private val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }

    companion object {
        private val pattern = arrayOf(
            "....X....", "...XXo...", "..XXXXX..", ".XXXXXXo.",
            ".XwkXwkX.", "XXXXXXXXo", "XXXXXXXXX",
        )
    }

    init {
        isClickable = true
        setOnClickListener { onClean() }
        contentDescription = "Убрать кучку"
        scaleX = 0.1f
        scaleY = 0.1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = min(width / 9f, height / 7f)
        val ox = (width - cell * 9f) / 2f
        val oy = (height - cell * 7f) / 2f
        for (row in pattern.indices) {
            for (col in pattern[row].indices) {
                val color = when (pattern[row][col]) {
                    'X' -> 0xFF8A5A33.toInt()
                    'o' -> 0xFFB27D4E.toInt()
                    'w' -> 0xFFFFFFFF.toInt()
                    'k' -> 0xFF1A1A1A.toInt()
                    else -> 0
                }
                if (color != 0) {
                    paint.color = color
                    canvas.drawRect(ox + col * cell, oy + row * cell, ox + (col + 1) * cell, oy + (row + 1) * cell, paint)
                }
            }
        }
    }
}
