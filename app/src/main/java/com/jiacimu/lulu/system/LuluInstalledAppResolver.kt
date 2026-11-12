package com.jiacimu.lulu.system

import android.content.Context
import android.content.Intent

/**
 * Resolve the character's explicitly chosen app display name to a real installed
 * launchable package. This is a local identity lookup, not a second agent or
 * permission to choose an app on behalf of the character.
 *
 * Android package visibility is limited to MAIN/LAUNCHER in the manifest.
 * Sensitive and owner-excluded packages are never returned as candidates.
 */
internal object LuluInstalledAppResolver {
    data class LaunchableApp(val label: String, val packageName: String)

    /** Never guess between similarly named apps or silently pick a partial hit. */
    fun exactPackage(requestedName: String, candidates: List<LaunchableApp>): String {
        val name = requestedName.trim()
        require(name.isNotEmpty() && name.length <= 80) {
            "请提供已安装应用的准确显示名称（appName）或 packageName"
        }
        val matches = candidates.asSequence()
            .filter { it.label.trim().equals(name, ignoreCase = true) }
            .map { it.packageName }
            .distinct()
            .toList()
        return when (matches.size) {
            1 -> matches.single()
            0 -> error("本机没有找到显示名称为「${name.take(80)}」的可启动应用，请核实名称或指定包名")
            else -> error("本机存在多个同名可启动应用「${name.take(80)}」，请指定准确包名，不能猜测打开哪一个")
        }
    }

    fun resolve(context: Context, requestedName: String): String {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = manager.queryIntentActivities(launcher, 0).asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName?.trim().orEmpty()
                if (LuluScreenAwareness.isExcluded(context, pkg)) null
                else LaunchableApp(
                    label = info.loadLabel(manager)?.toString().orEmpty(),
                    packageName = pkg,
                )
            }
            .toList()
        return exactPackage(requestedName, candidates)
    }
}
