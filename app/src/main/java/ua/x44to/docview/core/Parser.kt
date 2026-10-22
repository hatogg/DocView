package ua.x44to.docview.core

import java.io.File

/** Відкриває файл потрібним розбирачем. Працює у фоновому потоці. */
object Parser {
    fun open(f: File, name: String, format: Format): Doc = when (format) {
        Format.PDF, Format.IMAGE -> NativeDoc(name, format)
        Format.DOCX -> Docx(f).parse()
        Format.XLSX -> Xlsx(f).parse()
        Format.XLS -> Xls(f).parse()
        Format.PPTX -> Pptx(f).parse()
        Format.ODT -> Odf(f).text()
        Format.ODS -> Odf(f).sheets()
        Format.ODP -> Odf(f).slides()
        Format.DOC -> Doc97(f).parse()
        Format.PPT -> Ppt97(f).parse()
        Format.RTF -> Rtf(f).parse()
        Format.TXT -> Text.txt(f, name)
        Format.CSV -> Text.csv(f, name)
        Format.MD -> Text.md(f, name)
        Format.EPUB -> Epub(f).parse()
        Format.FB2 -> Fb2(f).parse()
        Format.HTML -> HtmlConv.file(f, name)
        Format.ZIP -> Archive.list(f, name)
        Format.UNKNOWN -> throw DocException("Unsupported format")
    }
}
