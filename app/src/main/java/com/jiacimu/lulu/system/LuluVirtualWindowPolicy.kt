package com.jiacimu.lulu.system

/** The Mika AppSwitch classifier is used ONLY to explain a window switch:
 * Lulu never moves another App's task onto a different display automatically. */
internal object LuluVirtualWindowPolicy {
    const val TYPE_APPLICATION = 1
    const val TYPE_SYSTEM = 3

    enum class Kind { TARGET, SYSTEM_DIALOG, OTHER_APP, UNKNOWN }

    fun classify(targetPackage: String, actualPackage: String?, windowType: Int?): Kind {
        if (targetPackage.isBlank() || windowType == null) return Kind.UNKNOWN
        // A TYPE_SYSTEM layer still blocks the App if its root is unreadable.
        if (windowType != TYPE_APPLICATION) return Kind.SYSTEM_DIALOG
        if (actualPackage.isNullOrBlank()) return Kind.UNKNOWN
        if (actualPackage == targetPackage) return Kind.TARGET
        return if (LuluMikaAppSwitch.switchedTo(
                target = targetPackage,
                current = actualPackage,
                sameTask = { false }, // TaskManager ownership has not been proven.
                partOfTask = emptySet(),
                settingsPage = { false },
            ) != null) Kind.OTHER_APP else Kind.UNKNOWN
    }

    fun pausesAutomation(kind: Kind): Boolean = kind != Kind.TARGET
}
