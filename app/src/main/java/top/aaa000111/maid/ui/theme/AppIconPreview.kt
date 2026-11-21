package top.aaa000111.maid.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.aaa000111.maid.R

@Composable
private fun AdaptiveIconSample(
    size: Dp,
    shape: Shape,
    label: String,
    monochromeBg: Color? = null,
    monochromeFg: Color? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .then(
                    if (monochromeBg != null) Modifier.background(monochromeBg) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Android Adaptive Icon renders a 108dp canvas cropped to the center 72dp (1.5x scale)
            if (monochromeFg != null) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(monochromeFg),
                    modifier = Modifier
                        .size(size)
                        .scale(1.5f),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier
                        .size(size)
                        .scale(1.5f),
                )
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = label,
                    modifier = Modifier
                        .size(size)
                        .scale(1.5f),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF45464F),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F5FA, widthDp = 420, heightDp = 360)
@Composable
fun MaidAppIconShowcasePreview() {
    MaidTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AdaptiveIconSample(
                    size = 112.dp,
                    shape = CircleShape,
                    label = "Circle 112dp",
                )
                AdaptiveIconSample(
                    size = 112.dp,
                    shape = RoundedCornerShape(26.dp),
                    label = "Squircle 112dp",
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AdaptiveIconSample(
                    size = 56.dp,
                    shape = CircleShape,
                    label = "Launcher 56dp",
                )
                AdaptiveIconSample(
                    size = 56.dp,
                    shape = RoundedCornerShape(14.dp),
                    label = "Rounded 56dp",
                )
                AdaptiveIconSample(
                    size = 56.dp,
                    shape = CircleShape,
                    label = "Themed Light",
                    monochromeBg = Color(0xFFDCE1FF),
                    monochromeFg = Color(0xFF283578),
                )
                AdaptiveIconSample(
                    size = 56.dp,
                    shape = CircleShape,
                    label = "Themed Dark",
                    monochromeBg = Color(0xFF1F274D),
                    monochromeFg = Color(0xFFDCE1FF),
                )
            }
        }
    }
}
