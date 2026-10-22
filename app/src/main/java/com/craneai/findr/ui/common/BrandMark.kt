package com.craneai.findr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.theme.SunlightLightTokens
import com.craneai.findr.ui.theme.SunType

/**
 * The app's mark: the launcher's drawable (res/drawable/ic_brand_mark.xml) in ink on a marker tile, as on the launcher. Swapping
 * that one file changes the mark everywhere.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
  // The logo keeps its own colours (as on the launcher), whatever the palette: with wallpaper colours the marker follows the
  // wallpaper, the mark does not.
  val brand = SunlightLightTokens
  Box(
    modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(brand.marker),
    contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(R.drawable.ic_brand_mark), contentDescription = null, tint = brand.onMarker, modifier = Modifier.size(size))
  }
}

/** The wordmark: the mark and the app's name (app_name) in Atkinson Bold. A heading for TalkBack. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, markSize: Dp = 32.dp) {
  Row(modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
    BrandMark(markSize)
    Spacer(Modifier.width(10.dp))
    Text(stringResource(R.string.app_name), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground)
  }
}
