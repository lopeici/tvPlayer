package com.lopeici.tvplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Search field that is D-pad friendly on TV: focusing it only highlights it (primary border);
 * the field itself — and thus the on-screen keyboard — activates when the user presses select.
 * On touch devices it behaves like a plain [OutlinedTextField].
 */
@Composable
fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isTv = remember { context.isTelevision() }
    val fieldFocus = remember { FocusRequester() }
    var active by remember { mutableStateOf(false) }
    var wrapperFocused by remember { mutableStateOf(false) }

    // canFocus flips on activation; focus the field right after that recomposes.
    LaunchedEffect(active) { if (active) runCatching { fieldFocus.requestFocus() } }

    val wrapper = if (isTv) {
        Modifier
            .onFocusChanged { wrapperFocused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { active = true }
    } else {
        Modifier
    }

    Box(modifier.then(wrapper)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(placeholder) },
            trailingIcon = trailingIcon,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .onFocusChanged { if (!it.isFocused) active = false }
                .then(if (isTv) Modifier.focusProperties { canFocus = active } else Modifier)
                .then(
                    if (wrapperFocused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, OutlinedTextFieldDefaults.shape)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
