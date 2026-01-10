package com.github.movesense.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.R

@Composable
fun PremiumBanner(
    onUpgradeClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isPromo: Boolean = false
) {
    var isVisible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Card(
            onClick = onUpgradeClick,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = if (isPromo) {
                                // Fire Gradient (Red/Orange/Gold)
                                listOf(
                                    Color(0xFFFF512F),
                                    Color(0xFFDD2476),
                                    Color(0xFFFFD700)
                                )
                            } else {
                                // Deep Space Gradient (Dark Blue/Purple)
                                listOf(
                                    Color(0xFF0F2027),
                                    Color(0xFF203A43),
                                    Color(0xFF2C5364)
                                )
                            }
                        )
                    )
            ) {
                // Decorative background circle
                Box(
                    modifier = Modifier
                        .offset(x = (-20).dp, y = (-20).dp)
                        .size(100.dp)
                        .background(
                            color = if (isPromo) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Icon
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isPromo) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = if (isPromo) Color.White else Color(0xFFFFD700),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Text
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isPromo) stringResource(R.string.banner_promo_title) else stringResource(R.string.banner_standard_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isPromo) stringResource(R.string.banner_promo_desc) else stringResource(R.string.banner_standard_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                    }

                    // Arrow Icon to indicate clickability
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Close Button (Positioned absolutely to avoid layout shifts, but with padding to prevent overlap)
                IconButton(
                    onClick = {
                        isVisible = false
                        onDismiss()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp) // Reduced padding to push it further to the corner
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.2f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CompactPremiumBanner(
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onUpgradeClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFFD700).copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = stringResource(R.string.upgrade_for_faster_analysis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}