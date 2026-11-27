package com.craneai.findr

import android.app.Activity
import android.app.job.JobParameters
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.impl.background.systemjob.SystemJobService
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.engine.ChangeWorker
import com.craneai.findr.engine.IndexWorker
import com.craneai.findr.quick.QuickSearchActivity
import com.craneai.findr.ui.fake.FakeSearchEngine
import com.craneai.findr.ui.keeplearning.KeepLearningActivity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

class SlowStartupApp : FindrApp() {
  val constructionStarted = CountDownLatch(1)
  val releaseConstruction = CountDownLatch(1)
  var constructionThread: Thread? = null
  var constructedEngine: SearchEngine? = null

  override fun createSearchEngine(): SearchEngine {
    constructionThread = Thread.currentThread()
    constructionStarted.countDown()
    check(releaseConstruction.await(30, TimeUnit.SECONDS))
    return FakeSearchEngine(this).also { constructedEngine = it }
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SlowStartupApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class StartupNonBlockingTest {
  private val app get() = RuntimeEnvironment.getApplication() as SlowStartupApp
  private val scope = CoroutineScope(Dispatchers.Unconfined)

  private fun assertConstructionBlocked() {
    assertTrue(app.constructionStarted.await(10, TimeUnit.SECONDS))
    assertEquals(1L, app.releaseConstruction.count)
    assertFalse(app.isReady)
    assertNotSame(Looper.getMainLooper().thread, app.constructionThread)
  }

  private fun finishStartup() {
    app.releaseConstruction.countDown()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!app.isReady && System.nanoTime() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    assertTrue("startup finishes after the slow engine is released", app.isReady)
  }

  @After fun releaseStartup() {
    finishStartup()
    scope.cancel()
  }

  @Suppress("DEPRECATION")
  @Test fun onCreateReturnsAndMainCallbacksRunWhileEngineConstructionIsBlocked() {
    assertConstructionBlocked()
    app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    var mainCallbackRan = false
    Handler(Looper.getMainLooper()).post { mainCallbackRan = true }
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(mainCallbackRan)
    assertFalse(app.isReady)
    val waiting = scope.async { app.awaitEngine() }
    assertFalse(waiting.isCompleted)
    finishStartup()
    assertSame(app.engine, waiting.getCompleted())
    assertSame(app.constructedEngine, app.engine)
  }

  @Test fun bothWorkersSuspendWithoutBlockingOrConstructingAnotherEngine() {
    assertConstructionBlocked()
    val index = TestListenableWorkerBuilder<IndexWorker>(app).build()
    val change = TestListenableWorkerBuilder<ChangeWorker>(app).build()
    val foreground = scope.async { index.getForegroundInfo() }
    assertTrue(foreground.isCompleted)
    val indexing = scope.async { index.doWork() }
    val changing = scope.async { change.doWork() }
    assertFalse(indexing.isCompleted)
    assertFalse(changing.isCompleted)
    finishStartup()
    assertEquals(ListenableWorker.Result.success(), indexing.getCompleted())
    assertEquals(ListenableWorker.Result.success(), changing.getCompleted())
    assertSame(app.constructedEngine, app.engine)
  }

  @Test fun cancellingAWaitingWorkerDoesNotCancelSharedStartup() {
    assertConstructionBlocked()
    val worker = TestListenableWorkerBuilder<IndexWorker>(app).build()
    val cancelled = scope.async { worker.doWork() }
    cancelled.cancel()
    val survivor = scope.async { app.awaitEngine() }
    assertFalse(survivor.isCompleted)
    finishStartup()
    assertTrue(cancelled.isCancelled)
    assertSame(app.engine, survivor.getCompleted())
  }

  @Test fun systemJobServiceReturnsWithAnActiveJobWhileTheEngineIsBlocked() {
    assertConstructionBlocked()
    val queued = ArrayList<Runnable>()
    val executor = Executor { queued.add(it) }
    WorkManagerTestInitHelper.initializeTestWorkManager(
      app,
      Configuration.Builder().setExecutor(executor).setTaskExecutor(executor).build(),
    )
    val controller = Robolectric.buildService(SystemJobService::class.java).create()
    val params = ReflectionHelpers.newInstance(JobParameters::class.java)
    ReflectionHelpers.setField(params, "extras", PersistableBundle().apply {
      putString("EXTRA_WORK_SPEC_ID", UUID.randomUUID().toString())
      putInt("EXTRA_WORK_SPEC_GENERATION", 0)
    })
    assertTrue(controller.get().onStartJob(params))
    assertFalse(app.isReady)
    assertTrue(queued.isNotEmpty())
    controller.destroy()
    WorkManagerTestInitHelper.closeWorkDatabase()
  }

  @Test fun activitiesCanStartAndStopBeforeTheEngineIsReady() {
    assertConstructionBlocked()
    val main = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
    assertFalse(app.isReady)
    main.pause().stop().destroy()
    val quick = Robolectric.buildActivity(QuickSearchActivity::class.java).create().start().resume()
    assertFalse(app.isReady)
    quick.pause().stop().destroy()
    val learning = Robolectric.buildActivity(KeepLearningActivity::class.java).create().start().resume()
    assertFalse(app.isReady)
    learning.pause().stop().destroy()
    finishStartup()
    assertEquals(0, ReflectionHelpers.getField<Int>(app.privacy, "started"))
  }

  @Test fun startupReplaysAlreadyVisibleActivitiesAndLoadsPrivacyBeforePublishing() {
    assertConstructionBlocked()
    app.getSharedPreferences("findr_privacy", Activity.MODE_PRIVATE).edit().putBoolean("lock_enabled", true).commit()
    val controller = Robolectric.buildActivity(Activity::class.java).create().start()
    finishStartup()
    assertTrue(app.privacy.locked.value)
    assertEquals(1, ReflectionHelpers.getField<Int>(app.privacy, "started"))
    controller.stop().destroy()
    assertEquals(0, ReflectionHelpers.getField<Int>(app.privacy, "started"))
  }
}
