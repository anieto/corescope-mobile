package org.nodescope.android.core.design

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A single composition path for phones, tablets and unfolded windows. Keeping the panes in
 * the same scaffold lets their saveable list/filter state survive hiding and showing a pane.
 * Selection belongs to the feature, not the window size, so folding never clears it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveListDetail(
    selectedId: String?,
    detailTitle: String,
    emptyTitle: String,
    emptyDescription: String,
    emptyIcon: ImageVector,
    onCloseDetail: () -> Unit,
    listPane: @Composable (showSelection: Boolean) -> Unit,
    detailPane: @Composable (String) -> Unit,
    detailBackLabel: String? = null,
) {
    val windowInfo = currentWindowAdaptiveInfo()
    val detailStates = rememberSaveableStateHolder()
    val fold = rememberFoldSplit()
    val wide = LocalWideLayout.current
    BoxWithConstraints(Modifier.fillMaxSize().then(fold.modifier)) {
        // An unfolded foldable splits at the crease: the list ends half the pane spacer before
        // it, so the gap between panes sits on the fold. Otherwise these are content widths
        // AFTER the app rail and insets, and large text needs more room.
        val listAtFold = fold.firstPaneWidth(maxWidth, 280.dp)?.minus(PaneGap / 2)
        val twoPanes = listAtFold != null || maxWidth >= if (LocalDensity.current.fontScale >= 1.5f) 900.dp else 720.dp
        val directive = calculatePaneScaffoldDirective(windowInfo).copy(
            maxHorizontalPartitions = if (twoPanes) 2 else 1,
            horizontalPartitionSpacerSize = if (twoPanes) PaneGap else 0.dp,
        )
        val value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination = ThreePaneScaffoldDestinationItem<String>(
                if (selectedId == null) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail,
                selectedId,
            ),
        )
        // Back returns directly to the list, rather than stepping through every selected row.
        BackHandler(enabled = selectedId != null, onBack = onCloseDetail)
        ListDetailPaneScaffold(
            modifier = if (twoPanes && wide) Modifier.background(paneCanvasColor()) else Modifier,
            directive = directive,
            value = value,
            listPane = {
                AnimatedPane(Modifier.preferredWidth(listAtFold ?: 340.dp)) {
                    // Side by side, each pane is its own rounded card on the canvas.
                    Box(if (twoPanes && wide) Modifier.fillMaxSize().paneCard() else Modifier.fillMaxSize()) { listPane(twoPanes) }
                }
            },
            detailPane = {
                AnimatedPane {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface,
                        shape = if (twoPanes && wide) PaneShape else androidx.compose.ui.graphics.RectangleShape) {
                        if (selectedId == null) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Column(Modifier.widthIn(max = 400.dp)) {
                                    EmptyState(emptyIcon, emptyTitle, emptyDescription)
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize().semantics { paneTitle = detailTitle }) {
                                TopAppBar(
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                    title = { Text(detailTitle, style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    navigationIcon = {
                                        if (!twoPanes || detailBackLabel != null) IconButton(onClick = onCloseDetail) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, detailBackLabel ?: "Back")
                                        }
                                    },
                                    actions = {
                                        if (twoPanes && detailBackLabel == null) IconButton(onClick = onCloseDetail) {
                                            Icon(Icons.Outlined.Close, "Close details")
                                        }
                                    },
                                )
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                    // Avoid stretching messages and charts across a desktop-sized window.
                                    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                                        detailStates.SaveableStateProvider(selectedId) { detailPane(selectedId) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
