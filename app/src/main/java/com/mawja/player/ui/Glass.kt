package com.mawja.player.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.glassSurface(shape: Shape, accent: Color = MaterialTheme.colorScheme.primary): Modifier {
    val colors = MaterialTheme.colorScheme
    val base = colors.surface.copy(alpha = .88f)
    val top = accent.copy(alpha = .16f).compositeOver(base)
    val bottom = accent.copy(alpha = .05f).compositeOver(base)

    return clip(shape)
        .background(Brush.verticalGradient(listOf(top, base, bottom)), shape)
        .border(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .72f), accent.copy(alpha = .28f))),
            shape,
        )
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = modifier.glassSurface(RoundedCornerShape(24.dp))
    Column(
        if (onClick == null) glass else glass.clickable(role = Role.Button, onClick = onClick),
        content = content,
    )
}
