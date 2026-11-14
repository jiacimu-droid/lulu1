package com.jiacimu.lulu.system

import android.content.Context

/**
 * The owner's one role-level grant for autonomous phone/device operations.
 * The existing "screen" key is the source of truth, so installed users who
 * already granted virtual-screen control need no migration or second switch.
 * Android permissions, verified targets, pause/revocation and actual results
 * remain independent runtime facts, not additional per-action owner toggles.
 */
internal object LuluRolePhoneAuthorization {
    private const val PREFS = "lulu_execution_policy"

    fun isGranted(context: Context, characterId: String): Boolean =
        characterId.isNotBlank() && !characterId.startsWith("resident:") &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("$characterId:screen", false)

    fun setGranted(context: Context, characterId: String, enabled: Boolean): Boolean {
        if (characterId.isBlank() || characterId.startsWith("resident:")) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("$characterId:screen", enabled).commit()
    }
}
