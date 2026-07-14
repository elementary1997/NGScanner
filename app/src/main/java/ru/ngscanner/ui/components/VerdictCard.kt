package ru.ngscanner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.agent.DiagnosticVerdict
import ru.ngscanner.agent.EvidenceKind
import ru.ngscanner.agent.VerdictCause
import ru.ngscanner.report.ReportStatus

/**
 * Карточка структурного вердикта — триаж, а не чат.
 *
 * Ключевое здесь не оформление, а **видимое заземление**: у каждой причины показано, на
 * каких основаниях она стоит и какие из них реально подтверждены данными с адаптера.
 * Если модель сослалась на код, которого адаптер не отдавал, это видно красным —
 * галлюцинация «можно ехать» не должна проходить незаметно.
 */
@Composable
internal fun VerdictCard(v: DiagnosticVerdict) {
    val cs = MaterialTheme.colorScheme
    val accent = severityColor(v.severity)
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Метка тяжести + вердикт «можно ли ехать» — самое важное, наверху.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(7.dp), color = accent.copy(alpha = 0.16f)) {
                    Text(
                        v.severity,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            if (v.summary.isNotBlank()) {
                Text(v.summary, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, lineHeight = 22.sp)
            }

            // Второе мнение движка правил — важнее вердикта модели: правила не галлюцинируют.
            v.crossCheck?.let { VerdictWarning(it, cs.error) }

            // Предупреждения о качестве самого вывода — важнее, чем сам вывод.
            if (v.hasContradiction) {
                VerdictWarning(
                    "Ассистент сослался на код, которого адаптер не отдавал. Такой вывод " +
                        "нельзя принимать на веру — перечитайте основания ниже.",
                    cs.error,
                )
            } else if (v.ungrounded) {
                VerdictWarning(
                    "Ни одна причина не подтверждена данными с адаптера — это версии, а не " +
                        "диагноз. Соберите данные (полный скан) и повторите.",
                    cs.tertiary,
                )
            }

            if (v.causes.isNotEmpty()) {
                HorizontalDivider(color = cs.outline.copy(alpha = 0.5f))
                SectionLabel("Вероятные причины")
                v.causes.forEach { CauseRow(it) }
            }

            if (v.checks.isNotEmpty()) {
                HorizontalDivider(color = cs.outline.copy(alpha = 0.5f))
                SectionLabel("Что проверить")
                v.checks.forEach { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            c,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (v.diy.isNotBlank()) {
                HorizontalDivider(color = cs.outline.copy(alpha = 0.5f))
                SectionLabel("Своими силами или в сервис")
                Text(v.diy, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun CauseRow(c: VerdictCause) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (c.confidence > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${c.confidence}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (c.grounded) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
        if (c.confidence > 0) {
            LinearProgressIndicator(
                progress = { c.confidence / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = if (c.grounded) cs.primary else cs.onSurfaceVariant,
                trackColor = cs.surfaceVariant,
            )
        }
        c.evidence.forEach { e -> EvidenceRow(e.text, e.kind) }
        Spacer(Modifier.height(2.dp))
    }
}

/** Строка основания: значок показывает, подтверждено ли оно данными с адаптера. */
@Composable
private fun EvidenceRow(text: String, kind: EvidenceKind) {
    val cs = MaterialTheme.colorScheme
    val (icon, color, hint) = when (kind) {
        EvidenceKind.CONFIRMED -> Triple(Icons.Rounded.CheckCircle, cs.primary, "с адаптера")
        EvidenceKind.CONTRADICTED -> Triple(Icons.Rounded.Warning, cs.error, "нет в данных")
        EvidenceKind.UNVERIFIED -> Triple(Icons.Rounded.HelpOutline, cs.onSurfaceVariant, "вывод")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(14.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (kind == EvidenceKind.CONTRADICTED) cs.error else cs.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun VerdictWarning(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(9.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun severityColor(severity: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (severity) {
        ReportStatus.CRITICAL -> cs.error
        ReportStatus.WARNING -> cs.tertiary
        ReportStatus.OK -> cs.primary
        else -> cs.onSurfaceVariant
    }
}
