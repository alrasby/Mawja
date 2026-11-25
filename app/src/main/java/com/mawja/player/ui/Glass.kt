package com.mawja.player.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.glassSurface(shape: Shape, accent: Color = MaterialTheme.colorScheme.primary): Modifier {
    val colors = MaterialTheme.colorScheme
    val base = colors.surface.copy(alpha = .97f)
    val top = accent.copy(alpha = .10f).compositeOver(base)
    val bottom = accent.copy(alpha = .03f).compositeOver(base)

    return clip(shape)
        .background(Brush.verticalGradient(listOf(top, base, bottom)), shape)
        .border(
            1.dp,
            Brush.verticalGradient(listOf(colors.outline.copy(alpha = .45f), accent.copy(alpha = .22f))),
            shape,
        )
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    opaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val backing = if (opaque) modifier.background(MaterialTheme.colorScheme.surface, shape) else modifier
    val glass = backing.glassSurface(shape)
    Column(
        if (onClick == null) glass else glass.clickable(role = Role.Button, onClick = onClick),
        content = content,
    )
}


// Fade only the opening edge of scrolling content, leaving the filters crisp.
internal fun Modifier.fadeTopEdge(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fadeHeight = 14.dp.toPx()
            val mask = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = fadeHeight)
            onDrawWithContent {
                drawContent()
                drawRect(mask, size = androidx.compose.ui.geometry.Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
            }
        }

@Composable
internal fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
        GlassCard(Modifier.fillMaxWidth(), opaque = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                text()
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
