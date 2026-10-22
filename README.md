<p align="center">
  <img src="docs/banner-uk.png" alt="DocView — усі документи на телефоні" width="100%">
</p>

<p align="center">
  <a href="https://github.com/hatogg/DocView/releases/latest"><img alt="Реліз" src="https://img.shields.io/github/v/release/hatogg/DocView?style=flat-square&label=реліз&color=3A5BA9"></a>
  <a href="https://github.com/hatogg/DocView/actions/workflows/build.yml"><img alt="Збірка" src="https://img.shields.io/github/actions/workflow/status/hatogg/DocView/build.yml?branch=main&style=flat-square&label=збірка"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin і Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="Ліцензія MIT" src="https://img.shields.io/badge/ліцензія-MIT-E53935?style=flat-square"></a>
</p>

<p align="center"><b>Українська</b> &nbsp;|&nbsp; <a href="README.en.md">English</a></p>

**DocView** — переглядач документів для Android, який відкриває все, що зазвичай приходить у месенджері чи пошті: PDF, Word, Excel, PowerPoint, OpenDocument, електронні книги й архіви. Один застосунок замість п'яти, без реклами, реєстрації та інтернету — файли нікуди не надсилаються.

<p align="center">
  <a href="https://github.com/hatogg/DocView/releases/latest"><img alt="Завантажити APK" src="https://img.shields.io/badge/Завантажити-APK-3A5BA9?style=for-the-badge&logo=android&logoColor=white"></a>
</p>

<p align="center">
  <img src="docs/screens/uk-light-home.png" width="24%" alt="Головний екран з нещодавніми файлами">
  <img src="docs/screens/uk-light-pdf-search.png" width="24%" alt="Пошук у PDF">
  <img src="docs/screens/uk-light-docx.png" width="24%" alt="Документ Word">
  <img src="docs/screens/uk-light-xlsx.png" width="24%" alt="Таблиця Excel">
</p>
<p align="center">
  <img src="docs/screens/uk-light-pptx.png" width="24%" alt="Презентація PowerPoint">
  <img src="docs/screens/uk-light-epub.png" width="24%" alt="Книга EPUB у темі «Сепія»">
  <img src="docs/screens/uk-dark-pdf.png" width="24%" alt="PDF у нічному режимі">
  <img src="docs/screens/uk-dark-home.png" width="24%" alt="Головний екран у темній темі">
</p>

<sub>Справжні скриншоти: GitHub Actions запускає застосунок в емуляторі Android 14 і відкриває демо-документи.</sub>

## Що відкриває

| | Формати | Що вміє |
| --- | --- | --- |
| 📕 **PDF** | `.pdf` | Плавне гортання й масштаб до 600 % з чіткими літерами, пошук тексту з підсвіткою, зміст (закладки), PDF з паролем, нічний режим, перехід на сторінку, повзунок для довгих файлів |
| 📘 **Word** | `.docx` `.doc` `.odt` `.rtf` | Заголовки, жирний/курсив/колір, списки з нумерацією, таблиці, картинки, зміст за заголовками. Старі `.doc` з Word 97–2003 і навіть Word 95 |
| 📗 **Excel** | `.xlsx` `.xls` `.ods` `.csv` | Аркуші, формати чисел і дат, об'єднані комірки, кольори, закріплені заголовки рядків і стовпців, пошук, масштаб двома пальцями |
| 📙 **PowerPoint** | `.pptx` `.ppt` `.odp` | Слайди з фігурами, картинками, таблицями й фоном, нотатки доповідача, показ на весь екран з гортанням |
| 📚 **Книги** | `.epub` `.fb2` `.fb2.zip` | Зміст, теми «Папір», «Сепія», «Ніч», розмір тексту, запам'ятовує, де ви зупинились |
| 📄 **Текст** | `.txt` `.md` `.html` `.json` `.xml` код | Кирилиця в будь-якому кодуванні (UTF-8, Windows-1251), Markdown з форматуванням |
| 🗜️ **Інше** | `.zip`, картинки | Перегляд архіву й відкриття файлів з нього, картинки з масштабом |

Плюс для будь-якого документа: **друк**, **збереження в PDF**, «Поділитися», «Відкрити в іншому застосунку», властивості (сторінки, слова, автор), історія з обраними файлами й прогресом читання, Material You і темна тема.

## Встановлення

1. На телефоні відкрийте [останній реліз](https://github.com/hatogg/DocView/releases/latest) і завантажте `DocView-<версія>.apk`.
2. Відкрийте завантажений файл і дозвольте встановлення з цього джерела.
3. **Встановити** → **Відкрити**.

Потрібен Android 8.0 або новіший. Нові версії ставляться поверх старої.

**Як відкривати файли з Telegram, Viber чи пошти:** натисніть на файл → «Відкрити за допомогою» → **DocView** (можна зробити «Завжди»). Або в самому DocView — «Відкрити файл».

## Приватність

У застосунку **немає дозволу на інтернет** (збірка перевіряє це автоматично) — документи ніколи не залишають телефон. DocView не просить доступу до всієї пам'яті: файли надходять лише через системний вибір файлів або «Відкрити за допомогою», а їхні копії для історії лежать у пам'яті самого застосунку.

## Для розробників

Kotlin, Jetpack Compose, Material 3. PDF малює системний `PdfRenderer`, пошук і паролі — [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android). Усі офісні формати розбирає власне ядро без сторонніх бібліотек:

```
app/src/main/java/ua/x44to/docview/
├── core/               ← чистий Kotlin, без Android: можна перевіряти на звичайній JVM
│   ├── Xml.kt          ← потоковий XML/HTML-розбирач
│   ├── Cfb.kt          ← контейнер OLE2 старих DOC/XLS/PPT
│   ├── Docx.kt Xlsx.kt Pptx.kt Odf.kt   ← Office 2007+ і OpenDocument
│   ├── Doc97.kt Xls.kt Ppt97.kt         ← Office 97–2003 (piece table, BIFF8, persist directory)
│   ├── Rtf.kt Html.kt Books.kt Text.kt  ← RTF, HTML, EPUB, FB2, TXT/CSV/Markdown, ZIP
│   └── NumFmt.kt       ← числові формати й дати Excel
├── pdf/                ← PdfRenderer з масштабом і чіткими фрагментами, пошук через PdfBox
├── render/             ← малювання слайдів на Canvas (екран і PDF)
├── export/             ← будь-який документ → PDF, друк
└── ui/                 ← головний екран і переглядачі для кожного типу
```

- **Збірка:** Android Studio або `./gradlew assembleRelease` (JDK 17, Android SDK 35).
- **GitHub Actions** ([build.yml](.github/workflows/build.yml)) збирає APK, перевіряє, що в ньому немає дозволу на інтернет, і викладає його в реліз `v<versionName>`.
- **Скриншоти** ([screenshots.yml](.github/workflows/screenshots.yml)) знімає емулятор Android 14: тест відкриває демо-документи українською й англійською, у світлій і темній темі.
- **Підпис:** ключ `signing/docview.jks` лежить у репозиторії, щоб кожна збірка ставилася поверх попередньої. Для Google Play замініть його своїм і передайте паролі секретами `DV_STORE_PASS` / `DV_KEY_PASS`.

## Ліцензія

[MIT](LICENSE) © x44to. PdfBox-Android — Apache License 2.0.
