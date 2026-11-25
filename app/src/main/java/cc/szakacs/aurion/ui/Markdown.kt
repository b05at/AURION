package cc.szakacs.aurion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.ui.theme.AurionColors

/** Schlanker Markdown-Renderer: Überschriften, Listen, **fett**, *kursiv*, `Code` und Codeblöcke. */
@Composable
fun MarkdownText(md: String, color: Color = AurionColors.TextHigh, modifier: Modifier = Modifier) {
    val blocks = splitBlocks(md)
    Column(modifier) {
        blocks.forEach { (isCode, content) ->
            if (isCode) {
                Text(
                    content.trimEnd(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = AurionColors.Silver,
                    softWrap = false,
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x55000000))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            } else {
                content.trim('\n').lines().forEach { line ->
                    val t = line.trimEnd()
                    when {
                        t.isBlank() -> Text(" ", fontSize = 6.sp)
                        t.startsWith("#") -> Text(
                            inline(t.trimStart('#').trim()),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = color, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                        Regex("^\\s*[-*•]\\s+").containsMatchIn(t) -> Text(
                            inline("•  " + t.replaceFirst(Regex("^\\s*[-*•]\\s+"), "")),
                            style = MaterialTheme.typography.bodyLarge, color = color,
                            modifier = Modifier.padding(start = (if (t.startsWith("  ")) 16 else 4).dp, top = 2.dp)
                        )
                        Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(t) -> Text(
                            inline(t.trim()), style = MaterialTheme.typography.bodyLarge, color = color,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                        else -> Text(inline(t), style = MaterialTheme.typography.bodyLarge, color = color)
                    }
                }
            }
        }
    }
}

private fun splitBlocks(md: String): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val parts = md.split("```")
    parts.forEachIndexed { i, p ->
        if (i % 2 == 1) out += true to p.substringAfter('\n', p) // Sprache in erster Zeile entfernen
        else if (p.isNotBlank()) out += false to p
    }
    return out
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val re = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\*(?!\\s)(.+?)\\*|_(?!\\s)(.+?)_")
    re.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1] + m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x40000000), color = AurionColors.Cyan)) { append(m.groupValues[3]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[4] + m.groupValues[5]) }
        }
        i = m.range.last + 1
    }
    if (i < s.length) append(s.substring(i))
}
