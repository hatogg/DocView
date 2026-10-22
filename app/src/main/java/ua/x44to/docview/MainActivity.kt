package ua.x44to.docview

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.core.NumFmt
import ua.x44to.docview.data.L
import ua.x44to.docview.data.OpenException
import ua.x44to.docview.data.Opened
import ua.x44to.docview.data.Opener
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t
import ua.x44to.docview.ui.DocTheme
import ua.x44to.docview.ui.Home
import ua.x44to.docview.ui.Viewer
import ua.x44to.docview.ui.isDark
import ua.x44to.docview.ui.share
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Файли, що прийшли ззовні («Відкрити за допомогою», «Поділитися»). */
object Incoming {
    val uris = MutableStateFlow<List<Uri>>(emptyList())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        setupFormats()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val data by Store.data.collectAsStateWithLifecycle()
            val dark = isDark(data.settings)
            DisposableEffect(dark) {
                val st = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = st, navigationBarStyle = st)
                onDispose {}
            }
            DocTheme(data.settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Root(dark)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { uris.add(it) }
            }
        }
        if (uris.isNotEmpty()) Incoming.uris.value = uris
    }

    /** Числа й дати в таблицях — як прийнято в мові телефона. */
    private fun setupFormats() {
        val loc = L.override?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val sym = DecimalFormatSymbols(loc)
        NumFmt.dec = sym.decimalSeparator
        NumFmt.group = if (sym.groupingSeparator == ' ' || sym.groupingSeparator == ' ') ' ' else sym.groupingSeparator
        NumFmt.shortDate = when (loc.country) {
            "US" -> "m/d/yyyy"
            "GB", "IE", "AU", "NZ", "IN" -> "dd/mm/yyyy"
            "CN", "JP", "KR", "TW" -> "yyyy/m/d"
            else -> if (L.ukr || loc.language in setOf("de", "pl", "cs", "sk", "fi", "nb", "da", "tr", "ro", "hu")) "dd.mm.yyyy" else "dd/mm/yyyy"
        }
        val dfs = DateFormatSymbols(loc)
        NumFmt.monthsShort = dfs.shortMonths.take(12).map { it.trimEnd('.') }
        NumFmt.monthsLong = dfs.months.take(12)
        // у DateFormatSymbols неділя перша — переставляємо на понеділок
        val ws = dfs.shortWeekdays.drop(1)
        val wl = dfs.weekdays.drop(1)
        if (ws.size == 7) NumFmt.daysShort = ws.drop(1) + ws.first()
        if (wl.size == 7) NumFmt.daysLong = wl.drop(1) + wl.first()
    }
}

@Composable
private fun Root(appDark: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data by Store.data.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Opened>() }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate } // інтерфейс — лише з головного потоку
    var importing by remember { mutableStateOf(false) }
    val incoming by Incoming.uris.collectAsStateWithLifecycle()

    fun import(uri: Uri) {
        scope.launch {
            importing = true
            val r = withContext(Dispatchers.IO) { runCatching { Opener.import(ctx, uri) } }
            importing = false
            r.onSuccess { o -> stack.add(o) }.onFailure { e ->
                snack.showSnackbar((e as? OpenException)?.message ?: t("Не вдалося відкрити файл", "Couldn't open the file"))
            }
        }
    }

    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            val list = incoming
            Incoming.uris.value = emptyList()
            stack.clear()
            list.firstOrNull()?.let { import(it) }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { import(it) } }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize()) {
        val top = stack.lastOrNull()
        if (top == null) {
            Home(
                data, snack,
                onPick = { pick.launch(arrayOf("*/*")) },
                onOpen = { r ->
                    val o = Opener.fromRecent(r)
                    if (o != null) stack.add(o) else {
                        Store.remove(r.id)
                        scope.launch { snack.showSnackbar(t("Копії файлу вже немає — відкрийте його знову", "The file copy is gone — open it again")) }
                    }
                },
                onShare = { r -> Opener.fromRecent(r)?.let { o -> share(ctx, o.file, o.name, o.format) } },
            )
        } else {
            key(top.id + stack.size) {
                Viewer(top, data.settings, appDark, onBack = { stack.removeAt(stack.lastIndex) }, onOpen = { stack.add(it) })
            }
        }
        if (importing) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(t("Відкриваю файл…", "Opening the file…"))
                    }
                }
            }
        }
    }
}
