package ru.ngscanner.perf

import kotlinx.serialization.Serializable

/** Что меряем. Источник скорости — стандартный PID 010D (другого у OBD-II нет). */
@Serializable
enum class PerfKind(val label: String, val hint: String) {
    ACCEL_0_100("Разгон 0–100 км/ч", "Остановитесь. Как тронетесь — отсчёт пойдёт сам."),
    BRAKE_100_0("Торможение 100–0 км/ч", "Разгонитесь до 100+ км/ч, затем тормозите."),
    QUARTER_MILE("¼ мили (402 м)", "Остановитесь. Замер стартует с места."),
}

/** Фаза замера — определяет подсказку и вид экрана. */
enum class PerfPhase {
    /** Условие старта ещё не выполнено (не остановились / не разогнались до 100). */
    ARMING,

    /** Условие выполнено — ждём начала заезда. */
    READY,

    /** Заезд идёт, идёт отсчёт. */
    RUNNING,

    /** Есть результат. */
    DONE,

    /** Заезд прерван (остановились на разгоне, снова разогнались при торможении). */
    ABORTED,
}

/** Текущее состояние замера — то, что рисует экран. */
data class PerfState(
    val phase: PerfPhase,
    val speedKmh: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val distanceM: Double = 0.0,
    val resultSec: Double? = null,
    /** Скорость на финише ¼ мили (trap speed); для остальных замеров null. */
    val trapSpeedKmh: Double? = null,
)

/** Сохранённый результат заезда. */
@Serializable
data class PerfRun(
    val id: String,
    val kind: PerfKind,
    val dateIso: String,
    val seconds: Double,
    val trapSpeedKmh: Double? = null,
    val carTitle: String = "",
)

/**
 * Конечный автомат замера по потоку сэмплов скорости. Чистый (без Android и времени
 * «изнутри») — время и скорость подаются снаружи, поэтому тестируется целиком.
 *
 * Момент пересечения порога (100 км/ч, 402 м) берётся линейной интерполяцией между
 * соседними сэмплами: OBD отдаёт скорость дискретно (1 км/ч) и не чаще ~10 Гц, без
 * интерполяции ошибка достигала бы длительности шага опроса.
 *
 * Точность ограничена самим OBD (задержка ответа шины, разрешение 1 км/ч) — результат
 * оценочный, спортивный таймер он не заменяет. Дистанция ¼ мили считается интегралом
 * скорости (трапеции), а не GPS.
 */
class PerfCalc(private val kind: PerfKind) {

    private var phase = PerfPhase.ARMING
    private var t0Ms: Double? = null
    private var prevT: Long? = null
    private var prevV: Double? = null
    private var distanceM = 0.0
    private var result: Double? = null
    private var trapSpeed: Double? = null

    /** Подаёт очередной сэмпл (время, км/ч) и возвращает новое состояние. */
    fun sample(tMs: Long, speedKmh: Double): PerfState {
        val v = speedKmh.coerceAtLeast(0.0)
        val pt = prevT
        val pv = prevV

        when (phase) {
            PerfPhase.ARMING -> when (kind) {
                PerfKind.ACCEL_0_100, PerfKind.QUARTER_MILE -> if (v <= STOP_EPS) phase = PerfPhase.READY
                PerfKind.BRAKE_100_0 -> if (v >= TARGET_KMH) phase = PerfPhase.READY
            }

            PerfPhase.READY -> when (kind) {
                PerfKind.ACCEL_0_100, PerfKind.QUARTER_MILE -> if (v > LAUNCH_EPS) {
                    // Старт — последний сэмпл, когда машина ещё стояла.
                    t0Ms = (pt ?: tMs).toDouble()
                    distanceM = 0.0
                    phase = PerfPhase.RUNNING
                }
                PerfKind.BRAKE_100_0 -> if (pt != null && pv != null && pv >= TARGET_KMH && v < TARGET_KMH) {
                    // Старт — момент пересечения 100 км/ч вниз.
                    t0Ms = crossMs(pt, pv, tMs, v, TARGET_KMH)
                    phase = PerfPhase.RUNNING
                }
            }

            PerfPhase.RUNNING -> {
                val t0 = t0Ms
                if (pt != null && pv != null && t0 != null) {
                    val dt = (tMs - pt) / 1000.0
                    val prevDistance = distanceM
                    if (dt > 0) distanceM += (pv + v) / 2.0 / 3.6 * dt // м, трапеция
                    when (kind) {
                        PerfKind.ACCEL_0_100 -> when {
                            v <= STOP_EPS -> phase = PerfPhase.ABORTED
                            v >= TARGET_KMH && pv < TARGET_KMH -> {
                                result = (crossMs(pt, pv, tMs, v, TARGET_KMH) - t0) / 1000.0
                                phase = PerfPhase.DONE
                            }
                        }

                        PerfKind.QUARTER_MILE -> when {
                            v <= STOP_EPS -> phase = PerfPhase.ABORTED
                            distanceM >= QUARTER_M && prevDistance < QUARTER_M -> {
                                val frac = ((QUARTER_M - prevDistance) / (distanceM - prevDistance)).coerceIn(0.0, 1.0)
                                result = (pt + frac * (tMs - pt) - t0) / 1000.0
                                trapSpeed = pv + frac * (v - pv)
                                phase = PerfPhase.DONE
                            }
                        }

                        PerfKind.BRAKE_100_0 -> when {
                            // Снова разгоняется — это уже не замер торможения.
                            v > pv + REACCEL_EPS -> phase = PerfPhase.ABORTED
                            v <= STOP_EPS -> {
                                result = (tMs - t0) / 1000.0
                                phase = PerfPhase.DONE
                            }
                        }
                    }
                }
            }

            PerfPhase.DONE, PerfPhase.ABORTED -> Unit
        }

        prevT = tMs
        prevV = v
        val elapsed = t0Ms?.let { ((tMs - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0
        return PerfState(
            phase = phase,
            speedKmh = v,
            elapsedSec = result ?: elapsed,
            distanceM = distanceM,
            resultSec = result,
            trapSpeedKmh = trapSpeed,
        )
    }

    /** Момент пересечения [target] между двумя сэмплами (линейно), мс. */
    private fun crossMs(t1: Long, v1: Double, t2: Long, v2: Double, target: Double): Double {
        if (v2 == v1) return t2.toDouble()
        val frac = ((target - v1) / (v2 - v1)).coerceIn(0.0, 1.0)
        return t1 + frac * (t2 - t1)
    }

    companion object {
        const val TARGET_KMH = 100.0
        const val QUARTER_M = 402.336 // 1/4 мили
        private const val STOP_EPS = 0.5 // «стоим» (км/ч): OBD отдаёт целые км/ч
        private const val LAUNCH_EPS = 0.5 // «тронулись»
        private const val REACCEL_EPS = 2.0 // допуск шума при торможении
    }
}
