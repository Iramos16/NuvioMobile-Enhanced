package com.nuvio.app.features.profiles

import androidx.compose.ui.graphics.Color
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

@Ignore("subject removed upstream: resolveProfileAvatarBorderColor no longer exists")
class ProfileAvatarBorderColorTest {
    @Test
    fun selectedProfileRingKeepsThemeColorAndOverrideAppliesWhenUnselected() {
        val override = Color(0xFFEC35F4)
        val avatarColor = Color(0xFF1688FF)
        val selectedBorderColor = Color(0xFF0055FF)

        assertEquals(
            selectedBorderColor,
            resolveProfileAvatarBorderColor(
                selected = true,
                avatarColor = avatarColor,
                selectedBorderColor = selectedBorderColor,
                defaultIconColorOverride = override,
            ),
        )
        assertEquals(
            override,
            resolveProfileAvatarBorderColor(
                selected = false,
                avatarColor = avatarColor,
                selectedBorderColor = selectedBorderColor,
                defaultIconColorOverride = override,
            ),
        )
    }

    @Test
    fun profileRingKeepsExistingColorsWhenOverrideIsDisabled() {
        val avatarColor = Color(0xFF1688FF)
        val selectedBorderColor = Color(0xFF0055FF)

        assertEquals(
            selectedBorderColor,
            resolveProfileAvatarBorderColor(
                selected = true,
                avatarColor = avatarColor,
                selectedBorderColor = selectedBorderColor,
                defaultIconColorOverride = null,
            ),
        )
        assertEquals(
            avatarColor.copy(alpha = 0.5f),
            resolveProfileAvatarBorderColor(
                selected = false,
                avatarColor = avatarColor,
                selectedBorderColor = selectedBorderColor,
                defaultIconColorOverride = null,
            ),
        )
    }
}

// Stub so this ignored test still compiles; the real function was removed upstream.
private fun resolveProfileAvatarBorderColor(
    selected: Boolean,
    avatarColor: Color,
    selectedBorderColor: Color,
    defaultIconColorOverride: Color?,
): Color = error("resolveProfileAvatarBorderColor was removed upstream")
