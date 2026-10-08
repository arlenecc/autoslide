package com.autoslide.app

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 随机源抽象：隔离 kotlin.random.Random，便于测试中注入受控随机值。
 * [nextLong] 返回 [0, until) 内的值；[nextFloat] 返回 [0, 1) 内的值。
 */
interface RandomSource {
    fun nextLong(until: Long): Long
    fun nextFloat(): Float

    companion object {
        fun default(random: Random = Random.Default): RandomSource = object : RandomSource {
            override fun nextLong(until: Long): Long = random.nextLong(until)
            override fun nextFloat(): Float = random.nextFloat()
        }
    }
}

/** 二次贝塞尔弧段（拟人轨迹的一小段），附带该段执行时长。 */
data class SwipeCurve(
    val p0x: Float,
    val p0y: Float,
    val cx: Float,
    val cy: Float,
    val p2x: Float,
    val p2y: Float,
    val durationMs: Long,
)

/**
 * 一次拟人化上划手势的完整参数（绝对像素坐标）。
 *
 * 拟人要素：
 * - 起点落在拇指自然活动区（横向 30%~70%、纵向 65%~85%）
 * - 终点横向随机漂移并夹紧在屏幕内（避开系统边缘手势）
 * - 轨迹为随机弯向的弧线（控制点偏离弦中点 3%~8% 屏宽）
 * - 触屏后有 [startDelayMs] 的按压延迟才起滑
 * - 三段变速执行：起手慢、中段发力最快、收尾减速
 */
data class SwipeGesture(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val controlX: Float,
    val controlY: Float,
    val startDelayMs: Long,
    val durationMs: Long,
    val segments: List<SwipeCurve>,
)

private data class Pt(val x: Float, val y: Float)
private data class Quad(val p0: Pt, val c: Pt, val p2: Pt)

/** de Casteljau 二次贝塞尔切分：在参数 t 处拆为左右两段，保证拼接后与原曲线完全一致。 */
private fun deCasteljauSplit(q: Quad, t: Float): Pair<Quad, Quad> {
    val q0 = Pt(q.p0.x + (q.c.x - q.p0.x) * t, q.p0.y + (q.c.y - q.p0.y) * t)
    val q1 = Pt(q.c.x + (q.p2.x - q.c.x) * t, q.c.y + (q.p2.y - q.c.y) * t)
    val r = Pt(q0.x + (q1.x - q0.x) * t, q0.y + (q1.y - q0.y) * t)
    return Quad(q.p0, q0, r) to Quad(r, q1, q.p2)
}

/**
 * 纯逻辑层：生成随机切换间隔与拟人化上划手势。
 * 不依赖任何 Android API，可被 JVM 单元测试覆盖。
 */
class SwipePlanner(private val random: RandomSource = RandomSource.default()) {

    /** TC-01/02/03/04: 随机返回 [MIN_DELAY_MS, MAX_DELAY_MS] 闭区间内的切换间隔。 */
    fun nextDelayMs(): Long = MIN_DELAY_MS + random.nextLong(SPAN_MS)

    /**
     * TC-05~09、TC-21~24: 根据屏幕尺寸生成拟人化上划手势。
     * 屏幕尺寸非法（<=0）时返回 null，调用方应跳过本次手势。
     */
    fun nextSwipe(width: Int, height: Int): SwipeGesture? {
        if (width <= 0 || height <= 0) return null
        val w = width.toFloat()
        val h = height.toFloat()

        // 起点在拇指自然活动区，终点在中上部且横向漂移
        val startX = w * (START_X_MIN + random.nextFloat() * (START_X_MAX - START_X_MIN))
        val startY = h * (START_Y_MIN + random.nextFloat() * (START_Y_MAX - START_Y_MIN))
        val endY = h * (END_Y_MIN + random.nextFloat() * (END_Y_MAX - END_Y_MIN))
        val endX = (startX + (random.nextFloat() * 2f - 1f) * w * END_X_DRIFT_RATIO)
            .coerceIn(w * END_X_MARGIN_RATIO, w * (1f - END_X_MARGIN_RATIO))

        // 弧线：控制点位于弦中点沿法线方向偏移 offset 处，弯向与幅度随机
        val chordX = endX - startX
        val chordY = endY - startY
        val chordLen = sqrt(chordX * chordX + chordY * chordY)
        val magnitude = w * (CURVE_OFFSET_MIN + random.nextFloat() * (CURVE_OFFSET_MAX - CURVE_OFFSET_MIN))
        val sign = if (random.nextFloat() < 0.5f) -1f else 1f
        val offset = sign * magnitude
        val midX = (startX + endX) / 2f
        val midY = (startY + endY) / 2f
        val controlX = midX - chordY / chordLen * offset
        val controlY = midY + chordX / chordLen * offset

        // 时长：多数为快速轻扫，小概率慢速拖动，节奏更像真人
        val slow = random.nextFloat() < SLOW_SWIPE_PROBABILITY
        val durationMs = if (slow) {
            SLOW_DURATION_MIN_MS + random.nextLong(SLOW_DURATION_SPAN_MS)
        } else {
            FAST_DURATION_MIN_MS + random.nextLong(FAST_DURATION_SPAN_MS)
        }

        // 触屏预压延迟：手指先按下，短暂停顿后才开始滑动
        val startDelayMs = START_DELAY_MIN_MS + random.nextLong(START_DELAY_SPAN_MS)

        // 三段变速：起手慢、中段发力、收尾减速（中段占比恒最大）
        val w1 = SEG1_MIN + random.nextFloat() * (SEG1_MAX - SEG1_MIN)
        val w3 = SEG3_MIN + random.nextFloat() * (SEG3_MAX - SEG3_MIN)
        val d1 = (durationMs * w1).toLong().coerceAtLeast(1)
        val d3 = (durationMs * w3).toLong().coerceAtLeast(1)
        val d2 = (durationMs - d1 - d3).coerceAtLeast(1)

        // 用 de Casteljau 把整条弧精确切成三段，拼接处斜率连续
        val whole = Quad(Pt(startX, startY), Pt(controlX, controlY), Pt(endX, endY))
        val (head, rest) = deCasteljauSplit(whole, 1f / 3f)
        val (mid, tail) = deCasteljauSplit(rest, 0.5f)
        val segments = listOf(
            SwipeCurve(head.p0.x, head.p0.y, head.c.x, head.c.y, head.p2.x, head.p2.y, d1),
            SwipeCurve(mid.p0.x, mid.p0.y, mid.c.x, mid.c.y, mid.p2.x, mid.p2.y, d2),
            SwipeCurve(tail.p0.x, tail.p0.y, tail.c.x, tail.c.y, tail.p2.x, tail.p2.y, d3),
        )

        return SwipeGesture(
            startX, startY, endX, endY, controlX, controlY,
            startDelayMs, durationMs, segments,
        )
    }

    companion object {
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 30_000L
        const val SPAN_MS = MAX_DELAY_MS - MIN_DELAY_MS + 1

        // 起点区域（比例）
        const val START_X_MIN = 0.30f
        const val START_X_MAX = 0.70f
        const val START_Y_MIN = 0.65f
        const val START_Y_MAX = 0.85f

        // 终点：纵向区域 + 横向漂移与屏幕内边距
        const val END_Y_MIN = 0.20f
        const val END_Y_MAX = 0.40f
        const val END_X_DRIFT_RATIO = 0.12f
        const val END_X_MARGIN_RATIO = 0.03f

        // 弧线控制点相对弦中点的偏移（比例），曲线实际偏离弦约其一半
        const val CURVE_OFFSET_MIN = 0.03f
        const val CURVE_OFFSET_MAX = 0.08f

        // 时长（快速轻扫 / 慢速拖动）
        const val SLOW_SWIPE_PROBABILITY = 0.15f
        const val FAST_DURATION_MIN_MS = 180L
        const val FAST_DURATION_SPAN_MS = 241L // [180, 420]
        const val SLOW_DURATION_MIN_MS = 450L
        const val SLOW_DURATION_SPAN_MS = 251L // [450, 700]

        // 触屏预压延迟
        const val START_DELAY_MIN_MS = 20L
        const val START_DELAY_SPAN_MS = 71L // [20, 90]

        // 三段变速权重范围（w2 = 1 - w1 - w3，恒为最大段）
        const val SEG1_MIN = 0.26f
        const val SEG1_MAX = 0.34f
        const val SEG3_MIN = 0.20f
        const val SEG3_MAX = 0.28f
    }
}
