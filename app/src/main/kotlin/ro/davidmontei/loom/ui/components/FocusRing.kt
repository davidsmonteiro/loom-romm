package ro.davidmontei.loom.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.davidmontei.loom.ui.theme.LoomColors

/**
 * Loom's only indication: a thick silk-gold ring outside the focused element (readable at arm's
 * length on a handheld) and a faint wash while pressed. Every Loom component uses it, so focus is
 * always visible when driving the app with a pad.
 */
class FocusRingIndication(
    private val cornerRadius: Dp = 14.dp,
    private val ringWidth: Dp = 3.dp,
    private val gap: Dp = 3.dp,
    private val color: Color = LoomColors.Accent,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, cornerRadius, ringWidth, gap, color)

    override fun equals(other: Any?) = other is FocusRingIndication && other.cornerRadius == cornerRadius &&
        other.ringWidth == ringWidth && other.gap == gap && other.color == color

    override fun hashCode() = listOf(cornerRadius, ringWidth, gap, color).hashCode()
}

private class FocusRingNode(
    private val source: InteractionSource,
    private val cornerRadius: Dp,
    private val ringWidth: Dp,
    private val gap: Dp,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(cornerRadius.toPx()))
        }
        if (focused) {
            val outset = gap.toPx() + ringWidth.toPx() / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(-outset, -outset),
                size = Size(size.width + outset * 2, size.height + outset * 2),
                cornerRadius = CornerRadius(cornerRadius.toPx() + outset),
                style = Stroke(ringWidth.toPx()),
            )
        }
    }
}
