package com.craneai.findr.diag

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.craneai.findr.BuildConfig
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackState
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.engine.Embedder
import java.io.File

/** The Android half of [Diag]: where reports live, what this phone is, and what the system recorded about earlier exits. */
class AndroidDiagPlatform(private val app: Application) : DiagPlatform {
  override val reportDir: File get() = File(app.noBackupFilesDir, Diag.DIR)
  override val pid: Int get() = android.os.Process.myPid()

  override fun readExits(): List<ExitRecord> = ExitHistory.read(app)

  override fun cleanupShared() = ReportSender.cleanup(app)

  override fun deviceFacts(): DeviceFacts {
    val context: Context = app
    val mem = ActivityManager.MemoryInfo()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    am.getMemoryInfo(mem)
    val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
    return DeviceFacts(
      appVersion = BuildConfig.VERSION_NAME,
      appBuild = BuildConfig.VERSION_CODE,
      buildType = BuildConfig.BUILD_TYPE,
      packageName = context.packageName,
      manufacturer = Build.MANUFACTURER,
      model = Build.MODEL,
      androidRelease = Build.VERSION.RELEASE,
      sdk = Build.VERSION.SDK_INT,
      abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
      ramMb = mem.totalMem / MB,
      lowRamDevice = am.isLowRamDevice,
      freeStorageMb = (stat?.availableBytes ?: 0L) / MB,
      totalStorageMb = (stat?.totalBytes ?: 0L) / MB,
    )
  }

  override fun modelFacts(engine: SearchEngine?): ModelFacts {
    val dir = File(app.filesDir, "models")
    val model = Embedder.MODEL_NAMES.map { File(dir, it) }.firstOrNull { it.isFile }
    val embedding = if (model == null) "not installed" else "${model.name}, ${model.length() / MB} MB"
    val states = engine?.packs?.states?.value.orEmpty()
    val packs = PackCatalog.all.filter { it.available }.map { def ->
      val state = when (val st = states[def.id]) {
        is PackState.Installed -> "installed, ${st.bytes / MB} MB"
        is PackState.Installing -> "installing"
        is PackState.Failed -> "failed (${st.problem.name.lowercase()})"
        else -> "not installed"
      }
      "${def.id}: $state"
    }
    return ModelFacts(embedding, packs)
  }

  private companion object { const val MB = 1_000_000L }
}
