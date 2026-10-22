package trawl.desktop

import com.craneai.findr.core.LearningSpeed

/**
 * "How hard should Trawl work", on a computer. The phone's three names ([LearningSpeed]) mean here:
 *  - Gentle: a quarter of the processor cores (at least one), and no reading while the computer runs on its battery.
 *  - Balanced: the engine's own thread count and no battery rule, exactly how Trawl has always run.
 *  - Fast: half the cores (never fewer than four where the machine has them), battery or not. Measured on an 8-vCPU box, more threads
 *    than that were slower, not faster: 8 threads read 127 files a minute, 6 read 131, 4 read 143. The processor's logical cores
 *    share their execution units and the rest of the reading pipeline (decoding, OCR, the database) needs room too.
 * The thread count is the only knob that changes how quickly one file is read, so it is the one the speed turns.
 */
object WorkSpeed {
  /** CPU threads for the text side of the engine, or null to leave it to LiteRT-LM (Balanced). */
  fun threads(speed: LearningSpeed, cores: Int = Runtime.getRuntime().availableProcessors()): Int? = when (speed) {
    LearningSpeed.GENTLE -> (cores / 4).coerceAtLeast(1)
    LearningSpeed.BALANCED -> null
    LearningSpeed.FAST -> (cores / 2).coerceAtLeast(4).coerceAtMost(cores).coerceAtLeast(1)
  }

  fun waitsForCharger(speed: LearningSpeed): Boolean = speed == LearningSpeed.GENTLE
}
