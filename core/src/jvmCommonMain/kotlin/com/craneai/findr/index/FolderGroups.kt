package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings

/**
 * The places people think in ("my camera", "WhatsApp voice notes", "Downloads"), as data. Each group is a list of
 * [PathRule]s over the folder a file lives in (MediaStore's RELATIVE_PATH) and its kind; the first group whose rule
 * matches owns the file. Nothing here knows about Android, MediaStore or the UI, so the later Play build can feed the
 * same groups from MediaStore alone and the folder picker, and a new group (for example one the ethics review asks
 * for) is one more entry in [FolderGroups.ALL], with a label in the UI.
 *
 * What is NEVER decided here: private places (hidden folders, `.nomedia`, other apps' private trees) stay out in
 * [Rules.folderSkipReason] whatever a group's switch says, and a folder the person excluded beats every switch.
 */
data class PathRule(
  /** Folder paths from the volume root, lower case, no slashes at the ends: "dcim/camera". Matches that folder and below. */
  val prefixes: List<String> = emptyList(),
  /** Any folder on the way whose name equals one of these (lower case). */
  val segments: List<String> = emptyList(),
  /** Any folder on the way whose name contains one of these (lower case). */
  val segmentContains: List<String> = emptyList(),
  /** When set, the file must also be one of these kinds. A rule with no path part and only kinds matches by kind alone. */
  val kinds: Set<FileKind>? = null,
) {
  fun matches(parts: List<String>, normalized: String, kind: FileKind): Boolean {
    if (kinds != null && kind !in kinds) return false
    val hasPathPart = prefixes.isNotEmpty() || segments.isNotEmpty() || segmentContains.isNotEmpty()
    if (!hasPathPart) return kinds != null
    return prefixes.any { normalized == it || normalized.startsWith("$it/") } ||
      parts.any { it in segments } ||
      parts.any { part -> segmentContains.any { part.contains(it) } }
  }
}

data class FolderGroup(
  /** Stable key stored in settings; never rename one once shipped. */
  val id: String,
  val rules: List<PathRule>,
  /** What a person who never touched the switch gets. Groups that were never indexed before (Status) start off. */
  val defaultOn: Boolean = true,
)

object FolderGroups {
  const val CAMERA = "camera"
  const val SCREENSHOTS = "screenshots"
  const val WHATSAPP_IMAGES = "whatsapp_images"
  const val WHATSAPP_VIDEO = "whatsapp_video"
  const val WHATSAPP_DOCUMENTS = "whatsapp_documents"
  const val WHATSAPP_VOICE = "whatsapp_voice"
  const val WHATSAPP_STATUS = "whatsapp_status"
  const val DOWNLOADS = "downloads"
  const val DOCUMENTS = "documents"
  const val MUSIC = "music"
  const val OTHER = "other"

  private val IMAGES = setOf(FileKind.IMAGE, FileKind.SCREENSHOT)
  private val DOCS = setOf(FileKind.DOCUMENT, FileKind.PDF)

  /**
   * In order: the first match wins, so the specific groups come before the general ones (a PDF in
   * `Download/WhatsApp Documents` is WhatsApp's, not Downloads'). [OTHER] has no rules: it takes whatever is left.
   */
  val ALL: List<FolderGroup> = listOf(
    // Status lives in a hidden folder; this group is the one switch that lifts that rule (see Rules.folderSkipReason).
    FolderGroup(WHATSAPP_STATUS, listOf(PathRule(segments = listOf(".statuses"))), defaultOn = false),
    FolderGroup(WHATSAPP_IMAGES, listOf(PathRule(segmentContains = listOf("whatsapp"), kinds = IMAGES))),
    FolderGroup(WHATSAPP_VIDEO, listOf(PathRule(segmentContains = listOf("whatsapp"), kinds = setOf(FileKind.VIDEO)))),
    FolderGroup(WHATSAPP_VOICE, listOf(PathRule(segmentContains = listOf("whatsapp"), kinds = setOf(FileKind.AUDIO)))),
    // Documents, and anything else WhatsApp saved (the odd archive or contact card).
    FolderGroup(WHATSAPP_DOCUMENTS, listOf(PathRule(segmentContains = listOf("whatsapp")))),
    FolderGroup(SCREENSHOTS, listOf(PathRule(kinds = setOf(FileKind.SCREENSHOT)))),
    FolderGroup(CAMERA, listOf(PathRule(prefixes = listOf("dcim")))),
    FolderGroup(DOWNLOADS, listOf(PathRule(prefixes = listOf("download", "downloads")))),
    FolderGroup(DOCUMENTS, listOf(PathRule(prefixes = listOf("documents")), PathRule(kinds = DOCS))),
    FolderGroup(
      MUSIC,
      listOf(
        PathRule(prefixes = listOf("music", "recordings", "voice recorder", "voicerecorder", "sounds", "audiobooks", "podcasts")),
        PathRule(segmentContains = listOf("recorder")),
        PathRule(kinds = setOf(FileKind.AUDIO)),
      ),
    ),
    FolderGroup(OTHER, emptyList()),
  )

  private val BY_ID = ALL.associateBy { it.id }

  fun byId(id: String): FolderGroup? = BY_ID[id]

  /** The group a file belongs to. [relativePath] is MediaStore's RELATIVE_PATH ("DCIM/Camera/"). */
  fun groupOf(relativePath: String, kind: FileKind): FolderGroup {
    val parts = pathParts(relativePath)
    val normalized = parts.joinToString("/")
    return ALL.firstOrNull { g -> g.rules.any { it.matches(parts, normalized, kind) } } ?: BY_ID.getValue(OTHER)
  }

  fun pathParts(relativePath: String): List<String> = relativePath.split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
}

/**
 * The person's choices over [FolderGroups]. A group with no entry in [groupOverrides] follows its default, so a group
 * added in a later version needs no migration, and an install from before this feature (no entries at all) searches
 * exactly what it did before. [includedFolders] are folders added by hand; they are searched even when their group is off.
 * (Excluded folders live beside this in the settings and beat everything.)
 */
data class FolderScope(
  val groupOverrides: Map<String, Boolean> = emptyMap(),
  val includedFolders: Set<String> = emptySet(),
) {
  fun isOn(group: FolderGroup): Boolean = groupOverrides[group.id] ?: group.defaultOn
  fun isOn(groupId: String): Boolean = FolderGroups.byId(groupId)?.let(::isOn) ?: true

  fun isIncluded(relativePath: String): Boolean {
    if (includedFolders.isEmpty()) return false
    val normalized = FolderGroups.pathParts(relativePath).joinToString("/")
    return includedFolders.any { f ->
      val e = f.trim('/').lowercase()
      e.isNotEmpty() && (normalized == e || normalized.startsWith("$e/"))
    }
  }

  companion object {
    /** Every group on, Status included: what a count of "what is there" uses. */
    val EVERYTHING = FolderScope(FolderGroups.ALL.associate { it.id to true })
  }
}

/** The folder choices in [FindrSettings], as the rules want them. */
fun FindrSettings.folderScope() = FolderScope(groupOverrides, includedFolders)

/** One group switched on or off. The choice is stored even when it equals today's default, so a later change of default never overrides it. */
fun FindrSettings.withGroup(id: String, on: Boolean) = copy(groupOverrides = groupOverrides + (id to on))

/** "+ Add a folder". A folder cannot be both added and excluded: the latest choice wins. */
fun FindrSettings.withIncludedFolder(folder: String): FindrSettings {
  val f = folder.trim('/')
  if (f.isEmpty()) return this
  return copy(includedFolders = includedFolders + f, excludedFolders = excludedFolders - f)
}

fun FindrSettings.withoutIncludedFolder(folder: String) = copy(includedFolders = includedFolders - folder)

/** "Exclude a folder". */
fun FindrSettings.withExcludedFolder(folder: String): FindrSettings {
  val f = folder.trim('/')
  if (f.isEmpty()) return this
  return copy(excludedFolders = excludedFolders + f, includedFolders = includedFolders - f)
}

fun FindrSettings.withoutExcludedFolder(folder: String) = copy(excludedFolders = excludedFolders - folder)
