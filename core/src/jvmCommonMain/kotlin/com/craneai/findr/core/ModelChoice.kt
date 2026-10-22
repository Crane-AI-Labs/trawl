package com.craneai.findr.core

import com.craneai.findr.engine.BundleClass
import com.craneai.findr.engine.ModelBundle

/**
 * Which model pack the person wants (save-data choice). [STANDARD] is `trawl-models.zip` (670 MB): the 440m model with vision,
 * the quick-look pack, speech to text and the private-preview pack. [SAVE_DATA] is `trawl-models-lite.zip` (304 MB): the 270m
 * text model, quick look and private previews, no speech. English search measures the same on both; Luganda and Swahili photo
 * search is weaker on the small pack and videos and voice notes are not read for speech (internal/research/save-data-choice.md).
 */
enum class ModelChoice(val id: String) {
  STANDARD("standard"), SAVE_DATA("save-data");

  companion object {
    fun parse(id: String?): ModelChoice? = entries.firstOrNull { it.id == id }
  }
}

/** A tiny string store the choice persists in (Android: the `delivery` SharedPreferences; desktop: its settings file; tests: a map). */
interface ChoiceKeyValue {
  fun get(key: String): String?
  fun put(key: String, value: String)
}

/**
 * The person's answer, remembered across launches. [hardware] decides whether an answer is kept at all: a 2 GB phone is always on
 * Save data and stores nothing. Installing a signed pack records what was installed as the answer, so the pack on the device and
 * the answer cannot disagree.
 */
class ModelChoiceStore(private val kv: ChoiceKeyValue, private val hardware: BundleClass) {
  fun stored(): ModelChoice? = ModelChoice.parse(kv.get(KEY))

  fun set(choice: ModelChoice) {
    if (ModelPlan.offered(hardware)) kv.put(KEY, choice.id)
  }

  /** A full bundle of [installed] class went in: that is the answer now. */
  fun recordInstalled(installed: BundleClass) {
    ModelPlan.choiceOf(installed)?.let(::set)
  }

  /** The choice in force, given the class of the signed pack on the device (null when none). */
  fun effective(installed: BundleClass?): ModelChoice = ModelPlan.effective(hardware, stored(), installed)

  companion object {
    const val KEY = "model_choice"
  }
}

/**
 * The rules around [ModelChoice], pure so they are unit-tested. Signature checks are not here and are not touched: they stay in
 * the installer ([com.craneai.findr.engine.SignedBundleInstaller]); this only decides what is offered, which file the browser is
 * sent to, and which classes the installer is told to accept.
 */
object ModelPlan {
  /**
   * Whether the person is asked at all. A phone whose hardware gets the LITE stack (under about 2.6 GB of memory) is
   * always on the small pack, as before: the large one does not fit it, so offering it would only lead to a refusal.
   */
  fun offered(hardware: BundleClass): Boolean = hardware == BundleClass.STANDARD

  /** The bundle class a choice downloads on this hardware. */
  fun bundleClass(hardware: BundleClass, choice: ModelChoice): BundleClass =
    if (hardware == BundleClass.LITE || choice == ModelChoice.SAVE_DATA) BundleClass.LITE else BundleClass.STANDARD

  fun archiveName(hardware: BundleClass, choice: ModelChoice): String =
    if (bundleClass(hardware, choice) == BundleClass.LITE) ModelBundle.LITE_ARCHIVE_NAME else ModelBundle.ARCHIVE_NAME

  /**
   * What the installer takes. Unchanged from before the choice existed: a standard phone takes either class (so a person who
   * picked Save data, or simply downloaded the small file from the website, can install it on any phone that is not
   * a 2 GB one); a lite phone takes only its own. Add-ons fit every phone and are not decided here.
   */
  fun acceptClasses(hardware: BundleClass): Set<BundleClass> =
    if (hardware == BundleClass.STANDARD) setOf(BundleClass.STANDARD, BundleClass.LITE) else setOf(hardware)

  /**
   * The choice in force. A 2 GB phone is always [ModelChoice.SAVE_DATA]. Otherwise the person's stored answer, then what a
   * signed bundle already installed implies (a small pack from the website means Save data), then the default, [ModelChoice.STANDARD].
   */
  fun effective(hardware: BundleClass, stored: ModelChoice?, installed: BundleClass?): ModelChoice = when {
    hardware == BundleClass.LITE -> ModelChoice.SAVE_DATA
    stored != null -> stored
    installed == BundleClass.LITE -> ModelChoice.SAVE_DATA
    else -> ModelChoice.STANDARD
  }

  /** What an installed bundle class means as a choice (null for `any`, which a model bundle never is). */
  fun choiceOf(installed: BundleClass?): ModelChoice? = when (installed) {
    BundleClass.LITE -> ModelChoice.SAVE_DATA
    BundleClass.STANDARD -> ModelChoice.STANDARD
    else -> null
  }

  /**
   * Whether the Save data pack is on offer to someone who already holds [installed]. Once the standard pack is installed it is
   * not offered (the installer would keep the fuller model and refuse the lighter one as older); the upgrade runs the other way.
   */
  fun canChooseSaveData(hardware: BundleClass, installed: BundleClass?): Boolean = offered(hardware) && installed != BundleClass.STANDARD

  /** The Settings upgrade: Save data is installed on a phone that could hold the standard pack. */
  fun canUpgradeToStandard(hardware: BundleClass, installed: BundleClass?): Boolean = offered(hardware) && installed == BundleClass.LITE

  /** An install that takes a phone from the lite stack to the standard one: the files are read again with the better model. */
  fun upgradesStack(before: BundleClass?, after: BundleClass): Boolean = before == BundleClass.LITE && after == BundleClass.STANDARD
}
