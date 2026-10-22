# kotlinx.serialization: згенеровані серіалізатори моделей даних
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class ua.x44to.docview.**$$serializer { *; }
-keepclassmembers class ua.x44to.docview.** { *** Companion; }
-keepclasseswithmembers class ua.x44to.docview.** { kotlinx.serialization.KSerializer serializer(...); }

# PdfBox для Android: ресурси шрифтів і класи, що завантажуються за іменем
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
