package com.craneai.findr.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** "50,000" and "50k" in a query are the digits a spreadsheet cell holds; nothing else in a query is touched. */
class QueryNumbersTest {
  @Test fun groupedAndThousandsNumbersBecomeDigits() {
    assertEquals("pay 50000 and 50000", QueryNumbers.normalise("pay 50,000 and 50k"))
    assertEquals("1500 shillings", QueryNumbers.normalise("1.5k shillings"))
    assertEquals("1234567 total", QueryNumbers.normalise("1,234,567 total"))
    assertEquals("350000 Mukasa", QueryNumbers.normalise("350k Mukasa"))
    assertEquals("350000 Mukasa", QueryNumbers.normalise("350K Mukasa"))
  }

  @Test fun otherQueriesAreUntouched() {
    assertEquals("a, b 12, 34", QueryNumbers.normalise("a, b 12, 34"))
    assertEquals("5km run", QueryNumbers.normalise("5km run"))
    assertEquals("room 1k5", QueryNumbers.normalise("room 1k5"))
    assertEquals("photos of the market", QueryNumbers.normalise("photos of the market"))
    assertEquals("IMG_2026_receipt", QueryNumbers.normalise("IMG_2026_receipt"))
  }

  @Test fun theTermsOfAQueryCarryTheDigits() {
    assertEquals(listOf("350000", "mukasa"), Fusion.terms("350k Mukasa"))
    assertEquals(listOf("50000", "ntambi"), Fusion.terms("50,000 Ntambi"))
  }
}
