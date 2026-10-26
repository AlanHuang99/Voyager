package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.voyagerfiles.R
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder

@Composable
fun FileSortMenu(
    sortBy: SortBy,
    sortOrder: SortOrder,
    onSortByChange: (SortBy) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showSortMenu = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.content_desc_sort))
        }
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false },
        ) {
            SortBy.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes)) },
                    trailingIcon = {
                        if (sortBy == sort) {
                            Icon(
                                if (sortOrder == SortOrder.ASCENDING) Icons.Filled.KeyboardArrowUp
                                else Icons.Filled.KeyboardArrowDown,
                                stringResource(
                                    if (sortOrder == SortOrder.ASCENDING) {
                                        R.string.browser_sort_ascending
                                    } else {
                                        R.string.browser_sort_descending
                                    },
                                ),
                            )
                        }
                    },
                    onClick = {
                        onSortByChange(sort)
                        showSortMenu = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (sortOrder == SortOrder.ASCENDING) {
                                R.string.browser_sort_descending_action
                            } else {
                                R.string.browser_sort_ascending_action
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (sortOrder == SortOrder.ASCENDING) Icons.Filled.KeyboardArrowDown
                        else Icons.Filled.KeyboardArrowUp,
                        null,
                    )
                },
                onClick = {
                    onSortOrderChange(
                        if (sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING
                        else SortOrder.ASCENDING,
                    )
                    showSortMenu = false
                },
            )
        }
    }
}
