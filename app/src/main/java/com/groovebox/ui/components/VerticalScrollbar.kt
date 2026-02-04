package com.groovebox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyListState
) {
    val totalItems = state.layoutInfo.totalItemsCount
    if (totalItems == 0) return
    
    Canvas(modifier = modifier.width(3.dp)) {
        val visibleItems = state.layoutInfo.visibleItemsInfo.size
        if (visibleItems < totalItems) {
            val scrollbarHeight = size.height * (visibleItems.toFloat() / totalItems)
            val scrollbarOffset = size.height * (state.firstVisibleItemIndex.toFloat() / totalItems)
            
            drawRoundRect(
                color = Color.Cyan.copy(alpha = 0.3f),
                topLeft = Offset(0f, scrollbarOffset),
                size = Size(size.width, scrollbarHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}
