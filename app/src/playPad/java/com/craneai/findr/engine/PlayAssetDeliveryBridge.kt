package com.craneai.findr.engine

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play AAB bridge (`-PtrawlPlayAssetDelivery=true`): connects [PlayAssetPackSource] to Google Play's
 * `AssetPackManager` IPC client. Play Store performs the on-demand download out of process, so Trawl keeps
 * zero `android.permission.INTERNET` permission. Tracks the currently resumed foreground [Activity] so Play's
 * cellular/large-pack confirmation dialog (`showConfirmationDialog`) is launched automatically when Play emits
 * `WAITING_FOR_WIFI` or `REQUIRES_USER_CONFIRMATION`. If installed outside Play, [PlayAssetPackSource] falls
 * back to [DownloadsFolderSource].
 */
object PlayAssetDeliveryBridge {
  private val lifecycleRegistered = AtomicBoolean(false)
  private val resumedActivity = AtomicReference<WeakReference<Activity>?>(null)

  fun createPackSource(context: Context, fallback: ModelPackSource): ModelPackSource {
    val app = context.applicationContext
    if (app is Application && lifecycleRegistered.compareAndSet(false, true)) {
      app.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityResumed(activity: Activity) {
            resumedActivity.set(WeakReference(activity))
          }

          override fun onActivityPaused(activity: Activity) {
            val current = resumedActivity.get()?.get()
            if (current === activity) resumedActivity.set(null)
          }

          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
          override fun onActivityStarted(activity: Activity) = Unit
          override fun onActivityStopped(activity: Activity) = Unit
          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
          override fun onActivityDestroyed(activity: Activity) {
            val current = resumedActivity.get()?.get()
            if (current === activity) resumedActivity.set(null)
          }
        },
      )
    }
    return PlayAssetPackSource(
      client = GooglePlayAssetDeliveryClient(
        context = app,
        resumedActivityProvider = { resumedActivity.get()?.get() },
      ),
      fallback = fallback,
    )
  }
}

internal class GooglePlayAssetDeliveryClient(
  private val context: Context,
  private val managerProvider: () -> AssetPackManager = { AssetPackManagerFactory.getInstance(context) },
  private val resumedActivityProvider: () -> Activity? = { null },
) : PlayAssetDeliveryClient {
  private val manager: AssetPackManager by lazy(managerProvider)

  override fun isPlayStoreInstall(): Boolean = runCatching {
    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == PLAY_STORE_PACKAGE
  }.getOrDefault(false)

  override fun installedAssetsDir(packName: String): File? = runCatching {
    manager.getPackLocation(packName)?.assetsPath()?.let(::File)?.takeIf { it.isDirectory }
  }.getOrNull()

  override suspend fun fetch(packName: String, onUpdate: (PlayAssetProgress) -> Unit): PlayAssetFetchResult {
    installedAssetsDir(packName)?.let { return PlayAssetFetchResult.Ready(it) }
    return suspendCancellableCoroutine { cont ->
      val done = AtomicBoolean(false)
      val confirmationPrompted = AtomicBoolean(false)
      lateinit var listener: AssetPackStateUpdateListener

      fun finish(result: PlayAssetFetchResult) {
        if (done.compareAndSet(false, true)) {
          runCatching { manager.unregisterListener(listener) }
          if (cont.isActive) cont.resume(result)
        }
      }

      fun promptOrFinish(status: PlayAssetStatus) {
        val fallbackResult = if (status == PlayAssetStatus.WAITING_FOR_WIFI) {
          PlayAssetFetchResult.WaitingForWifi(packName)
        } else {
          PlayAssetFetchResult.NeedsConfirmation(packName)
        }
        val act = resumedActivityProvider()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (act == null || !confirmationPrompted.compareAndSet(false, true)) {
          finish(fallbackResult)
          return
        }
        runCatching {
          act.runOnUiThread {
            if (done.get() || act.isFinishing || act.isDestroyed) {
              finish(fallbackResult)
              return@runOnUiThread
            }
            runCatching {
              manager.showConfirmationDialog(act)
                .addOnSuccessListener { resultCode ->
                  // RESULT_OK lets Play resume DOWNLOADING -> COMPLETED on the active listener.
                  if (resultCode == Activity.RESULT_CANCELED) {
                    finish(PlayAssetFetchResult.Canceled(packName))
                  }
                }
                .addOnFailureListener { finish(fallbackResult) }
            }.onFailure { finish(fallbackResult) }
          }
        }.onFailure { finish(fallbackResult) }
      }

      fun handleState(state: AssetPackState) {
        if (state.name() != packName) return
        val status = mapStatus(state.status())
        onUpdate(
          PlayAssetProgress(
            status = status,
            bytesDownloaded = state.bytesDownloaded(),
            totalBytes = state.totalBytesToDownload(),
            errorCode = state.errorCode(),
          ),
        )
        when (status) {
          PlayAssetStatus.COMPLETED -> {
            val dir = installedAssetsDir(packName)
            if (dir != null) finish(PlayAssetFetchResult.Ready(dir))
            else finish(PlayAssetFetchResult.Failed("completed without assets path"))
          }
          PlayAssetStatus.WAITING_FOR_WIFI,
          PlayAssetStatus.REQUIRES_USER_CONFIRMATION,
          -> promptOrFinish(status)
          PlayAssetStatus.CANCELED -> finish(PlayAssetFetchResult.Canceled(packName))
          PlayAssetStatus.FAILED -> finish(mapError(state.errorCode(), "status FAILED (${state.errorCode()})"))
          PlayAssetStatus.UNKNOWN,
          PlayAssetStatus.PENDING,
          PlayAssetStatus.DOWNLOADING,
          PlayAssetStatus.TRANSFERRING,
          PlayAssetStatus.NOT_INSTALLED,
          -> Unit
        }
      }

      listener = AssetPackStateUpdateListener { state: AssetPackState -> handleState(state) }

      manager.registerListener(listener)
      cont.invokeOnCancellation {
        done.set(true)
        runCatching { manager.unregisterListener(listener) }
        runCatching { manager.cancel(listOf(packName)) }
      }

      manager.fetch(listOf(packName))
        .addOnSuccessListener { states ->
          val state = states.packStates()[packName]
          if (state == null) {
            finish(PlayAssetFetchResult.Unavailable("Play Asset Delivery returned no state for $packName"))
          } else {
            // Route every initial state (COMPLETED with null dir, WAITING_FOR_WIFI, REQUIRES_USER_CONFIRMATION,
            // FAILED, CANCELED) through handleState so fetch() never hangs when Play does not re-emit to listener.
            handleState(state)
          }
        }
        .addOnFailureListener { error ->
          val code = (error as? AssetPackException)?.errorCode ?: AssetPackErrorCode.INTERNAL_ERROR
          finish(mapError(code, error.message ?: error.javaClass.simpleName))
        }
    }
  }

  override fun removePack(packName: String) {
    runCatching { manager.removePack(packName) }
  }

  private fun mapStatus(status: Int): PlayAssetStatus = when (status) {
    AssetPackStatus.PENDING -> PlayAssetStatus.PENDING
    AssetPackStatus.DOWNLOADING -> PlayAssetStatus.DOWNLOADING
    AssetPackStatus.TRANSFERRING -> PlayAssetStatus.TRANSFERRING
    AssetPackStatus.COMPLETED -> PlayAssetStatus.COMPLETED
    AssetPackStatus.FAILED -> PlayAssetStatus.FAILED
    AssetPackStatus.CANCELED -> PlayAssetStatus.CANCELED
    AssetPackStatus.WAITING_FOR_WIFI -> PlayAssetStatus.WAITING_FOR_WIFI
    AssetPackStatus.NOT_INSTALLED -> PlayAssetStatus.NOT_INSTALLED
    AssetPackStatus.REQUIRES_USER_CONFIRMATION -> PlayAssetStatus.REQUIRES_USER_CONFIRMATION
    else -> PlayAssetStatus.UNKNOWN
  }

  private fun mapError(errorCode: Int, detail: String): PlayAssetFetchResult = when (errorCode) {
    AssetPackErrorCode.APP_UNAVAILABLE,
    AssetPackErrorCode.PACK_UNAVAILABLE,
    AssetPackErrorCode.APP_NOT_OWNED,
    AssetPackErrorCode.API_NOT_AVAILABLE,
    -> PlayAssetFetchResult.Unavailable("Play Asset Delivery unavailable ($errorCode): $detail")
    else -> PlayAssetFetchResult.Failed(detail, errorCode)
  }

  private companion object {
    const val PLAY_STORE_PACKAGE = "com.android.vending"
  }
}
