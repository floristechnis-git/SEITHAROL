package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareArtistModal(
    artistName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modalSheetState = rememberModalBottomSheetState()

    fun triggerShare() {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Listen to $artistName on Seitharol! Pure offline-first music streaming.")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share $artistName via")
        context.startActivity(shareIntent)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalSheetState,
        containerColor = SeitharolBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SeitharolHandleBar)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("share_artist_modal"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Share $artistName",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = SeitharolDark
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Row of share targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Telegram
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { triggerShare() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = Color(0xFF29B6F6)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Telegram",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Telegram", fontSize = 12.sp, fontFamily = OutfitFontFamily, color = SeitharolDark)
                }

                // WhatsApp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { triggerShare() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = Color(0xFF25D366)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "WhatsApp",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("WhatsApp", fontSize = 12.sp, fontFamily = OutfitFontFamily, color = SeitharolDark)
                }

                // Copy Link
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { triggerShare() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = SeitharolDark
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Link",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Link", fontSize = 12.sp, fontFamily = OutfitFontFamily, color = SeitharolDark)
                }

                // More
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { triggerShare() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = Color(0xFFE0E0E0)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More",
                                tint = SeitharolDark,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("More", fontSize = 12.sp, fontFamily = OutfitFontFamily, color = SeitharolDark)
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
