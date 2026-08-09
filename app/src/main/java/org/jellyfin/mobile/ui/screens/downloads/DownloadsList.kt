package org.jellyfin.mobile.ui.screens.downloads

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsList(
    downloads: List<DownloadFiles>,
    onOpen: (DownloadEntity) -> Unit,
    onDownload: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
    onToggleSelection: (DownloadEntity) -> Unit = {},
) {
    val selectionMode = selection.isNotEmpty()

    val resources = LocalResources.current
    val sections = remember(downloads) { groupDownloads(resources, downloads) }

    val expandedStates = remember(sections) {
        mutableStateMapOf<String, Boolean>().apply {
            sections.forEach { section ->
                section.groups.forEach { group -> put(group.name, true) }
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        sections.forEach { section ->
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = section.title.uppercase(),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                }
            }

            section.groups.forEach { group ->
                stickyHeader {
                    val isExpanded = expandedStates[group.name] ?: true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colors.surface)
                            .clickable { expandedStates[group.name] = !isExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                isExpanded -> Icons.Default.KeyboardArrowDown
                                else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colors.secondary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.subtitle1,
                            color = MaterialTheme.colors.secondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Divider(thickness = 1.dp, color = Color.Gray.copy(alpha = 0.1f))
                }

                if (expandedStates[group.name] ?: true) {
                    items(
                        group.items,
                        key = { it.download.id },
                    ) { downloadFiles ->
                        DownloadItem(
                            downloadFiles = downloadFiles,
                            onOpen = { onOpen(downloadFiles.download) },
                            onDownload = { onDownload(downloadFiles.download) },
                            onToggleSelection = { onToggleSelection(downloadFiles.download) },
                            isSelected = selection.contains(downloadFiles.download.id),
                            selectionMode = selectionMode,
                        )
                        Divider(startIndent = 72.dp, thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

data class DownloadSection(val title: String, val groups: List<DownloadGroup>)
data class DownloadGroup(val name: String, val items: List<DownloadFiles>)

private val episodeOrder = compareBy<DownloadFiles>(
    { it.download.item.parentIndexNumber ?: 0 },
    { it.download.item.indexNumber ?: 0 },
)

private fun DownloadFiles.isBook(): Boolean = with(download.item) {
    mediaType == MediaType.BOOK || type == BaseItemKind.AUDIO_BOOK || type == BaseItemKind.BOOK
}

/**
 * Sort [downloads] into sections by type: books and audiobooks, shows (one group per series), music and movies.
 */
private fun groupDownloads(resources: Resources, downloads: List<DownloadFiles>): List<DownloadSection> {
    val (books, others) = downloads.partition { it.isBook() }
    val (episodes, standalone) = others.partition { it.download.item.seriesName != null }
    val music = standalone.filter { it.download.item.mediaType == MediaType.AUDIO }
    val movies = standalone.filter { it.download.item.mediaType == MediaType.VIDEO }

    val shows = episodes
        .groupBy { it.download.item.seriesName.orEmpty() }
        .map { (series, items) -> DownloadGroup(series, items.sortedWith(episodeOrder)) }
        .sortedBy { it.name }

    fun singleGroup(sectionRes: Int, groupRes: Int, items: List<DownloadFiles>) = DownloadSection(
        title = resources.getString(sectionRes),
        groups = listOf(DownloadGroup(resources.getString(groupRes), items.sortedBy { it.download.item.name })),
    )

    return buildList {
        if (shows.isNotEmpty()) add(DownloadSection(resources.getString(R.string.downloads_section_shows), shows))
        if (books.isNotEmpty()) {
            add(singleGroup(R.string.downloads_section_books, R.string.downloads_group_books, books))
        }
        if (music.isNotEmpty()) {
            add(singleGroup(R.string.downloads_section_music, R.string.downloads_group_music, music))
        }
        if (movies.isNotEmpty()) {
            add(singleGroup(R.string.downloads_section_movies, R.string.downloads_group_movies, movies))
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DownloadItem(
    downloadFiles: DownloadFiles,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
) {
    val (download, files) = downloadFiles
    val context = LocalContext.current
    val storageManager: StorageManager = koinInject()

    val isVerified by produceState(initialValue = false, downloadFiles) {
        value = withContext(Dispatchers.IO) {
            storageManager.verify(downloadFiles)
        }
    }

    val iconRes = remember(download.item) {
        when {
            downloadFiles.isBook() -> R.drawable.ic_audiobooks
            download.item.mediaType == MediaType.AUDIO -> R.drawable.ic_music_note_white_24dp
            else -> R.drawable.ic_local_movies_white_64
        }
    }

    ListItem(
        modifier = modifier
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        !isVerified -> onDownload()
                        else -> onOpen()
                    }
                },
                onLongClick = { onToggleSelection() },
            ),
        text = {
            val name = remember(download, context) { download.getDisplayName(context).orEmpty() }
            Text(
                text = name,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        },
        icon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                val uri = remember(files) {
                    files.find { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri
                }

                AsyncImage(
                    model = uri,
                    placeholder = painterResource(iconRes),
                    fallback = painterResource(iconRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(
                        width = 64.dp,
                        height = 64.dp,
                    )
                )
            }
        },
        secondaryText = {
            if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.QUEUED) {
                LinearProgressIndicator()
            } else if (isVerified) {
                Text(
                    text = Formatter.formatShortFileSize(context, files.sumOf { it.size }),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = stringResource(R.string.download_incomplete),
                    color = Color.Yellow,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        },
        singleLineSecondaryText = true,
    )
}
