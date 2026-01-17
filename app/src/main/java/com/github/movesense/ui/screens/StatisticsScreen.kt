package com.github.movesense.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.GameHeader
import com.github.movesense.Provider
import com.github.movesense.R
import com.github.movesense.ui.UserProfile
import com.github.movesense.FullReport
import com.github.movesense.MoveClass
import com.github.movesense.pgnHash
import kotlinx.coroutines.delay

// Modern color palette
private val WinColor = Color(0xFF22C55E)
private val WinColorDark = Color(0xFF16A34A)
private val LossColor = Color(0xFFEF4444)
private val LossColorDark = Color(0xFFDC2626)
private val DrawColor = Color(0xFF94A3B8)
private val DrawColorDark = Color(0xFF64748B)

private val WinGradient = Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF4ADE80)))
private val LossGradient = Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFF87171)))
private val DrawGradient = Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1)))

private val RatingGradient = Brush.sweepGradient(
    listOf(
        Color(0xFFFF5F6D).copy(alpha = 0.8f),
        Color(0xFFFFC371),
        Color(0xFF22C55E)
    )
)

private const val MIN_GAMES_FOR_STATS = 10

enum class TimeControlFilter {
    ALL, BULLET, BLITZ, RAPID, CLASSICAL
}

enum class SourceFilter {
    ALL, LICHESS, CHESS_COM
}

data class TimeControlStats(
    val totalGames: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val winsAsWhite: Int = 0,
    val lossesAsWhite: Int = 0,
    val drawsAsWhite: Int = 0,
    val winsAsBlack: Int = 0,
    val lossesAsBlack: Int = 0,
    val drawsAsBlack: Int = 0
) {
    val winRate: Float get() = if (totalGames > 0) wins.toFloat() / totalGames else 0f
    val lossRate: Float get() = if (totalGames > 0) losses.toFloat() / totalGames else 0f
    val drawRate: Float get() = if (totalGames > 0) draws.toFloat() / totalGames else 0f
    
    val whiteGames: Int get() = winsAsWhite + lossesAsWhite + drawsAsWhite
    val blackGames: Int get() = winsAsBlack + lossesAsBlack + drawsAsBlack
    
    val winRateAsWhite: Float get() = if (whiteGames > 0) winsAsWhite.toFloat() / whiteGames else 0f
    val winRateAsBlack: Float get() = if (blackGames > 0) winsAsBlack.toFloat() / blackGames else 0f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    profile: UserProfile,
    games: List<GameHeader>,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToGame: (GameHeader) -> Unit,
    onLoadGames: (() -> Unit)? = null,
    analyzedGames: Map<String, FullReport>
) {
    var selectedTimeFilter by remember { mutableStateOf(TimeControlFilter.ALL) }
    var selectedSourceFilter by remember { mutableStateOf(SourceFilter.ALL) }
    var isVisible by remember { mutableStateOf(false) }
    
    // Filter out test games
    val userGames = remember(games) { games.filter { !it.isTest } }
    val hasEnoughGames = userGames.size >= MIN_GAMES_FOR_STATS
    
    // Calculate statistics
    val currentStats = remember(userGames, profile, selectedTimeFilter, selectedSourceFilter) {
        calculateStats(userGames, profile, selectedTimeFilter, selectedSourceFilter)
    }

    val openingStats = remember(userGames, profile, selectedTimeFilter, selectedSourceFilter) {
        calculateOpeningStats(userGames, profile, selectedTimeFilter, selectedSourceFilter)
    }

    // Pre-calculate counts for badges/chips
    val bulletStats = remember(userGames, profile, selectedSourceFilter) { calculateStats(userGames, profile, TimeControlFilter.BULLET, selectedSourceFilter) }
    val blitzStats = remember(userGames, profile, selectedSourceFilter) { calculateStats(userGames, profile, TimeControlFilter.BLITZ, selectedSourceFilter) }
    val rapidStats = remember(userGames, profile, selectedSourceFilter) { calculateStats(userGames, profile, TimeControlFilter.RAPID, selectedSourceFilter) }
    val classicalStats = remember(userGames, profile, selectedSourceFilter) { calculateStats(userGames, profile, TimeControlFilter.CLASSICAL, selectedSourceFilter) }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.statistics),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        when {
            profile.lichessUsername.isBlank() && profile.chessUsername.isBlank() -> {
                NoAccountsConfiguredContent(
                    onNavigateToProfile = onNavigateToProfile,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            !hasEnoughGames -> {
                // Need more games state
                NeedMoreGamesContent(
                    currentGames = userGames.size,
                    onLoadGames = onLoadGames ?: onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
            else -> {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.95f, animationSpec = tween(300))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Source Filter Selector
                        SourceFilterSelector(
                            selectedFilter = selectedSourceFilter,
                            onFilterSelected = { selectedSourceFilter = it },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        // Time Control Selector
                        TimeControlSelector(
                            selectedFilter = selectedTimeFilter,
                            onFilterSelected = { selectedTimeFilter = it },
                            bulletCount = bulletStats.totalGames,
                            blitzCount = blitzStats.totalGames,
                            rapidCount = rapidStats.totalGames,
                            classicalCount = classicalStats.totalGames,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        // Main stats card with donut chart
                        MainStatsCard(
                            stats = currentStats,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        
                        // Quick stats row
                        QuickStatsRow(
                            stats = currentStats,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        
                        // Stats by color
                        ColorStatsSection(
                            stats = currentStats,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Opening Stats
                        OpeningStatsSection(
                            stats = openingStats,
                            profile = profile,
                            onNavigateToGame = onNavigateToGame,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Analyzed Stats
                        val analyzedGameReports = remember(userGames, analyzedGames, selectedTimeFilter, selectedSourceFilter) {
                             userGames.mapNotNull { game ->
                                 // Check if we have an analysis report
                                 val pgn = game.pgn ?: return@mapNotNull null
                                 val report = analyzedGames[pgnHash(pgn)] ?: return@mapNotNull null
                                 
                                 // Apply filters (Time Control & Source)
                                 // We reuse the logic: if the GAME passes the filter, we include the REPORT.
                                 // Wait, `userGames` passed to this remember block is ALREADY filtered by nothing but `!isTest`.
                                 // `calculateStats` applies `selectedTimeFilter` and `selectedSourceFilter`.
                                 // So we must check filters manually here.
                                 
                                 if (selectedSourceFilter != SourceFilter.ALL) {
                                     val isLichess = game.site == Provider.LICHESS
                                     val isChessCom = game.site == Provider.CHESSCOM
                                     if (selectedSourceFilter == SourceFilter.LICHESS && !isLichess) return@mapNotNull null
                                     if (selectedSourceFilter == SourceFilter.CHESS_COM && !isChessCom) return@mapNotNull null
                                 }
                                 
                                 if (selectedTimeFilter != TimeControlFilter.ALL) {
                                     val tc = extractTimeControl(pgn)
                                     val gameControl = mapToTimeControlFilter(tc)
                                     if (gameControl != selectedTimeFilter) return@mapNotNull null
                                 }
                                 
                                 report
                             }
                        }

                        val analyzedData = remember(analyzedGameReports, profile) {
                            val totalAnalyzed = analyzedGameReports.size
                            var totalAccuracy = 0.0
                            val moveDist = mutableMapOf<MoveClass, Int>()
                            var totalMoves = 0
                            
                            // Rating Calculation variables
                            var totalEstimatedRating = 0.0
                            var ratingCount = 0

                            val usernames = listOf(
                                profile.lichessUsername.lowercase(),
                                profile.chessUsername.lowercase()
                            ).filter { it.isNotBlank() }

                            analyzedGameReports.forEach { report ->
                                 val white = report.header.white?.lowercase() ?: ""
                                 val isWhite = usernames.any { it == white }
                                 
                                 // Weighted accuracy
                                 val acc = if (isWhite) report.accuracy.whiteMovesAcc.weighted else report.accuracy.blackMovesAcc.weighted
                                 totalAccuracy += acc
                                 
                                 // --- Estimated Rating Logic ---
                                 // 1. Performance based on result
                                 val opponentElo = if (isWhite) report.header.blackElo else report.header.whiteElo
                                 val myElo = if (isWhite) report.header.whiteElo else report.header.blackElo
                                 
                                 val baseRating = opponentElo ?: 1500 // Default if unknown
                                 
                                 val result = report.header.result
                                 val scoreOffset = when(result) {
                                     "1-0" -> if (isWhite) 400 else -400
                                     "0-1" -> if (!isWhite) 400 else -400
                                     "1/2-1/2" -> 0
                                     else -> 0
                                 }
                                 
                                 val performanceRating = baseRating + scoreOffset
                                 
                                 // 2. Accuracy to Elo mapping
                                 // Simple mapping: 50% -> 800, 100% -> 3000
                                 val accuracyRating = when {
                                     acc >= 100.0 -> 3000
                                     acc >= 95.0 -> 2700 + (acc - 95) * 60 // 300 per 5%
                                     acc >= 90.0 -> 2300 + (acc - 90) * 80 // 400 per 5%
                                     acc >= 80.0 -> 1800 + (acc - 80) * 50 // 500 per 10%
                                     acc >= 70.0 -> 1400 + (acc - 70) * 40
                                     acc >= 60.0 -> 1000 + (acc - 60) * 40
                                     else -> 800 + (acc - 50).coerceAtLeast(0.0) * 20
                                 }.toInt()
                                 
                                 // 3. User's Existing Rating (from PGN)
                                 // If available, this helps anchor the estimate.
                                 
                                 val gameEstRating = if (myElo != null && myElo > 0) {
                                     // 35% Performance, 35% Accuracy, 30% Existing Rating
                                     (performanceRating * 0.35 + accuracyRating * 0.35 + myElo.toDouble() * 0.30)
                                 } else {
                                     // 50% Performance, 50% Accuracy
                                     (performanceRating * 0.5 + accuracyRating * 0.5)
                                 }
                                 
                                 totalEstimatedRating += gameEstRating
                                 ratingCount++

                                 // Move classification
                                 report.moves.forEach { move ->
                                     val isWhiteMove = move.beforeFen.contains(" w ")
                                     if ((isWhite && isWhiteMove) || (!isWhite && !isWhiteMove)) {
                                         val cls = move.classification
                                         moveDist[cls] = (moveDist[cls] ?: 0) + 1
                                         totalMoves++
                                     }
                                 }
                            }
                            
                            val avgRating = if (ratingCount > 0) totalEstimatedRating / ratingCount else 1500.0
                            
                            AnalyzedData(
                                totalAnalyzed = totalAnalyzed,
                                averageAccuracy = if (totalAnalyzed > 0) (totalAccuracy / totalAnalyzed).toFloat() else 0f,
                                estimatedRating = avgRating.toInt(),
                                moveDistribution = moveDist,
                                totalMoves = totalMoves
                            )
                        }

                        AnalyzedStatsSection(
                            analyzedData = analyzedData,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedMoreGamesContent(
    currentGames: Int,
    onLoadGames: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Animated icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = stringResource(R.string.need_more_games_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = stringResource(R.string.need_more_games_desc, currentGames),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                // Progress indicator
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { currentGames.toFloat() / MIN_GAMES_FOR_STATS },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$currentGames / $MIN_GAMES_FOR_STATS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Button(
                    onClick = onLoadGames,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.load_games_button),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFilterSelector(
    selectedFilter: SourceFilter,
    onFilterSelected: (SourceFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SourceFilterOption(
            selected = selectedFilter == SourceFilter.ALL,
            label = "All",
            onClick = { onFilterSelected(SourceFilter.ALL) },
            modifier = Modifier.weight(1f)
        )
        SourceFilterOption(
            selected = selectedFilter == SourceFilter.LICHESS,
            label = "Lichess",
            onClick = { onFilterSelected(SourceFilter.LICHESS) },
            modifier = Modifier.weight(1f)
        )
        SourceFilterOption(
            selected = selectedFilter == SourceFilter.CHESS_COM,
            label = "Chess.com",
            onClick = { onFilterSelected(SourceFilter.CHESS_COM) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SourceFilterOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, label = "bg"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "text"
    )
    val shadowElevation by animateFloatAsState(
        if (selected) 2f else 0f, label = "shadow"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .shadow(shadowElevation.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeControlSelector(
    selectedFilter: TimeControlFilter,
    onFilterSelected: (TimeControlFilter) -> Unit,
    bulletCount: Int,
    blitzCount: Int,
    rapidCount: Int,
    classicalCount: Int,
    modifier: Modifier = Modifier
) {
    // Horizontal scrollable row for modern look
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(modifier = Modifier.width(16.dp)) // Start padding
        
        TimeControlChip(
            selected = selectedFilter == TimeControlFilter.ALL,
            label = stringResource(R.string.all_time_controls),
            icon = null,
            count = bulletCount + blitzCount + rapidCount + classicalCount,
            onClick = { onFilterSelected(TimeControlFilter.ALL) }
        )
        
        TimeControlChip(
            selected = selectedFilter == TimeControlFilter.BULLET,
            label = stringResource(R.string.bullet),
            icon = "🔫",
            count = bulletCount,
            onClick = { onFilterSelected(TimeControlFilter.BULLET) }
        )
        
        TimeControlChip(
            selected = selectedFilter == TimeControlFilter.BLITZ,
            label = stringResource(R.string.blitz),
            icon = "⚡",
            count = blitzCount,
            onClick = { onFilterSelected(TimeControlFilter.BLITZ) }
        )
        
        TimeControlChip(
            selected = selectedFilter == TimeControlFilter.RAPID,
            label = stringResource(R.string.rapid),
            icon = "⏱️",
            count = rapidCount,
            onClick = { onFilterSelected(TimeControlFilter.RAPID) }
        )
        
        TimeControlChip(
            selected = selectedFilter == TimeControlFilter.CLASSICAL,
            label = stringResource(R.string.classical),
            icon = "♟️",
            count = classicalCount,
            onClick = { onFilterSelected(TimeControlFilter.CLASSICAL) }
        )
        
        Spacer(modifier = Modifier.width(16.dp)) // End padding
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeControlChip(
    selected: Boolean,
    label: String,
    icon: String?,
    count: Int,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Text(text = icon, modifier = Modifier.padding(end = 4.dp))
                }
                Text(
                    text = if (count > 0) "$label ($count)" else label,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderWidth = 0.dp,
            borderColor = Color.Transparent
        ),
        elevation = FilterChipDefaults.filterChipElevation(
            elevation = 2.dp,
            pressedElevation = 0.dp
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun MainStatsCard(
    stats: TimeControlStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.overall_stats),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${stats.totalGames} ${stringResource(R.string.games).lowercase()}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            // Donut chart with center content
            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedDonutChart(
                    winRate = stats.winRate,
                    lossRate = stats.lossRate,
                    drawRate = stats.drawRate,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Center content
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = WinColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(stats.winRate * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = WinColor
                    )
                    Text(
                        text = stringResource(R.string.win_rate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatLegendItem(
                    color = WinColor,
                    gradient = WinGradient,
                    label = stringResource(R.string.wins),
                    count = stats.wins,
                    percentage = stats.winRate
                )
                StatLegendItem(
                    color = DrawColor,
                    gradient = DrawGradient,
                    label = stringResource(R.string.draws),
                    count = stats.draws,
                    percentage = stats.drawRate
                )
                StatLegendItem(
                    color = LossColor,
                    gradient = LossGradient,
                    label = stringResource(R.string.losses),
                    count = stats.losses,
                    percentage = stats.lossRate
                )
            }
        }
    }
}

@Composable
private fun AnimatedDonutChart(
    winRate: Float,
    lossRate: Float,
    drawRate: Float,
    modifier: Modifier = Modifier
) {
    val animationProgress = remember { Animatable(0f) }
    
    LaunchedEffect(winRate, lossRate, drawRate) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            1f,
            animationSpec = tween(1000)
        )
    }
    
    Canvas(modifier = modifier.padding(16.dp)) {
        val strokeWidth = 28.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)
        
        val total = winRate + drawRate + lossRate
        if (total <= 0f) return@Canvas
        
        val progress = animationProgress.value
        var startAngle = -90f
        
        // Win arc
        val winSweep = (winRate / total * 360f) * progress
        if (winSweep > 0) {
            drawArc(
                brush = WinGradient,
                startAngle = startAngle,
                sweepAngle = winSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        startAngle += winSweep + 3f
        
        // Draw arc
        val drawSweep = (drawRate / total * 360f) * progress
        if (drawSweep > 0) {
            drawArc(
                brush = DrawGradient,
                startAngle = startAngle,
                sweepAngle = drawSweep.coerceAtLeast(0f),
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        startAngle += drawSweep + 3f
        
        // Loss arc
        val lossSweep = (lossRate / total * 360f) * progress
        if (lossSweep > 0) {
            drawArc(
                brush = LossGradient,
                startAngle = startAngle,
                sweepAngle = lossSweep.coerceAtLeast(0f),
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun StatLegendItem(
    color: Color,
    gradient: Brush,
    label: String,
    count: Int,
    percentage: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(gradient)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = "${(percentage * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickStatsRow(
    stats: TimeControlStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickStatCard(
            icon = "⚪",
            label = stringResource(R.string.as_white),
            value = "${(stats.winRateAsWhite * 100).toInt()}%",
            subValue = "${stats.whiteGames} games",
            color = Color(0xFFF5F5F5),
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            icon = "⚫",
            label = stringResource(R.string.as_black),
            value = "${(stats.winRateAsBlack * 100).toInt()}%",
            subValue = "${stats.blackGames} games",
            color = Color(0xFF2A2A2A),
            textColor = Color.White,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickStatCard(
    icon: String,
    label: String,
    value: String,
    subValue: String,
    color: Color,
    textColor: Color = Color(0xFF1F1D17),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = icon, fontSize = 24.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = WinColor
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ColorStatsSection(
    stats: TimeControlStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.stats_by_color),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            // White stats
            ColorStatsBar(
                label = stringResource(R.string.as_white),
                icon = "⚪",
                wins = stats.winsAsWhite,
                draws = stats.drawsAsWhite,
                losses = stats.lossesAsWhite,
                winRate = stats.winRateAsWhite
            )
            
            // Black stats
            ColorStatsBar(
                label = stringResource(R.string.as_black),
                icon = "⚫",
                wins = stats.winsAsBlack,
                draws = stats.drawsAsBlack,
                losses = stats.lossesAsBlack,
                winRate = stats.winRateAsBlack
            )
        }
    }
}

@Composable
private fun ColorStatsBar(
    label: String,
    icon: String,
    wins: Int,
    draws: Int,
    losses: Int,
    winRate: Float
) {
    val total = wins + draws + losses
    val animatedWinWidth by animateFloatAsState(
        targetValue = if (total > 0) wins.toFloat() / total else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "winWidth"
    )
    val animatedDrawWidth by animateFloatAsState(
        targetValue = if (total > 0) draws.toFloat() / total else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "drawWidth"
    )
    val animatedLossWidth by animateFloatAsState(
        targetValue = if (total > 0) losses.toFloat() / total else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "lossWidth"
    )
    
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = icon, fontSize = 20.sp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "${(winRate * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = WinColor
            )
        }
        
        // Stacked bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (animatedWinWidth > 0) {
                Box(
                    modifier = Modifier
                        .weight(animatedWinWidth.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(WinGradient)
                )
            }
            if (animatedDrawWidth > 0) {
                Box(
                    modifier = Modifier
                        .weight(animatedDrawWidth.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(DrawGradient)
                )
            }
            if (animatedLossWidth > 0) {
                Box(
                    modifier = Modifier
                        .weight(animatedLossWidth.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(LossGradient)
                )
            }
        }
        
        // Numbers row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatMini(label = "W", value = wins, color = WinColor)
                StatMini(label = "D", value = draws, color = DrawColor)
                StatMini(label = "L", value = losses, color = LossColor)
            }
            Text(
                text = "$total games",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatMini(
    label: String,
    value: Int,
    color: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = "$value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// Opening Stats Components

data class OpeningStats(
    val name: String,
    val totalGames: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val games: List<GameHeader>
) {
    val winRate: Float get() = if (totalGames > 0) wins.toFloat() / totalGames else 0f
    val lossRate: Float get() = if (totalGames > 0) losses.toFloat() / totalGames else 0f
    val drawRate: Float get() = if (totalGames > 0) draws.toFloat() / totalGames else 0f
}

private fun calculateOpeningStats(
    games: List<GameHeader>,
    profile: UserProfile,
    filterControl: TimeControlFilter?,
    filterSource: SourceFilter
): List<OpeningStats> {
    val usernames = listOf(
        profile.lichessUsername.lowercase(),
        profile.chessUsername.lowercase()
    ).filter { it.isNotBlank() }

    if (usernames.isEmpty()) return emptyList()

    // 1. Filter games first (reusing logic from calculateStats, but we need the games list)
    val filteredGames = games.filter { game ->
        val pgn = game.pgn ?: return@filter false
        
        // Source
        if (filterSource != SourceFilter.ALL) {
             val isLichess = game.site == Provider.LICHESS
             val isChessCom = game.site == Provider.CHESSCOM
             if (filterSource == SourceFilter.LICHESS && !isLichess) return@filter false
             if (filterSource == SourceFilter.CHESS_COM && !isChessCom) return@filter false
        }
        
        // Time Control
        if (filterControl != null && filterControl != TimeControlFilter.ALL) {
            val tc = extractTimeControl(pgn)
            val gameControl = mapToTimeControlFilter(tc)
            if (gameControl != filterControl) return@filter false
        }

        // User played
        val white = game.white?.lowercase() ?: ""
        val black = game.black?.lowercase() ?: ""
        (usernames.any { it == white } || usernames.any { it == black })
    }

    // 2. Group by Opening
    val grouped = filteredGames.groupBy { game ->
        // "Borg Defense: Borg Gambit" -> "Borg Defense"
        val fullOpening = game.opening ?: game.eco ?: "Unknown"
        // Split by colon, take first part, trim
        fullOpening.split(":").first().trim()
    }

    // 3. Map to OpeningStats
    return grouped.map { (name, openingGames) ->
        var wins = 0
        var losses = 0
        var draws = 0

        openingGames.forEach { game ->
            val white = game.white?.lowercase() ?: ""
            val isPlayerWhite = usernames.any { it == white }
            
            when (game.result) {
                "1-0" -> if (isPlayerWhite) wins++ else losses++
                "0-1" -> if (!isPlayerWhite) wins++ else losses++
                "1/2-1/2" -> draws++
            }
        }

        OpeningStats(name, openingGames.size, wins, losses, draws, openingGames)
    }.filter { it.totalGames >= 3 }
     .sortedByDescending { it.totalGames }
}

@Composable
private fun OpeningStatsSection(
    stats: List<OpeningStats>,
    profile: UserProfile,
    onNavigateToGame: (GameHeader) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 Text(
                    text = "♟️", // Chess pawn icon
                    fontSize = 20.sp
                )
                Text(
                    text = stringResource(R.string.opening_stats), // Ensure string resource exists or use literal for now
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (stats.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_games_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                stats.forEach { openingStat ->
                    OpeningGroupCard(
                        stat = openingStat,
                        profile = profile,
                        onNavigateToGame = onNavigateToGame
                    )
                }
            }
        }
    }
}

@Composable
private fun OpeningGroupCard(
    stat: OpeningStats,
    profile: UserProfile,
    onNavigateToGame: (GameHeader) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        // Collapsed Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${stat.totalGames} games",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Mini Bar Chart
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mini bar
                Row(
                    modifier = Modifier
                        .width(60.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (stat.wins > 0) {
                        Box(Modifier.weight(stat.wins.toFloat()).fillMaxHeight().background(WinColor))
                    }
                    if (stat.draws > 0) {
                         Box(Modifier.weight(stat.draws.toFloat()).fillMaxHeight().background(DrawColor))
                    }
                    if (stat.losses > 0) {
                         Box(Modifier.weight(stat.losses.toFloat()).fillMaxHeight().background(LossColor))
                    }
                }
                
                // Win Rate Text
                Text(
                    text = "${(stat.winRate * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = WinColor
                )

                Icon(
                    if (expanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Expanded Content
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stat.games.forEach { game ->
                    MiniGameCard(
                        game = game, 
                        profile = profile, 
                        onClick = { onNavigateToGame(game) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniGameCard(
    game: GameHeader,
    profile: UserProfile,
    onClick: () -> Unit
) {
    val usernames = listOf(
        profile.lichessUsername.lowercase(),
        profile.chessUsername.lowercase()
    ).filter { it.isNotBlank() }
    
    val white = game.white ?: "?"
    val black = game.black ?: "?"
    val isPlayerWhite = usernames.any { it == white.lowercase() }
    
    val userWon = (isPlayerWhite && game.result == "1-0") || (!isPlayerWhite && game.result == "0-1")
    val userLost = (isPlayerWhite && game.result == "0-1") || (!isPlayerWhite && game.result == "1-0")
    
    val date = game.date ?: ""
    val openingDetail = game.opening ?: game.eco ?: ""

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Icon + Players + Variation
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Color Indicator Icon
                Box(
                    modifier = Modifier.size(12.dp).clip(CircleShape)
                        .background(if (isPlayerWhite) Color.White else Color(0xFF333333))
                        .then(if (isPlayerWhite) Modifier.border(1.dp, Color.Gray, CircleShape) else Modifier)
                )

                Column {
                    Text(
                        text = openingDetail,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$white vs $black",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Right: Result Badge
            val resultColor = when {
                userWon -> WinColor
                userLost -> LossColor
                else -> DrawColor
            }
            val resultText = when {
                userWon -> "WIN"
                userLost -> "LOSS"
                else -> "DRAW"
            }
            
            Surface(
                color = resultColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = resultText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = resultColor
                )
            }
        }
    }
}

// Helper functions
private fun calculateStats(
    games: List<GameHeader>,
    profile: UserProfile,
    filterControl: TimeControlFilter?,
    filterSource: SourceFilter
): TimeControlStats {
    val usernames = listOf(
        profile.lichessUsername.lowercase(),
        profile.chessUsername.lowercase()
    ).filter { it.isNotBlank() }
    
    // If no usernames configured, we can't calculate personal stats
    if (usernames.isEmpty()) {
        return TimeControlStats(totalGames = games.size) // Return empty stats but with total count (though UI might block this)
    }
    
    var wins = 0
    var losses = 0
    var draws = 0
    var winsAsWhite = 0
    var lossesAsWhite = 0
    var drawsAsWhite = 0
    var winsAsBlack = 0
    var lossesAsBlack = 0
    var drawsAsBlack = 0
    
    games.forEach { game ->
        val pgn = game.pgn ?: return@forEach

        // Check source filter
        if (filterSource != SourceFilter.ALL) {
             val isLichess = game.site == Provider.LICHESS
             val isChessCom = game.site == Provider.CHESSCOM
             
             if (filterSource == SourceFilter.LICHESS && !isLichess) return@forEach
             if (filterSource == SourceFilter.CHESS_COM && !isChessCom) return@forEach
        }
        
        // Check time control filter
        if (filterControl != null && filterControl != TimeControlFilter.ALL) {
            val tc = extractTimeControl(pgn)
            val gameControl = mapToTimeControlFilter(tc)
            if (gameControl != filterControl) return@forEach
        }
        
        val white = game.white?.lowercase() ?: ""
        val black = game.black?.lowercase() ?: ""
        val result = game.result ?: ""
        
        val isPlayerWhite = usernames.any { it == white }
        val isPlayerBlack = usernames.any { it == black }
        
        if (!isPlayerWhite && !isPlayerBlack) return@forEach
        
        when (result) {
            "1-0" -> {
                if (isPlayerWhite) {
                    wins++
                    winsAsWhite++
                } else {
                    losses++
                    lossesAsBlack++
                }
            }
            "0-1" -> {
                if (isPlayerBlack) {
                    wins++
                    winsAsBlack++
                } else {
                    losses++
                    lossesAsWhite++
                }
            }
            "1/2-1/2" -> {
                draws++
                if (isPlayerWhite) {
                    drawsAsWhite++
                } else {
                    drawsAsBlack++
                }
            }
        }
    }
    
    return TimeControlStats(
        totalGames = wins + losses + draws,
        wins = wins,
        losses = losses,
        draws = draws,
        winsAsWhite = winsAsWhite,
        lossesAsWhite = lossesAsWhite,
        drawsAsWhite = drawsAsWhite,
        winsAsBlack = winsAsBlack,
        lossesAsBlack = lossesAsBlack,
        drawsAsBlack = drawsAsBlack
    )
}

private fun extractTimeControl(pgn: String): Int? {
    val match = Regex("""\[TimeControl\s+"([^"]+)"\]""").find(pgn)
    val tc = match?.groupValues?.getOrNull(1) ?: return null
    return tc.substringBefore('+', tc).toIntOrNull()
}

private fun mapToTimeControlFilter(timeControlSeconds: Int?): TimeControlFilter {
    if (timeControlSeconds == null) return TimeControlFilter.ALL
    return when {
        timeControlSeconds <= 60 -> TimeControlFilter.BULLET
        timeControlSeconds <= 300 -> TimeControlFilter.BLITZ
        timeControlSeconds <= 1500 -> TimeControlFilter.RAPID
        else -> TimeControlFilter.CLASSICAL
    }
}

@Composable
private fun NoAccountsConfiguredContent(
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Animated icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = stringResource(R.string.profile_setup_required),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = stringResource(R.string.profile_setup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Button(
                    onClick = onNavigateToProfile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.TrendingUp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.go_to_profile),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// --- Analyzed Statistics Section ---

data class AnalyzedData(
    val totalAnalyzed: Int,
    val averageAccuracy: Float,
    val estimatedRating: Int, // New field
    val moveDistribution: Map<MoveClass, Int>,
    val totalMoves: Int
)

@Composable
private fun AnalyzedStatsSection(
    analyzedData: AnalyzedData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🧠", fontSize = 20.sp)
                }
                
                Column {
                    Text(
                        text = stringResource(R.string.analysis_stats),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${analyzedData.totalAnalyzed} analyzed games", // TODO: resource
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (analyzedData.totalAnalyzed < 5) {
                NeedMoreGamesContent(current = analyzedData.totalAnalyzed, target = 5)
            } else {
                // Metrics Row (Gauge + Accuracy)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Estimated Rating Gauge
                    AnimatedRatingGauge(rating = analyzedData.estimatedRating)
                    
                    // Accuracy Ring
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedAccuracyRing(accuracy = analyzedData.averageAccuracy)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.average_accuracy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                
                // Move Classifications HEADER
                Text(
                    text = stringResource(R.string.move_quality),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                val sortedClasses = listOf(
                    MoveClass.SPLENDID,
                    MoveClass.PERFECT,
                    MoveClass.BEST,
                    MoveClass.EXCELLENT,
                    MoveClass.OKAY,
                    MoveClass.INACCURACY,
                    MoveClass.MISTAKE,
                    MoveClass.BLUNDER
                )
                
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    sortedClasses.forEach { cls ->
                        val count = analyzedData.moveDistribution[cls] ?: 0
                        if (count > 0 || cls == MoveClass.SPLENDID || cls == MoveClass.BLUNDER) {
                            MoveClassRow(cls, count, analyzedData.totalMoves)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedMoreGamesContent(current: Int, target: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = stringResource(R.string.need_more_analyzed_games),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        LinearProgressIndicator(
            progress = { current.toFloat() / target.toFloat() },
            modifier = Modifier
                .width(120.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "$current / $target",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MoveClassRow(cls: MoveClass, count: Int, totalMoves: Int) {
    val percentage = if (totalMoves > 0) count.toFloat() / totalMoves else 0f
    
    // Modern colors for move classes (lighter/brighter)
    val color = when (cls) {
        MoveClass.SPLENDID -> Color(0xFF14B8A6) // Teal
        MoveClass.PERFECT -> Color(0xFF3B82F6) // Blue
        MoveClass.BEST -> Color(0xFF22C55E) // Green
        MoveClass.EXCELLENT -> Color(0xFF84CC16) // Lime
        MoveClass.OKAY -> Color(0xFFA3E635) // Light Lime
        MoveClass.INACCURACY -> Color(0xFFEAB308) // Yellow
        MoveClass.MISTAKE -> Color(0xFFF97316) // Orange
        MoveClass.BLUNDER -> Color(0xFFEF4444) // Red
        else -> Color.Gray
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = cls.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Percentage ONLY
            val percentageValue = percentage * 100
            val formattedPercentage = when {
                percentageValue > 0 && percentageValue < 0.1 -> "<0.1%"
                else -> String.format("%.1f%%", percentageValue)
            }
            Text(
                text = formattedPercentage,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        // Progress Bar
        val animatedProgress = remember { Animatable(0f) }
        LaunchedEffect(percentage) {
            animatedProgress.animateTo(
                percentage,
                animationSpec = tween(1000)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress.value)
                    .background(color)
            )
        }
    }
}

@Composable
private fun AnimatedRatingGauge(
    rating: Int,
    modifier: Modifier = Modifier
) {
    val animatedRating = remember { Animatable(0f) }
    
    LaunchedEffect(rating) {
        animatedRating.animateTo(
            targetValue = rating.toFloat(),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }
    
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val subTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier.size(140.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 10.dp.toPx()
            val startAngle = 140f
            val sweepAngle = 260f
            
            // Background Arc
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // Progress Arc
            val maxRating = 3000f
            val progress = (animatedRating.value / maxRating).coerceIn(0f, 1f)
            
            drawArc(
                brush = RatingGradient,
                startAngle = startAngle,
                sweepAngle = sweepAngle * progress,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = animatedRating.value.toInt().toString(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "ELO", 
                style = MaterialTheme.typography.labelSmall,
                color = subTextColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AnimatedAccuracyRing(
    accuracy: Float,
    modifier: Modifier = Modifier
) {
    val animatedAcc = remember { Animatable(0f) }
    
    LaunchedEffect(accuracy) {
        animatedAcc.animateTo(
            targetValue = accuracy,
            animationSpec = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )
    }
    
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    
    Box(modifier = modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()
            val radius = size.minDimension / 2 - strokeWidth
            
            drawCircle(
                color = trackColor,
                radius = radius,
                style = Stroke(width = strokeWidth)
            )
            
            drawArc(
                brush = Brush.sweepGradient(listOf(Color(0xFF22C55E), Color(0xFF4ADE80), Color(0xFF22C55E))),
                startAngle = -90f,
                sweepAngle = (animatedAcc.value / 100f) * 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        
        Text(
            text = String.format("%.1f%%", animatedAcc.value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
