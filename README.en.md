<p align="center">
  <img src="docs/banner-en.png" alt="DocView — every document on your phone" width="100%">
</p>

<p align="center">
  <a href="https://github.com/hatogg/DocView/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/hatogg/DocView?style=flat-square&label=release&color=3A5BA9"></a>
  <a href="https://github.com/hatogg/DocView/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/hatogg/DocView/build.yml?branch=main&style=flat-square&label=build"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-E53935?style=flat-square"></a>
</p>

<p align="center"><a href="README.md">Українська</a> &nbsp;|&nbsp; <b>English</b></p>

**DocView** is a document viewer for Android that opens everything that usually lands in your messenger or inbox: PDF, Word, Excel, PowerPoint, OpenDocument, e-books and archives. One app instead of five — no ads, no sign-up, no internet. Your files never leave the phone.

<p align="center">
  <a href="https://github.com/hatogg/DocView/releases/latest"><img alt="Download APK" src="https://img.shields.io/badge/Download-APK-3A5BA9?style=for-the-badge&logo=android&logoColor=white"></a>
</p>

<p align="center">
  <img src="docs/screens/en-light-home.png" width="24%" alt="Home screen with recent files">
  <img src="docs/screens/en-light-pdf-search.png" width="24%" alt="Searching a PDF">
  <img src="docs/screens/en-light-docx.png" width="24%" alt="A Word document">
  <img src="docs/screens/en-light-xlsx.png" width="24%" alt="An Excel spreadsheet">
</p>
<p align="center">
  <img src="docs/screens/en-light-pptx.png" width="24%" alt="A PowerPoint presentation">
  <img src="docs/screens/en-light-epub.png" width="24%" alt="An EPUB book in the Sepia theme">
  <img src="docs/screens/en-dark-pdf.png" width="24%" alt="PDF in night mode">
  <img src="docs/screens/en-dark-home.png" width="24%" alt="Home screen in the dark theme">
</p>

<sub>Real screenshots: GitHub Actions runs the app in an Android 14 emulator and opens demo documents.</sub>

## What it opens

| | Formats | What it does |
| --- | --- | --- |
| 📕 **PDF** | `.pdf` | Smooth scrolling and zoom up to 600% with sharp text, text search with highlights, outline (bookmarks), password-protected PDFs, night mode, go to page, a scrubber for long files |
| 📘 **Word** | `.docx` `.doc` `.odt` `.rtf` | Headings, bold/italic/colour, numbered lists, tables, images, contents from headings. Old Word 97–2003 `.doc` and even Word 95 |
| 📗 **Excel** | `.xlsx` `.xls` `.ods` `.csv` | Sheets, number and date formats, merged cells, colours, frozen row and column headers, search, pinch zoom |
| 📙 **PowerPoint** | `.pptx` `.ppt` `.odp` | Slides with shapes, pictures, tables and backgrounds, speaker notes, full-screen show with swiping |
| 📚 **Books** | `.epub` `.fb2` `.fb2.zip` | Contents, Paper / Sepia / Night themes, text size, remembers where you stopped |
| 📄 **Text** | `.txt` `.md` `.html` `.json` `.xml`, code | Any encoding (UTF-8, Windows-1251), Markdown with formatting |
| 🗜️ **Other** | `.zip`, images | Browse an archive and open files from it, zoomable images |

Plus for every document: **print**, **save as PDF**, Share, Open in another app, properties (pages, words, author), history with starred files and reading progress, Material You and a dark theme.

## Install

1. On your phone, open the [latest release](https://github.com/hatogg/DocView/releases/latest) and download `DocView-<version>.apk`.
2. Open the downloaded file and allow installs from that source.
3. **Install** → **Open**.

Requires Android 8.0 or newer. New versions install over the old one.

**Opening files from Telegram, WhatsApp or email:** tap the file → “Open with” → **DocView** (you can choose “Always”). Or use “Open a file” inside DocView.

## Privacy

The app **has no internet permission** (the build checks this automatically), so documents never leave the phone. DocView doesn't ask for access to all your storage: files only arrive through the system file picker or “Open with”, and the copies kept for history live in the app's own storage.

## For developers

Kotlin, Jetpack Compose, Material 3. PDFs are drawn by the system `PdfRenderer`; search and passwords use [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android). All office formats are parsed by an in-house core with no third-party libraries:

```
app/src/main/java/ua/x44to/docview/
├── core/               ← plain Kotlin, no Android: testable on a regular JVM
│   ├── Xml.kt          ← streaming XML/HTML parser
│   ├── Cfb.kt          ← OLE2 container of old DOC/XLS/PPT
│   ├── Docx.kt Xlsx.kt Pptx.kt Odf.kt   ← Office 2007+ and OpenDocument
│   ├── Doc97.kt Xls.kt Ppt97.kt         ← Office 97–2003 (piece table, BIFF8, persist directory)
│   ├── Rtf.kt Html.kt Books.kt Text.kt  ← RTF, HTML, EPUB, FB2, TXT/CSV/Markdown, ZIP
│   └── NumFmt.kt       ← Excel number and date formats
├── pdf/                ← PdfRenderer with zoom and sharp tiles, search via PdfBox
├── render/             ← slide drawing on Canvas (screen and PDF)
├── export/             ← any document → PDF, printing
└── ui/                 ← home screen and a viewer for each type
```

- **Build:** Android Studio or `./gradlew assembleRelease` (JDK 17, Android SDK 35).
- **GitHub Actions** ([build.yml](.github/workflows/build.yml)) builds the APK, checks it has no internet permission and publishes it to the `v<versionName>` release.
- **Screenshots** ([screenshots.yml](.github/workflows/screenshots.yml)) come from an Android 14 emulator: a test opens demo documents in Ukrainian and English, light and dark.
- **Signing:** the `signing/docview.jks` key is in the repo so every build installs over the previous one. For Google Play, replace it with your own and pass the passwords as `DV_STORE_PASS` / `DV_KEY_PASS` secrets.

## License

[MIT](LICENSE) © x44to. PdfBox-Android is under the Apache License 2.0.
