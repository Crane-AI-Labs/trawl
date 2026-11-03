package com.craneai.findr.core

/**
 * Scores a picture for the blur now, because it is on screen, ahead of the background privacy pass (PrivateLook.Look.CHECKING;
 * internal/research/blur-until-scored.md). The UI asks from a coroutine tied to the thumbnail, so a thumbnail that leaves the
 * screen drops its request.
 */
interface PrivacyCheck {
  /** The scorer is installed and switched on: an unscored picture can be scored, so it is shown blurred until it is. */
  fun available(): Boolean

  /**
   * The picture's score, 0..1, or a negative number when it can't be read. Suspends until it is scored (on demand, or by the
   * background pass if that gets there first); cancelling the call drops the request. Null: it could not be scored now (the
   * model failed to run, or the scorer went away), so the picture stays as it is.
   */
  suspend fun score(file: IndexedFile): Float?

  object None : PrivacyCheck {
    override fun available() = false
    override suspend fun score(file: IndexedFile): Float? = null
  }
}
