package com.meatsack.motivator.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.meatsack.motivator.presentation.theme.MeatsackTheme
import com.meatsack.motivator.presentation.theme.MeatsackTypography

@Composable
fun InsultScreen(
    insultText: String,
    statsText: String,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
) {
    MeatsackTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Branding watermark
                Text(
                    text = "meatsackMotivator",
                    style = MeatsackTypography.brandText,
                )

                // Insult message
                Text(
                    text = insultText,
                    style = MeatsackTypography.insultText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Stats context line
                Text(
                    text = statsText,
                    style = MeatsackTypography.statsText,
                    textAlign = TextAlign.Center,
                )

                // Vote buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Thumbs down
                    Button(
                        onClick = onThumbsDown,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2C2C2E)
                        ),
                    ) {
                        Text("👎", style = MeatsackTypography.insultText.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
                    }

                    // Thumbs up
                    Button(
                        onClick = onThumbsUp,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2C2C2E)
                        ),
                    ) {
                        Text("👍", style = MeatsackTypography.insultText.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
                    }
                }
            }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun InsultScreenPreview() {
    InsultScreen(
        insultText = "GET UP, you osteopenic jello mold.",
        statsText = "438 steps. It's 2pm. Pathetic.",
        onThumbsUp = {},
        onThumbsDown = {},
    )
}
