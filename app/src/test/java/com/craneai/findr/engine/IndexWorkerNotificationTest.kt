package com.craneai.findr.engine

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.MainActivity
import com.craneai.findr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Verifies the ongoing indexing notification opens/foregrounds [MainActivity] when tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexWorkerNotificationTest {

  @Test fun indexingNotificationHasMainActivityContentIntent() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val notification = IndexWorker.buildNotification(context, R.string.notif_reading_photo)

    val pi = notification.contentIntent
    assertNotNull("Indexing notification must attach a content PendingIntent", pi)

    val shadow = Shadows.shadowOf(pi)
    assertTrue("PendingIntent must be immutable", (shadow.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
    assertTrue("PendingIntent must update current", (shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT) != 0)

    val intent = shadow.savedIntent
    assertEquals(MainActivity::class.java.name, intent.component?.className)
    assertEquals(Intent.ACTION_MAIN, intent.action)
    assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
    assertTrue((intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    assertTrue((intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)
    assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

    // Privacy & ongoing semantics stay intact.
    assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
  }
}
