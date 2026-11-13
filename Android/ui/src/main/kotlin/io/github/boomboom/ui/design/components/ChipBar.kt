package io.github.boomboom.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A side-scrolling row of filter pills. The selected pill is white with black text.
 *
 *     ChipBar(page.chips, selection = selected, title = { it.title }) { chip -> model.select(chip) }
 */
@Composable
fun <T> ChipBar(
    items: List<T>,
    selection: T?,
    title: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        modifier.testTag("chipBar"),
        contentPadding = PaddingValues(horizontal = BoomMetrics.margin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items, key = { index, item -> "$index:${title(item)}" }) { _, item ->
            ChipPill(title(item), isSelected = item == selection) { onSelect(item) }
        }
    }
}

/** One filter pill. The pill is 34 dp tall; its touch target is 48 dp. */
@Composable
fun ChipPill(title: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .semantics { selected = isSelected }
            .testTag("chip")
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 34.dp)
                .clip(shape)
                .background(if (isSelected) BoomColor.chipSelected else BoomColor.chip)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, style = BoomFont.button, maxLines = 1,
                color = if (isSelected) Color.Black else BoomColor.textPrimary,
            )
        }
    }
}
