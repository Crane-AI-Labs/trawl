package trawl.desktop

import com.craneai.findr.core.LearningSpeed

/**
 * "How hard should Trawl work", on a computer. The phone's three names ([LearningSpeed]) mean here, each as a [DutyCell]:
 * the engine's thread count (the knob that decides how quickly one file is read) and how many cores the reader may average in each state
 * of the person and of the power ([DutyTable]).
 *  - Gentle: a quarter of the processor cores (at least one), and no reading while the computer runs on its battery.
 *  - Balanced: two threads, so that a stretch of reading never needs more than a couple of cores, whichever laptop it runs on.
 *  - Fast: half the cores (never fewer than four where the machine has them), battery or not. Measured on an 8-vCPU box, more threads
 *    than that were slower, not faster: 8 threads read 127 files a minute, 6 read 131, 4 read 143. The processor's logical cores
 *    share their execution units and the rest of the reading pipeline (decoding, OCR, the database) needs room too.
 */
object WorkSpeed {
  /** CPU threads for the text side of the engine. */
  fun threads(speed: LearningSpeed, cores: Int = Runtime.getRuntime().availableProcessors()): Int? = DutyTable.fromTuning(cores).of(speed).threads

  fun waitsForCharger(speed: LearningSpeed): Boolean = speed == LearningSpeed.GENTLE
}

/** Whether the person is using the computer: [UNKNOWN] (the system cannot say) is treated as [ACTIVE], so a wrong guess is always the polite one. */
enum class UserState { ACTIVE, AWAY, UNKNOWN }

/**
 * What one speed does: [threads] for the engine, and the most cores the reader averages while the person is [activeCores], or away from
 * the keyboard on the battery ([awayBatteryCores]) or on the charger ([awayAcCores]). 0 means no limit of its own (the computer's spare
 * cores still apply, see [Politeness]). Cores used on average are threads times duty, so each cell is a pair, not a percentage.
 */
data class DutyCell(val threads: Int, val activeCores: Double, val awayBatteryCores: Double, val awayAcCores: Double) {
  fun cores(user: UserState, onBattery: Boolean): Double = when {
    user != UserState.AWAY -> activeCores
    onBattery -> awayBatteryCores
    else -> awayAcCores
  }
}

class DutyTable(private val cells: Map<LearningSpeed, DutyCell>) {
  fun of(speed: LearningSpeed): DutyCell = cells.getValue(speed)

  companion object {
    /** The table for a computer with [cores] logical cores, from [BackgroundTuning]'s numbers (a thread count of 0 there means the formula). */
    fun fromTuning(cores: Int = Runtime.getRuntime().availableProcessors()): DutyTable {
      val t = BackgroundTuning
      fun threads(set: Int, formula: Int) = (if (set > 0) set else formula).coerceIn(1, cores.coerceAtLeast(1))
      return DutyTable(mapOf(
        LearningSpeed.GENTLE to DutyCell(threads(t.threadsGentle, cores / 4), t.gentleActive, t.gentleAwayBattery, t.gentleAwayAc),
        LearningSpeed.BALANCED to DutyCell(threads(t.threadsBalanced, 2), t.balancedActive, t.balancedAwayBattery, t.balancedAwayAc),
        LearningSpeed.FAST to DutyCell(threads(t.threadsFast, (cores / 2).coerceAtLeast(4)), t.fastActive, t.fastAwayBattery, t.fastAwayAc),
      ))
    }
  }
}
