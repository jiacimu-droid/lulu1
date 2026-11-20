package com.jiacimu.lulu.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore

private val LocalLuluCssHeaderAtBottom = compositionLocalOf { false }
val LocalLuluCssFooterAtTop = compositionLocalOf { false }

@Composable
fun LuluCssPageScope(page: String, content: @Composable () -> Unit) {
    val sheet = LocalLuluCssSheet.current
    if (page == LocalLuluCssScope.current) {
        content()
    } else {
        LuluAppearanceTheme(LuluAppearanceCatalog.builtIns.first(), page, sheetOverride = sheet,
            content = content)
    }
}

/** Uses the system Scaffold for inset, snackbar, FAB and scroll behavior. */
@Composable
fun LuluCssScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    cssPage: String = LocalLuluCssScope.current,
    content: @Composable (PaddingValues) -> Unit,
) {
    LuluCssPageScope(cssPage) {
        val sheet = LocalLuluCssSheet.current
        val page = sheet.page(cssPage)
        val header = luluCssStyle("header")
        val footer = luluCssStyle("footer")
        val body = luluCssStyle("body")
        val floating = luluCssStyle("floating")
        val snackbar = luluCssStyle("snackbar")
        val frame = luluCssStyle("page")
        val hasImage = page.hasPageArtwork()
        val customFrameFill = frame.cssColor("background-color") != null || frame.hasPageArtwork()
        val actualColor = page.cssColor("--paper")
            ?: containerColor.takeIf { it != Color.Unspecified && (!hasImage || it != Color.Transparent) }
            ?: Color(LocalLuluAppearance.current.palette.paper.toInt())

        val headerAtBottom = header["dock"] == "bottom"
        val footerAtTop = footer["dock"] == "top"
        val headerBar: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLuluCssHeaderAtBottom provides headerAtBottom) {
                CssSlot(header, topBar)
            }
        }
        val footerBar: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLuluCssFooterAtTop provides footerAtTop) {
                CssSlot(footer, bottomBar)
            }
        }
        val pageScaffold: @Composable () -> Unit = {
            Scaffold(
                modifier = Modifier.luluCssDecor(frame).then(modifier),
                containerColor = if (hasImage || customFrameFill) Color.Transparent else actualColor,
                contentColor = contentColor.takeIf { it != Color.Unspecified }
                    ?: androidx.compose.material3.contentColorFor(actualColor),
                topBar = {
                    if (!footerAtTop) {
                        if (!headerAtBottom) headerBar()
                    }
                    else Column {
                        if (!headerAtBottom) headerBar()
                        footerBar()
                    }
                },
                bottomBar = {
                    if (!headerAtBottom) {
                        if (!footerAtTop) footerBar()
                    } else Column {
                        headerBar()
                        if (!footerAtTop) footerBar()
                    }
                },
                snackbarHost = { CssSlot(snackbar, snackbarHost) },
                floatingActionButton = { CssSlot(floating, floatingActionButton) },
                floatingActionButtonPosition = floatingActionButtonPosition,
                contentWindowInsets = contentWindowInsets,
            ) { insets ->
                if (body.isEmpty()) {
                    content(insets)
                } else {
                    // Keep the system insets outside the authored body, so shrinking it
                    // never makes the keyboard or bars consume its internal content area.
                    Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                        contentAlignment = body.cssAlignment(Alignment.TopStart)) {
                        LuluCssRegion(style = body, propagateMinConstraints = true) {
                            content(PaddingValues(0.dp))
                        }
                    }
                }
            }
        }
        if (hasImage || customFrameFill) {
            Box {
                Box(Modifier.matchParentSize().background(actualColor)) {
                    LuluCssBackground(Modifier.matchParentSize())
                }
                pageScaffold()
            }
        } else pageScaffold()
    }
}

@Composable
private fun CssSlot(style: Map<String, String>, content: @Composable () -> Unit) {
    if (style.keys.none { it != "dock" }) content()
    else LuluCssRegion(style) { content() }
}

private fun Map<String, String>.hasPageArtwork(): Boolean {
    val image = get("background-image")
    return LuluCssSheet.gradient(image) != null || LuluCssStore.assetFile(LuluCssSheet.assetToken(image)) != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluCssTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    centered: Boolean = false,
) {
    val sheet = LocalLuluCssSheet.current
    val scope = LocalLuluCssScope.current
    val page = sheet.page(scope)
    val header = luluCssStyle("header")
    val titleStyle = luluCssStyle("title")
    val navigationStyle = luluCssStyle("navigation")
    val actionsStyle = luluCssStyle("actions")
    val fill = if (header.cssColor("background-color") != null || header.hasPageArtwork() || page.hasPageArtwork()
    ) Color.Transparent else page.cssColor("--paper")
    val foreground = page.cssColor("--ink")
    val styledColors = colors.copy(
        containerColor = fill ?: Color.Unspecified,
        scrolledContainerColor = fill ?: Color.Unspecified,
        navigationIconContentColor = navigationStyle.cssColor("color") ?: foreground ?: Color.Unspecified,
        titleContentColor = titleStyle.cssColor("color") ?: foreground ?: Color.Unspecified,
        actionIconContentColor = actionsStyle.cssColor("color") ?: foreground ?: Color.Unspecified,
    )
    val actualInsets = if (LocalLuluCssHeaderAtBottom.current) WindowInsets(0, 0, 0, 0) else windowInsets
    val styledTitle: @Composable () -> Unit = { CssSlot(titleStyle, title) }
    val styledNavigation: @Composable () -> Unit = { CssSlot(navigationStyle, navigationIcon) }
    val styledActions: @Composable RowScope.() -> Unit = {
        if (actionsStyle.isEmpty()) actions()
        else LuluCssRegion(actionsStyle) { Row(content = actions) }
    }
    if (centered) CenterAlignedTopAppBar(
        title = styledTitle, modifier = modifier, navigationIcon = styledNavigation,
        actions = styledActions, windowInsets = actualInsets, colors = styledColors,
    ) else TopAppBar(
        title = styledTitle, modifier = modifier, navigationIcon = styledNavigation,
        actions = styledActions, windowInsets = actualInsets, colors = styledColors,
    )
}
