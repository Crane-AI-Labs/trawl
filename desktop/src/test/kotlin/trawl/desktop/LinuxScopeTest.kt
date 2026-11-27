package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linux: the weight and quota for the app's own scope, and the refusals that keep it from touching anything that is not Trawl's. */
class LinuxScopeTest {
  private val calls = ArrayList<Triple<String, Long, Long>>()
  private fun scope(quotaPct: Int = 50, weight: Int = 20, ok: Boolean = true) =
    LinuxScope("app-trawl-1.scope", { u, w, q -> calls += Triple(u, w, q); ok }, quotaPct, weight)

  @Test fun gentleGetsAWeightAndAQuotaBalancedOnlyTheWeight() {
    val s = scope()
    s.enter(LearningSpeed.GENTLE)
    s.enter(LearningSpeed.BALANCED)
    assertEquals(Triple("app-trawl-1.scope", 20L, 500_000L), calls[0], "50% of one core is 500 000 microseconds in each second")
    assertEquals(Triple("app-trawl-1.scope", 20L, LinuxScope.NONE), calls[1], "no quota for Balanced")
  }

  @Test fun noQuotaConfiguredMeansWeightOnly() {
    scope(quotaPct = 0).enter(LearningSpeed.GENTLE)
    assertEquals(LinuxScope.NONE, calls.single().third)
  }

  @Test fun leavingPutsBothBackToTheDefault() {
    scope().leave()
    assertEquals(Triple("app-trawl-1.scope", LinuxScope.NONE, LinuxScope.NONE), calls.single())
  }

  @Test fun aRefusedCallIsReportedAsNothingDone() {
    val s = scope(ok = false)
    assertNull(s.enter(LearningSpeed.GENTLE)); assertNull(s.leave())
  }

  @Test fun theWeightStaysWithinSystemdsRange() {
    scope(weight = 0).enter(LearningSpeed.BALANCED); scope(weight = 99_999).enter(LearningSpeed.BALANCED)
    assertEquals(1L, calls[0].second); assertEquals(10_000L, calls[1].second)
  }

  private val own = { _: String -> true }

  @Test fun onlyAScopeOfTheUsersOwnServiceManagerIsEverChanged() {
    val user = "0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-gnome-trawl-4242.scope\n"
    assertEquals("app-gnome-trawl-4242.scope", LinuxScope.unitOf(user, 1000, own))
    assertNull(LinuxScope.unitOf("0::/user.slice/user-1000.slice/session-3.scope\n", 1000, own), "a login session belongs to the system manager")
    assertNull(LinuxScope.unitOf("0::/user.slice/user-1001.slice/user@1001.service/app.slice/x.scope\n", 1000, own), "another user's manager")
    assertNull(LinuxScope.unitOf("0::/user.slice/user-1000.slice/user@1000.service\n", 1000, own), "the manager itself")
    assertNull(LinuxScope.unitOf("0::/user.slice/user-1000.slice/user@1000.service/app.slice\n", 1000, own), "a slice is not a scope or a service")
    assertNull(LinuxScope.unitOf("", 1000, own))
  }

  @Test fun aScopeThatHoldsOtherProgramsIsLeftAlone() {
    val user = "0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-org.gnome.Terminal.slice/vte-spawn-1.scope\n"
    assertNull(LinuxScope.unitOf(user, 1000) { path -> LinuxScope.holdsOnlyUs(path, procs = { listOf(100L, 101L, 5000L) }, self = 100L, below = setOf(101L)) },
      "the terminal's shell (5000) is in the same scope")
    assertEquals("vte-spawn-1.scope", LinuxScope.unitOf(user, 1000) { path -> LinuxScope.holdsOnlyUs(path, procs = { listOf(100L, 101L) }, self = 100L, below = setOf(101L)) })
  }

  @Test fun holdsOnlyUsNeedsTheListAndSomethingInIt() {
    assertFalse(LinuxScope.holdsOnlyUs("/x", procs = { null }, self = 1L, below = emptySet()))
    assertFalse(LinuxScope.holdsOnlyUs("/x", procs = { emptyList() }, self = 1L, below = emptySet()))
    assertTrue(LinuxScope.holdsOnlyUs("/x", procs = { listOf(1L) }, self = 1L, below = emptySet()))
  }
}
