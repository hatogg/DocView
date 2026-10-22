package ua.x44to.docview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.x44to.docview.core.Detect
import ua.x44to.docview.core.Parser
import ua.x44to.docview.data.L
import ua.x44to.docview.export.Export
import ua.x44to.docview.data.Opener
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t
import java.io.File
import java.io.FileOutputStream

/**
 * Не перевірка, а фотосесія: відкриває демо-документи (PDF, Word, Excel, PowerPoint, EPUB)
 * і зберігає скриншоти в files/screens. Українською та англійською, у світлій і темній темі.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    /** Імена файлів, як їх побачить користувач. */
    private fun names(lang: String) = if (lang == "uk") mapOf(
        "guide.pdf" to "Посібник DocView.pdf", "report.docx" to "Звіт за квартал.docx", "budget.xlsx" to "Бюджет.xlsx",
        "deck.pptx" to "Квартальний звіт.pptx", "book.epub" to "Осінь у горах.epub",
    ) else mapOf(
        "guide.pdf" to "DocView Guide.pdf", "report.docx" to "Quarterly report.docx", "budget.xlsx" to "Budget.xlsx",
        "deck.pptx" to "Quarterly review.pptx", "book.epub" to "Autumn in the Mountains.epub",
    )

    private fun asset(lang: String, file: String): Uri {
        val dir = File(ctx.cacheDir, "demo-$lang").apply { mkdirs() }
        val out = File(dir, names(lang)[file] ?: file)
        inst.context.assets.open("$lang/$file").use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
        return Uri.fromFile(out)
    }

    @Test
    fun screenshots() {
        Store.init(ctx)
        for (lang in listOf("uk", "en")) {
            L.override = lang
            shell("cmd uimode night no")
            Store.update { it.copy(recents = emptyList()) }
            Store.settings { it.copy(theme = 0, dynamic = false, reading = 0, textScale = 1f, pdfNight = false) }
            // історія: найсвіжіший — PDF
            val order = listOf("book.epub", "deck.pptx", "budget.xlsx", "report.docx", "guide.pdf")
            val uris = order.associateWith { asset(lang, it) }
            for (f in order) { Opener.import(ctx, uris.getValue(f)); Thread.sleep(30) }
            Store.star(Store.data.value.recents.first { it.name.endsWith(".pdf") }.id)

            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                settle()
                shot("$lang-light-home")
                open(uris.getValue("guide.pdf"))
                shot("$lang-light-pdf")
                compose.onAllNodesWithContentDescription(t("Пошук", "Search")).onFirst().performClick()
                settle(800)
                compose.onNode(hasSetTextAction()).performTextInput("DocView")
                compose.onNode(hasSetTextAction()).performImeAction()
                settle(5000)
                shot("$lang-light-pdf-search")
                back(sc) // закриває пошук
                back(sc) // закриває документ
                open(uris.getValue("report.docx"))
                shot("$lang-light-docx")
                back(sc)
                open(uris.getValue("budget.xlsx"))
                shot("$lang-light-xlsx")
                back(sc)
                open(uris.getValue("deck.pptx"))
                shot("$lang-light-pptx")
                back(sc)
                Store.settings { it.copy(reading = 2) }
                open(uris.getValue("book.epub"))
                shot("$lang-light-epub")
                back(sc)
                Store.settings { it.copy(reading = 0) }
            }
            shell("cmd uimode night yes")
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                settle()
                shot("$lang-dark-home")
                Store.settings { it.copy(pdfNight = true) }
                open(uris.getValue("guide.pdf"))
                shot("$lang-dark-pdf")
                back(sc)
                Store.settings { it.copy(pdfNight = false) }
                open(uris.getValue("report.docx"))
                shot("$lang-dark-docx")
                back(sc)
            }
        }
        shell("cmd uimode night no")
        searchStress()
        exportChecks()
        L.override = null
    }

    /** Пошук у PDF кілька разів поспіль: колись тут зрідка падало (стан Compose змінювався з фонового потоку). */
    private fun searchStress() {
        val pdf = asset("en", "guide.pdf")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            settle()
            open(pdf)
            for (q in listOf("DocView", "PDF", "night", "zzz", "Word")) {
                compose.onAllNodesWithContentDescription(t("Пошук", "Search")).onFirst().performClick()
                settle(600)
                compose.onNode(hasSetTextAction()).performTextInput(q)
                compose.onNode(hasSetTextAction()).performImeAction()
                settle(2500)
                back(sc)
            }
            back(sc)
        }
    }

    /** «Зберегти як PDF» для кожного формату: сторінки результату — у files/checks (для перевірки, не для README). */
    private fun exportChecks() {
        val dir = File(ctx.filesDir, "checks").apply { deleteRecursively(); mkdirs() }
        for (file in listOf("report.docx", "budget.xlsx", "deck.pptx", "book.epub")) {
            val src = File(asset("en", file).path!!)
            val doc = Parser.open(src, src.name, Detect.of(src, src.name))
            val pdf = File(ctx.cacheDir, "check-$file.pdf")
            Export.toPdf(doc, "check-$file", pdf)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until minOf(r.pageCount, 2)) r.openPage(i).use { pg ->
                        val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(File(dir, "${file.substringBefore('.')}-p${i + 1}of${r.pageCount}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }

    private fun open(uri: Uri) {
        Incoming.uris.value = listOf(uri)
        settle(1500)
        waitLoaded()
        settle(800)
    }

    /** Чекаємо, доки зникнуть усі «крутилки»: розбір документа, сторінки PDF. */
    private fun waitLoaded() {
        val loading = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
        val t0 = System.currentTimeMillis()
        try {
            compose.waitUntil(30_000) { compose.onAllNodes(loading).fetchSemanticsNodes().isEmpty() }
        } catch (e: Throwable) {
            android.util.Log.w("DocView", "still loading after 30 s", e)
        }
        android.util.Log.d("DocView", "loaded in ${System.currentTimeMillis() - t0} ms")
    }

    private fun back(sc: ActivityScenario<MainActivity>) {
        sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle(900)
    }

    private fun settle(ms: Long = 1500) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: error("no screenshot for $name")
        val dir = File(ctx.filesDir, "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shell(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }
}
