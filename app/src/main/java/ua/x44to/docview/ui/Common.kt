package ua.x44to.docview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.x44to.docview.core.Format
import ua.x44to.docview.core.Group
import ua.x44to.docview.data.t

fun formatIcon(f: Format): ImageVector = when (f.group) {
    Group.PDF -> Icons.Rounded.PictureAsPdf
    Group.WORD -> Icons.Rounded.Description
    Group.EXCEL -> Icons.Rounded.TableChart
    Group.SLIDES -> Icons.Rounded.Slideshow
    Group.BOOK -> Icons.Rounded.AutoStories
    Group.TEXT -> Icons.Rounded.Article
    Group.IMAGE -> Icons.Rounded.Image
    Group.ARCHIVE -> Icons.Rounded.FolderZip
    Group.OTHER -> Icons.Rounded.InsertDriveFile
}

/** Плашка формату: кольоровий квадрат з іконкою і підписом «DOCX». */
@Composable
fun FormatBadge(f: Format, size: Dp = 48.dp) {
    val c = formatColor(f)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(c.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(formatIcon(f), null, tint = c, modifier = Modifier.size(size * 0.46f))
            if (size >= 40.dp && f != Format.UNKNOWN) Text(
                f.label, color = c, fontSize = (size.value * 0.19f).sp, fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.2f).sp,
            )
        }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(26.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun Confirm(title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Скасувати", "Cancel")) } },
    )
}
