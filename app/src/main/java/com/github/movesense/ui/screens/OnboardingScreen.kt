package com.github.movesense.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.github.movesense.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 6 images as uploaded
    val images = listOf(
        "file:///android_asset/onboarding/onboarding_1.png",
        "file:///android_asset/onboarding/onboarding_2.png",
        "file:///android_asset/onboarding/onboarding_3.png",
        "file:///android_asset/onboarding/onboarding_4.png",
        "file:///android_asset/onboarding/onboarding_5.png",
        "file:///android_asset/onboarding/onboarding_6.png"
    )
    
    val pagerState = rememberPagerState(pageCount = { images.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A1A), // Dark Gray
                        Color(0xFF000000)  // Black
                    )
                )
            )
    ) {
        // Full screen pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp) // Space for bottom controls
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Phone Mockup Frame
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.85f)
                        .aspectRatio(9f / 19.5f) // Typical modern phone aspect ratio
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black)
                        .border(
                            width = 8.dp,
                            color = Color(0xFF2C2C2C), // Dark bezel color
                            shape = RoundedCornerShape(32.dp)
                        )
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(images[page])
                                .crossfade(true)
                                .build()
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop, // Crop to fill the phone screen
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp) // Inner bezel padding
                            .clip(RoundedCornerShape(24.dp)) // Inner screen radius
                    )
                    
                    // Optional: Top Notch or Dynamic Island simulation
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .width(80.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    )
                }
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Indicators
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                repeat(images.size) { iteration ->
                    val isSelected = pagerState.currentPage == iteration
                    val width = if (isSelected) 32.dp else 8.dp
                    val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f)
                    
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            // Main Action Button
            Button(
                onClick = {
                    if (pagerState.currentPage < images.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onFinish()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (pagerState.currentPage == images.size - 1) 
                        stringResource(R.string.onboarding_finish) 
                    else 
                        stringResource(R.string.next),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
