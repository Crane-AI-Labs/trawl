package com.craneai.findr.ui.delivery

import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.ModelChoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who is asked which model pack they want: a phone that can hold either and holds neither yet. A 2 GB phone is never asked. */
class ModelChoiceVisibilityTest {
  @Test fun askedOnAPhoneThatCanHoldEitherAndHasNoSignedPackYet() {
    assertTrue(showsChoice(DeliveryState(choiceOffered = true, standardMb = 670, saveDataMb = 304)))
  }

  @Test fun notAskedOnATwoGbPhone() {
    assertFalse(showsChoice(DeliveryState(choiceOffered = false)))
  }

  @Test fun notAskedOnceAPackIsInstalledTheSettingsRowTakesOver() {
    assertFalse(showsChoice(DeliveryState(choiceOffered = true, installedChoice = ModelChoice.SAVE_DATA)))
    assertFalse(showsChoice(DeliveryState(choiceOffered = true, installedChoice = ModelChoice.STANDARD)))
  }
}
