package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBorderLight
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.EditorialGlassBorder
import com.example.ui.theme.EditorialGlassSurface
import com.example.ui.theme.InputBackgroundWhite
import com.example.ui.theme.TextPrimaryLight

/**
 * High-Contrast Editorial Pure White Card Surface (28dp rounded corners, crisp shadow).
 */
@Composable
fun WhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, CardBorderLight, RoundedCornerShape(28.dp)),
        color = CardSurfaceWhite,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

/**
 * Editorial Glass Translucent Dark Card for dark background items.
 */
@Composable
fun EditorialGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, EditorialGlassBorder, RoundedCornerShape(24.dp)),
        color = EditorialGlassSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * High-Contrast Editorial Input Field with bold uppercase label style.
 */
@Composable
fun WhiteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0x99000000),
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color(0xA071717A), fontSize = 13.sp) },
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = InputBackgroundWhite,
                unfocusedContainerColor = InputBackgroundWhite,
                focusedBorderColor = Color(0xFF09090B),
                unfocusedBorderColor = CardBorderLight,
                focusedTextColor = TextPrimaryLight,
                unfocusedTextColor = TextPrimaryLight
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
