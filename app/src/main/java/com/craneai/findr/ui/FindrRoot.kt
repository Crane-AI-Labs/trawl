package com.craneai.findr.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.core.PrivateLook
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.PreviewBlur
import com.craneai.findr.ui.common.blurHintOnce
import com.craneai.findr.ui.app.AppViewModel
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.onboarding.OnboardingScreen
import com.craneai.findr.ui.search.SearchScreen
import com.craneai.findr.ui.search.SearchViewModel
import com.craneai.findr.ui.settings.LicencesScreen
import com.craneai.findr.ui.settings.SettingsScreen
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.Motion

/** The share of a predictive back in which the page leaving fades out (of Motion.Standard), before the page under it fades in. */
private const val FADE_THROUGH_MS = 96

private object Routes {
  const val ONBOARDING = "onboarding"
  const val HOME = "home"
  const val SETTINGS = "settings"
  const val LICENCES = "licences"
}

/** Opens the "All files access" switch for this app, or the generic list if this phone has no per-app page. */
private fun Context.openAllFilesSettings() {
  try {
    startActivity(AppViewModel.allFilesIntent(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: ActivityNotFoundException) {
    try {
      startActivity(AppViewModel.allFilesFallbackIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
      // Nothing more we can do; the banner stays and tells the user what to switch on.
    }
  }
}

@Composable
fun FindrRoot(appViewModel: AppViewModel, factory: ViewModelProvider.Factory) {
  val navController = rememberNavController()
  val context = LocalContext.current
  val reduce = LocalReduceMotion.current
  val onboardingDone by appViewModel.onboardingDone.collectAsStateWithLifecycle()
  val access by appViewModel.access.collectAsStateWithLifecycle()
  // Decided once: finishing onboarding navigates explicitly, it must not restart the graph.
  val start = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }

  val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    appViewModel.refreshAccess()
  }

  val enter: () -> EnterTransition = {
    if (reduce) EnterTransition.None else fadeIn(tween(Motion.Standard)) + slideInHorizontally(tween(Motion.Standard)) { it / 14 }
  }
  val exit: () -> ExitTransition = {
    if (reduce) ExitTransition.None else fadeOut(tween(Motion.Quick))
  }
  val popEnter: () -> EnterTransition = {
    if (reduce) EnterTransition.None else fadeIn(tween(Motion.Standard))
  }
  val popExit: () -> ExitTransition = {
    if (reduce) ExitTransition.None else fadeOut(tween(Motion.Quick)) + slideOutHorizontally(tween(Motion.Quick)) { it / 14 }
  }

  // Predictive back (gesture navigation, on by default since we target Android 16): NavHost plays these while the finger moves,
  // seeking them with the gesture, then finishes them when it lets go (or reverses them if the back is cancelled). Navigation's
  // own default here is scaleOut(0.7f): the page shrinks toward the centre like a screenshot and fades (the design brief:
  // "the screen pulls away from the edges into the center"). Sunlight's instead: the page slides toward the side the finger
  // moves to and fades, and the page under it fades in from a little behind, the same motion as the back button's, only longer.
  // Fade through rather than cross-dissolve: the page leaving is gone by 40% of the way, the page under it fades in after, so the
  // two never show through each other while the finger drags. Both also move a little, toward the finger.
  val predictivePopEnter: (Int) -> EnterTransition = { edge ->
    if (reduce) EnterTransition.None
    else fadeIn(tween(Motion.Standard - FADE_THROUGH_MS, delayMillis = FADE_THROUGH_MS)) +
      slideInHorizontally(tween(Motion.Standard)) { w -> if (edge == BackEventCompat.EDGE_RIGHT) w / 14 else -w / 14 }
  }
  val predictivePopExit: (Int) -> ExitTransition = { edge ->
    if (reduce) ExitTransition.None
    else fadeOut(tween(FADE_THROUGH_MS)) + slideOutHorizontally(tween(Motion.Standard)) { w -> if (edge == BackEventCompat.EDGE_RIGHT) -w / 4 else w / 4 }
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    NavHost(
      navController = navController,
      startDestination = start,
      enterTransition = { enter() },
      exitTransition = { exit() },
      popEnterTransition = { popEnter() },
      popExitTransition = { popExit() },
      predictivePopEnterTransition = { edge -> predictivePopEnter(edge) },
      predictivePopExitTransition = { edge -> predictivePopExit(edge) },
    ) {
      composable(Routes.ONBOARDING) {
        val addOnsViewModel: AddOnsViewModel = viewModel(factory = factory)
        val indexViewModel: IndexViewModel = viewModel(factory = factory)
        val deliveryViewModel: DeliveryViewModel = viewModel(factory = factory)
        OnboardingScreen(
          addOns = addOnsViewModel,
          delivery = deliveryViewModel,
          index = indexViewModel,
          access = access,
          onOpenFilesSettings = { context.openAllFilesSettings() },
          onFinish = {
            appViewModel.completeOnboarding()
            navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
          },
        )
      }
      composable(Routes.HOME) {
        val searchViewModel: SearchViewModel = viewModel(factory = factory)
        val indexViewModel: IndexViewModel = viewModel(factory = factory)
        val deliveryViewModel: DeliveryViewModel = viewModel(factory = factory)
        val settings by indexViewModel.settings.collectAsStateWithLifecycle()
        // Off unless the person turned it on in Settings; it only ever blurs a thumbnail (see PrivateLook).
        // It also needs the add-on's pack switched on (privateScoring): no pack, no scores, no blur.
        val blurOn = settings.blurPrivatePreviews && settings.privateScoring
        val blur = remember(blurOn) { PreviewBlur(enabled = blurOn, threshold = PrivateLook.MAIN_APP_THRESHOLD, onFirstBlur = blurHintOnce(context)) }
        CompositionLocalProvider(LocalPreviewBlur provides blur) {
          SearchScreen(
            searchViewModel = searchViewModel,
            indexViewModel = indexViewModel,
            deliveryViewModel = deliveryViewModel,
            access = access,
            onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            onAllowAccess = { context.openAllFilesSettings() },
            // Asked after the first search, when it means something (onboarding v2), not during setup.
            onRequestNotifications = {
              if (AppViewModel.notificationsNeedRuntimePrompt()) notificationLauncher.launch(AppViewModel.NOTIFICATION_PERMISSION)
            },
          )
        }
      }
      composable(Routes.SETTINGS) {
        val indexViewModel: IndexViewModel = viewModel(factory = factory)
        val addOnsViewModel: AddOnsViewModel = viewModel(factory = factory)
        val deliveryViewModel: DeliveryViewModel = viewModel(factory = factory)
        val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
        val palette by appViewModel.palette.collectAsStateWithLifecycle()
        SettingsScreen(
          indexViewModel = indexViewModel,
          addOnsViewModel = addOnsViewModel,
          deliveryViewModel = deliveryViewModel,
          themeMode = themeMode,
          onThemeMode = appViewModel::setThemeMode,
          palette = palette,
          onPalette = appViewModel::setPalette,
          onOpenLicences = { navController.navigate(Routes.LICENCES) { launchSingleTop = true } },
          onBack = { navController.popBackStack() },
        )
      }
      composable(Routes.LICENCES) {
        LicencesScreen(onBack = { navController.popBackStack() })
      }
    }
  }
}
