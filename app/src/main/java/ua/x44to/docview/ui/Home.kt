@file:OptIn(ExperimentalMaterial3Api::class)

package ua.x44to.docview.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.x44to.docview.core.Format
import ua.x44to.docview.core.Group
import ua.x44to.docview.data.Data
import ua.x44to.docview.data.L
import ua.x44to.docview.data.Recent
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val loc = if (L.ukr) Locale.forLanguageTag("uk") else Locale.ENGLISH
    val time = SimpleDateFormat("HH:mm", loc).format(Date(ms))
    return when {
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> t("сьогодні $time", "today $time")
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) == 1 -> t("вчора $time", "yesterday $time")
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> SimpleDateFormat("d MMM", loc).format(Date(ms))
        else -> SimpleDateFormat("d MMM yyyy", loc).format(Date(ms))
    }
}

private enum class Filter(val uk: String, val en: String) {
    ALL("Усі", "All"), STAR("Обрані", "Starred"), PDF("PDF", "PDF"), WORD("Документи", "Documents"), EXCEL("Таблиці", "Sheets"),
    SLIDES("Презентації", "Slides"), BOOK("Книги", "Books"), OTHER("Інше", "Other"),
}

private fun Filter.matches(r: Recent): Boolean {
    val g = runCatching { Format.valueOf(r.format).group }.getOrDefault(Group.OTHER)
    return when (this) {
        Filter.ALL -> true
        Filter.STAR -> r.star
        Filter.PDF -> g == Group.PDF
        Filter.WORD -> g == Group.WORD
        Filter.EXCEL -> g == Group.EXCEL
        Filter.SLIDES -> g == Group.SLIDES
        Filter.BOOK -> g == Group.BOOK
        Filter.OTHER -> g in setOf(Group.TEXT, Group.IMAGE, Group.ARCHIVE, Group.OTHER)
    }
}

@Composable
fun Home(d: Data, snack: SnackbarHostState, onPick: () -> Unit, onOpen: (Recent) -> Unit, onShare: (Recent) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val list = d.recents.filter { filter.matches(it) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    val present = Filter.entries.filter { f -> f == Filter.ALL || d.recents.any { f.matches(it) } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DocView", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(t("Усі документи в одному місці", "All your documents in one place"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, t("Пошук", "Search")) }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, t("Налаштування", "Settings")) }
                }
            }
            if (searching) item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text(t("Назва файлу", "File name")) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Card(
                    onClick = onPick,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(t("Відкрити файл", "Open a file"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                t("PDF, Word, Excel, PowerPoint, OpenDocument, книги, архіви", "PDF, Word, Excel, PowerPoint, OpenDocument, books, archives"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
            if (d.recents.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (f in present) FilterChip(
                            selected = filter == f, onClick = { filter = f }, label = { Text(t(f.uk, f.en)) },
                            leadingIcon = if (f == Filter.STAR) ({ Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)) }) else null,
                        )
                    }
                }
                item {
                    Text(
                        t("Нещодавні", "Recent"), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            items(list, key = { it.id }) { r -> RecentRow(r, { onOpen(r) }, { onShare(r) }) }
            if (d.recents.isEmpty()) item { Formats() }
            else if (list.isEmpty()) item {
                Empty(Icons.Rounded.Search, t("Нічого не знайдено", "Nothing found"), t("Спробуйте інший фільтр.", "Try another filter."))
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
    if (settings) SettingsDialog(d) { settings = false }
}

@Composable
private fun RecentRow(r: Recent, onClick: () -> Unit, onShare: () -> Unit) {
    val f = runCatching { Format.valueOf(r.format) }.getOrDefault(Format.UNKNOWN)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatBadge(f, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(f.label, L.size(r.size), whenText(r.opened)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            if (r.total > 1 && r.pos > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { ((r.pos + 1).toFloat() / r.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = formatColor(f), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        }
        IconButton(onClick = { Store.star(r.id) }) {
            Icon(if (r.star) Icons.Rounded.Star else Icons.Rounded.StarBorder, t("Обране", "Star"), tint = if (r.star) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, t("Ще", "More")) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(t("Поділитися", "Share")) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(t("Прибрати з історії", "Remove from history")) }, onClick = { menu = false; Store.remove(r.id) })
            }
        }
    }
}

@Composable
private fun Formats() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            t("Що відкриває DocView", "What DocView opens"), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp),
        )
        val rows = listOf(
            Format.PDF to t("PDF — пошук, зміст, паролі, нічний режим", "PDF — search, outline, passwords, night mode"),
            Format.DOCX to t("Word: DOCX, DOC, ODT, RTF", "Word: DOCX, DOC, ODT, RTF"),
            Format.XLSX to t("Excel: XLSX, XLS, ODS, CSV", "Excel: XLSX, XLS, ODS, CSV"),
            Format.PPTX to t("PowerPoint: PPTX, PPT, ODP", "PowerPoint: PPTX, PPT, ODP"),
            Format.EPUB to t("Книги: EPUB, FB2", "Books: EPUB, FB2"),
            Format.TXT to t("Текст: TXT, Markdown, HTML, код", "Text: TXT, Markdown, HTML, code"),
            Format.ZIP to t("Архіви ZIP і картинки", "ZIP archives and images"),
        )
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                for ((f, s) in rows) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(f, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            t(
                "Також відкривайте файли просто з Telegram, Viber, пошти чи «Файлів» — оберіть DocView у «Відкрити за допомогою». Інтернет не потрібен.",
                "You can also open files straight from Telegram, email or Files — pick DocView in “Open with”. No internet needed.",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsDialog(d: Data, onDismiss: () -> Unit) {
    val s = d.settings
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Налаштування", "Settings")) },
        text = {
            Column {
                Text(t("Тема", "Theme"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                listOf(t("Як у системі", "System default"), t("Світла", "Light"), t("Темна", "Dark")).forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().clickable { Store.settings { it.copy(theme = i) } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.theme == i, { Store.settings { it.copy(theme = i) } })
                        Text(n)
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth().clickable { Store.settings { it.copy(dynamic = !it.dynamic) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Кольори Material You", "Material You colours"), Modifier.weight(1f))
                    Switch(s.dynamic, { v -> Store.settings { it.copy(dynamic = v) } })
                }
                Row(Modifier.fillMaxWidth().clickable { Store.settings { it.copy(keepScreenOn = !it.keepScreenOn) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Не гасити екран під час читання", "Keep screen on while reading"), Modifier.weight(1f))
                    Switch(s.keepScreenOn, { v -> Store.settings { it.copy(keepScreenOn = v) } })
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirm = true }) {
                    Icon(Icons.Rounded.DeleteSweep, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("Очистити історію", "Clear history"))
                }
                Text(
                    t("DocView не має доступу до інтернету — файли нікуди не надсилаються.", "DocView has no internet access — your files never leave the phone."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Готово", "Done")) } },
    )
    if (confirm) Confirm(
        t("Очистити історію?", "Clear history?"),
        t("Список нещодавніх файлів і їхні копії буде видалено. Обрані залишаться.", "Recent files and their copies will be removed. Starred files stay."),
        t("Очистити", "Clear"), { Store.clear() }, { confirm = false },
    )
}
