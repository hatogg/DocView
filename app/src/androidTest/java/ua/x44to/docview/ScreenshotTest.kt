package ua.x44to.docview

import android.graphics.Bitmap
import android.net.Uri
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
import ua.x44to.docview.data.L
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
                settle(3000) // перша сторінка PDF на повільному емуляторі малюється не одразу
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
                settle(3000)
                shot("$lang-dark-pdf")
                back(sc)
                Store.settings { it.copy(pdfNight = false) }
                open(uris.getValue("report.docx"))
                shot("$lang-dark-docx")
                back(sc)
            }
        }
        shell("cmd uimode night no")
        L.override = null
    }

    private fun open(uri: Uri) {
        Incoming.uris.value = listOf(uri)
        settle(3500)
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
