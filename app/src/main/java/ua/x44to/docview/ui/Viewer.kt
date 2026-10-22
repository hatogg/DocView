@file:OptIn(ExperimentalMaterial3Api::class)

package ua.x44to.docview.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.core.ArchiveDoc
import ua.x44to.docview.core.Doc
import ua.x44to.docview.core.DocException
import ua.x44to.docview.core.FlowDoc
import ua.x44to.docview.core.Format
import ua.x44to.docview.core.Group
import ua.x44to.docview.core.NativeDoc
import ua.x44to.docview.core.Parser
import ua.x44to.docview.core.SheetDoc
import ua.x44to.docview.core.SlideDoc
import ua.x44to.docview.core.blockText
import ua.x44to.docview.data.L
import ua.x44to.docview.data.Opened
import ua.x44to.docview.data.Opener
import ua.x44to.docview.data.Perf
import ua.x44to.docview.data.Settings
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t
import ua.x44to.docview.export.Export
import ua.x44to.docview.pdf.PdfEngine
import ua.x44to.docview.pdf.PdfText
import ua.x44to.docview.pdf.PdfUi
import ua.x44to.docview.pdf.PdfView
import ua.x44to.docview.render.Bitmaps
import java.io.File

private sealed interface Load {
    data object Loading : Load
    class Error(val msg: String) : Load
    class Password(val wrong: Boolean) : Load
    class Ready(val doc: Doc, val pdf: PdfEngine? = null) : Load
}

fun mimeOf(f: Format, name: String): String = when (f) {
    Format.PDF -> "application/pdf"
    Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    Format.DOC -> "application/msword"
    Format.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    Format.XLS -> "application/vnd.ms-excel"
    Format.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    Format.PPT -> "application/vnd.ms-powerpoint"
    Format.ODT -> "application/vnd.oasis.opendocument.text"
    Format.ODS -> "application/vnd.oasis.opendocument.spreadsheet"
    Format.ODP -> "application/vnd.oasis.opendocument.presentation"
    Format.RTF -> "application/rtf"
    Format.EPUB -> "application/epub+zip"
    Format.FB2 -> "application/x-fictionbook+xml"
    Format.CSV -> "text/csv"
    Format.HTML -> "text/html"
    Format.MD -> "text/markdown"
    Format.TXT -> "text/plain"
    Format.ZIP -> "application/zip"
    Format.IMAGE -> when (name.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }
    Format.UNKNOWN -> "application/octet-stream"
}

fun shareUri(c: Context, f: File, name: String): Uri {
    // віддаємо файл під справжнім іменем
    val dir = File(c.cacheDir, "share").apply { mkdirs() }
    val out = File(dir, name.replace('/', '_'))
    if (!out.exists() || out.length() != f.length()) f.copyTo(out, overwrite = true)
    return FileProvider.getUriForFile(c, c.packageName + ".files", out)
}

fun share(c: Context, f: File, name: String, format: Format) {
    val uri = shareUri(c, f, name)
    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(format, name)).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri(name, uri)
    c.startActivity(Intent.createChooser(i, name))
}

private fun openWith(c: Context, f: File, name: String, format: Format) {
    val uri = shareUri(c, f, name)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(format, name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { c.startActivity(Intent.createChooser(i, name)) } catch (_: Exception) {}
}

@Composable
fun Viewer(o: Opened, s: Settings, appDark: Boolean, onBack: () -> Unit, onOpen: (Opened) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var attempt by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf<String?>(null) }
    val load by produceState<Load>(Load.Loading, o.id, attempt) {
        value = Load.Loading
        value = withContext(Dispatchers.IO) { loadDoc(ctx, o, password) }
    }
    val view = LocalView.current
    DisposableEffect(s.keepScreenOn) {
        view.keepScreenOn = s.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val ready = load as? Load.Ready
    DisposableEffect(ready) { onDispose { ready?.pdf?.close() } }

    // стани переглядачів
    val doc = ready?.doc
    val pdfUi = remember(ready) { ready?.pdf?.let { PdfUi(it, it.file, o.recent.pos, ctx.applicationContext) } }
    val flowUi = remember(doc) { (doc as? FlowDoc)?.let { FlowUi(it, o.recent.pos) } }
    val sheetUi = remember(doc) { (doc as? SheetDoc)?.let { SheetUi(it, o.recent.pos) } }
    val slideUi = remember(doc) { (doc as? SlideDoc)?.let { SlideUi(it, o.id, o.recent.pos) } }
    val searcher: Searcher? = pdfUi ?: flowUi ?: sheetUi ?: slideUi
    LaunchedEffect(pdfUi) {
        pdfUi?.let { ui ->
            // PdfBox важкий: спершу хай намалюються сторінки, зміст — потім
            kotlinx.coroutines.delay(1500)
            ui.toc = withContext(Dispatchers.IO) { Perf.time("pdf outline") { PdfText.outline(ctx, ui.file) } }
        }
    }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var textOpen by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var bars by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    val paper = paper(s.reading, appDark)
    // «Назад» спершу закриває пошук, а не документ
    BackHandler(enabled = searching) { searching = false; query = ""; searcher?.clear() }

    /** PDF для друку/збереження: сам файл або згенерований. */
    suspend fun asPdf(): File? = withContext(Dispatchers.IO) {
        try {
            when {
                ready?.pdf != null -> ready.pdf.file
                doc is NativeDoc && doc.format == Format.IMAGE -> File(ctx.cacheDir, "export.pdf").also { Export.image(o.file.readBytes(), it) }
                doc != null && doc !is ArchiveDoc && doc !is NativeDoc -> File(ctx.cacheDir, "export.pdf").also { Export.toPdf(doc, o.id, it) }
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            busy = t("Створюю PDF…", "Creating PDF…")
            val f = asPdf()
            val ok = f != null && withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            busy = null
            snack.showSnackbar(if (ok) t("PDF збережено", "PDF saved") else t("Не вдалося зберегти PDF", "Couldn't save the PDF"))
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(bars || searching, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    if (searching && searcher != null) SearchBar(query, { query = it }, searcher, onSearch = { searcher.search(query, scope) }, onClose = {
                        searching = false; query = ""; searcher.clear()
                    })
                    else TopAppBar(
                        title = {
                            Column {
                                Text(o.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(subtitle(o, doc, ready?.pdf), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back")) } },
                        actions = {
                            if (searcher != null) IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, t("Пошук", "Search")) }
                            val hasToc = (flowUi?.doc?.toc?.isNotEmpty() == true) || (pdfUi?.toc?.isNotEmpty() == true)
                            if (hasToc) IconButton(onClick = { tocOpen = true }) { Icon(Icons.Rounded.Toc, t("Зміст", "Contents")) }
                            if (flowUi != null) IconButton(onClick = { textOpen = true }) { Icon(Icons.Rounded.FormatSize, t("Текст", "Text")) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, t("Ще", "More")) }
                                DropdownMenu(menu, { menu = false }) {
                                    if (pdfUi != null) {
                                        DropdownMenuItem(text = { Text(t("Перейти на сторінку", "Go to page")) }, onClick = { menu = false; gotoOpen = true })
                                        DropdownMenuItem(
                                            text = { Text(if (s.pdfNight) t("Звичайні кольори", "Normal colours") else t("Нічний режим", "Night mode")) },
                                            onClick = { menu = false; Store.settings { it.copy(pdfNight = !it.pdfNight) } },
                                        )
                                    }
                                    DropdownMenuItem(text = { Text(t("Поділитися", "Share")) }, onClick = { menu = false; share(ctx, o.file, o.name, o.format) })
                                    DropdownMenuItem(text = { Text(t("Відкрити в іншому застосунку", "Open in another app")) }, onClick = { menu = false; openWith(ctx, o.file, o.name, o.format) })
                                    if (ready != null && doc !is ArchiveDoc) {
                                        DropdownMenuItem(text = { Text(t("Друк", "Print")) }, onClick = {
                                            menu = false
                                            scope.launch {
                                                busy = t("Готую до друку…", "Preparing to print…")
                                                val f = asPdf()
                                                busy = null
                                                if (f != null) Export.print(ctx, o.name, f) else snack.showSnackbar(t("Не вдалося підготувати друк", "Couldn't prepare printing"))
                                            }
                                        })
                                        if (o.format != Format.PDF) DropdownMenuItem(text = { Text(t("Зберегти як PDF", "Save as PDF")) }, onClick = {
                                            menu = false; savePdf.launch(o.name.substringBeforeLast('.') + ".pdf")
                                        })
                                    }
                                    DropdownMenuItem(text = { Text(t("Властивості", "Properties")) }, onClick = { menu = false; info = true })
                                }
                            }
                        },
                    )
                    if (searcher?.busy == true) LinearProgressIndicator(progress = { searcher.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            when (val l = load) {
                Load.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(t("Відкриваю…", "Opening…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is Load.Error -> Empty(Icons.Rounded.ErrorOutline, t("Не вдалося відкрити", "Couldn't open"), l.msg, color = MaterialTheme.colorScheme.error)
                is Load.Password -> PasswordBox(l.wrong) { p -> password = p; attempt++ }
                is Load.Ready -> {
                    val d = l.doc
                    when {
                        pdfUi != null -> PdfView(pdfUi, o.id, s.pdfNight, onTap = { bars = !bars }, onGoto = { gotoOpen = true })
                        flowUi != null -> FlowView(flowUi.doc, o.id, flowUi, paper, s.textScale, onTap = { bars = !bars }, onImage = { image = it })
                        sheetUi != null -> SheetView(sheetUi.doc, o.id, sheetUi, paper)
                        slideUi != null -> SlideView(slideUi)
                        d is ArchiveDoc -> ArchiveView(d) { e ->
                            scope.launch {
                                busy = t("Розпаковую…", "Extracting…")
                                val r = withContext(Dispatchers.IO) { runCatching { Opener.fromArchive(ctx, o, e) } }
                                busy = null
                                r.onSuccess(onOpen).onFailure { snack.showSnackbar(it.message ?: "") }
                            }
                        }
                        d is NativeDoc && d.format == Format.IMAGE -> ImageFileView(o.file) { bars = !bars }
                    }
                }
            }
        }
    }

    if (tocOpen) TocSheet(flowUi, pdfUi, { tocOpen = false }) { i ->
        tocOpen = false
        scope.launch {
            flowUi?.list?.scrollToItem(i)
            pdfUi?.let { it.zoom = 1f; it.tx = 0f; it.ty = 0f; it.list.scrollToItem(i) }
        }
    }
    if (textOpen) TextSheet(s) { textOpen = false }
    if (gotoOpen && pdfUi != null) GotoDialog(pdfUi.count, { gotoOpen = false }) { p -> scope.launch { pdfUi.list.scrollToItem(p) } }
    if (info) InfoDialog(o, doc, ready?.pdf) { info = false }
    busy?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 16.dp)); Text(msg) }
        })
    }
    image?.let { key ->
        val fd = doc as? FlowDoc
        val bmp = remember(key) { fd?.let { Bitmaps.get(o.id, it.res, key, 3000) } }
        if (bmp != null) androidx.compose.ui.window.Dialog(onDismissRequest = { image = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().clickable { image = null }) { ZoomImage(bmp, onTap = { image = null }) }
        } else LaunchedEffect(key) { image = null }
    }
}

private fun loadDoc(ctx: Context, o: Opened, password: String?): Load {
    return try {
        when (o.format) {
            Format.PDF -> {
                val unlocked = File(o.file.parentFile, o.id + ".unlocked.pdf")
                if (password != null) {
                    if (!PdfText.decrypt(ctx, o.file, password, unlocked)) return Load.Password(true)
                }
                val f = if (unlocked.exists()) unlocked else o.file
                try {
                    Load.Ready(ua.x44to.docview.core.NativeDoc(o.name, Format.PDF), Perf.time("pdf open") { PdfEngine(f) })
                } catch (e: SecurityException) {
                    Load.Password(password != null)
                }
            }
            Format.UNKNOWN -> Load.Error(t("Цей тип файлу DocView не відкриває.", "DocView can't open this type of file."))
            else -> Load.Ready(Perf.time("parse ${o.format} ${o.recent.size} B") { Parser.open(o.file, o.name, o.format) })
        }
    } catch (e: DocException) {
        Load.Error(
            when {
                e.needsPassword -> t("Документ захищено паролем. Такі файли Office поки що не відкриваються.", "The document is password-protected. Encrypted Office files aren't supported yet.")
                e.message == "File is too large" -> t("Файл завеликий для перегляду на телефоні.", "The file is too large to view on a phone.")
                else -> t("Файл пошкоджено або це не той формат, що в назві.", "The file is damaged or isn't the format its name says.")
            },
        )
    } catch (e: OutOfMemoryError) {
        Bitmaps.clear()
        Load.Error(t("Документ завеликий для пам'яті телефона.", "The document is too large for the phone's memory."))
    } catch (e: java.io.IOException) {
        Load.Error(t("Файл пошкоджено.", "The file is damaged."))
    } catch (e: Throwable) {
        Load.Error(t("Не вдалося прочитати файл", "Couldn't read the file") + " (${e.javaClass.simpleName})")
    }
}

private fun subtitle(o: Opened, doc: Doc?, pdf: PdfEngine?): String {
    val parts = arrayListOf(o.format.label, L.size(o.recent.size))
    when {
        pdf != null -> parts.add(L.pages(pdf.count))
        doc is SlideDoc -> parts.add(L.slides(doc.slides.size))
        doc is SheetDoc -> parts.add(L.sheets(doc.sheets.size))
        doc is ArchiveDoc -> parts.add(L.files(doc.entries.size))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, s: Searcher, onSearch: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Закрити пошук", "Close search")) } },
        title = {
            TextField(
                query, onQuery, singleLine = true, placeholder = { Text(t("Знайти в документі", "Find in document")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch(); keyboard?.hide() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery(""); s.clear() }) { Icon(Icons.Rounded.Close, null) } }) else null,
            )
        },
        actions = {
            if (s.total > 0 || (s.current < 0 && query.isNotEmpty() && !s.busy)) Text(
                if (s.total > 0) "${s.current + 1}/${s.total}" else "0", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = { s.prev() }, enabled = s.total > 0) { Icon(Icons.Rounded.KeyboardArrowUp, t("Попередній", "Previous")) }
            IconButton(onClick = { s.next() }, enabled = s.total > 0) { Icon(Icons.Rounded.KeyboardArrowDown, t("Наступний", "Next")) }
        },
    )
}

@Composable
private fun PasswordBox(wrong: Boolean, onSubmit: (String) -> Unit) {
    var p by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Empty(Icons.Rounded.Lock, t("Захищено паролем", "Password protected"), t("Введіть пароль, щоб відкрити PDF.", "Enter the password to open this PDF."))
        OutlinedTextField(
            p, { p = it }, label = { Text(t("Пароль", "Password")) }, singleLine = true, isError = wrong,
            supportingText = if (wrong) ({ Text(t("Невірний пароль", "Wrong password")) }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (p.isNotEmpty()) onSubmit(p) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { if (p.isNotEmpty()) onSubmit(p) }) { Text(t("Відкрити", "Open")) }
    }
}

@Composable
private fun TocSheet(flow: FlowUi?, pdf: PdfUi?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val items: List<Triple<String, Int, Int>> = flow?.doc?.toc?.map { Triple(it.title, it.block, it.level) }
        ?: pdf?.toc?.map { Triple(it.title, it.page, it.level) } ?: emptyList()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(t("Зміст", "Contents"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(items) { (title, target, level) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(target) }.padding(start = (24 + level * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = if (level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    )
                    if (pdf != null) Text("${target + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TextSheet(s: Settings, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(t("Розмір тексту", "Text size"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodySmall)
                Slider(s.textScale, { v -> Store.settings { it.copy(textScale = v) } }, valueRange = 0.7f..2.2f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("A", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text(t("Тема читання", "Reading theme"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(t("Авто", "Auto"), t("Папір", "Paper"), t("Сепія", "Sepia"), t("Ніч", "Night")).forEachIndexed { i, n ->
                    FilterChip(s.reading == i, { Store.settings { it.copy(reading = i) } }, { Text(n) })
                }
            }
            Text(
                t("Підказка: розмір тексту можна змінювати й двома пальцями.", "Tip: pinch with two fingers to change the text size."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun GotoDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var v by remember { mutableStateOf("") }
    val n = v.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Перейти на сторінку", "Go to page")) },
        text = {
            OutlinedTextField(
                v, { v = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, label = { Text("1 – $count") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (n != null && n in 1..count) { onGo(n - 1); onDismiss() } }),
            )
        },
        confirmButton = { TextButton(onClick = { if (n != null && n in 1..count) { onGo(n - 1); onDismiss() } }, enabled = n != null && n in 1..count) { Text(t("Перейти", "Go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Скасувати", "Cancel")) } },
    )
}

@Composable
private fun InfoDialog(o: Opened, doc: Doc?, pdf: PdfEngine?, onDismiss: () -> Unit) {
    val words by produceState<Int?>(null, doc) {
        value = (doc as? FlowDoc)?.let { d -> withContext(Dispatchers.Default) { d.blocks.sumOf { b -> blockText(b).split(Regex("\\s+")).count { it.isNotBlank() } } } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Властивості", "Properties")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun row(k: String, v: String) {
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                row(t("Назва файлу", "File name"), o.name)
                row(t("Формат", "Format"), o.format.label + (if (o.format.group != Group.OTHER) "" else ""))
                row(t("Розмір", "Size"), L.size(o.recent.size))
                doc?.title?.takeIf { it.isNotBlank() && it != o.name }?.let { row(t("Заголовок", "Title"), it) }
                (doc as? FlowDoc)?.author?.let { row(t("Автор", "Author"), it) }
                pdf?.let { row(t("Сторінок", "Pages"), "${it.count}") }
                (doc as? SlideDoc)?.let { row(t("Слайдів", "Slides"), "${it.slides.size}") }
                (doc as? SheetDoc)?.let { d -> row(t("Аркуші", "Sheets"), d.sheets.joinToString(", ") { "${it.name} (${it.rows.size}×${it.cols})" }) }
                words?.let { row(t("Слів", "Words"), "$it") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun ArchiveView(d: ArchiveDoc, onOpen: (ua.x44to.docview.core.ArcEntry) -> Unit) {
    if (d.entries.isEmpty()) {
        Empty(Icons.Rounded.ErrorOutline, t("Архів порожній", "Empty archive"), t("У ньому немає файлів.", "There are no files in it."))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(d.entries) { _, e ->
            val name = e.path.substringAfterLast('/')
            val dir = e.path.substringBeforeLast('/', "")
            val f = guess(name)
            Row(Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(f, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(dir.takeIf { it.isNotEmpty() }, if (e.size >= 0) L.size(e.size) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Формат за розширенням (для списку в архіві, до розпакування). */
fun guess(name: String): Format = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> Format.PDF; "docx", "docm", "dotx" -> Format.DOCX; "doc", "dot" -> Format.DOC; "odt" -> Format.ODT; "rtf" -> Format.RTF
    "xlsx", "xlsm" -> Format.XLSX; "xls" -> Format.XLS; "ods" -> Format.ODS; "csv", "tsv" -> Format.CSV
    "pptx", "ppsx" -> Format.PPTX; "ppt", "pps" -> Format.PPT; "odp" -> Format.ODP
    "epub" -> Format.EPUB; "fb2" -> Format.FB2; "htm", "html" -> Format.HTML; "md" -> Format.MD
    "txt", "log", "json", "xml" -> Format.TXT; "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Format.IMAGE
    "zip" -> Format.ZIP
    else -> Format.UNKNOWN
}
