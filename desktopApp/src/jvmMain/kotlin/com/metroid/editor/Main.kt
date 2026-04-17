package com.metroid.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metroid.editor.data.Area
import com.metroid.editor.data.MetEditProject
import com.metroid.editor.ui.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
    val editorState = remember { EditorState() }

    Window(
        onCloseRequest = ::exitApplication,
        title = editorState.title,
        state = windowState,
        icon = painterResource("app_icon.png"),
        onPreviewKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val mod = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                when {
                    mod && keyEvent.isShiftPressed && keyEvent.key == Key.S -> {
                        showSaveProjectDialog(null, editorState); true
                    }
                    mod && keyEvent.key == Key.S -> {
                        if (editorState.hasProject) editorState.saveProject()
                        else showSaveProjectDialog(null, editorState)
                        true
                    }
                    mod && keyEvent.key == Key.O -> {
                        showOpenRomDialog(null, editorState); true
                    }
                    mod && keyEvent.isShiftPressed && keyEvent.key == Key.Z -> {
                        editorState.redo(); true
                    }
                    mod && keyEvent.key == Key.Z -> {
                        editorState.undo(); true
                    }
                    mod && keyEvent.key == Key.E -> {
                        if (editorState.isRomLoaded) showExportRomDialog(null, editorState)
                        true
                    }
                    else -> false
                }
            } else false
        },
    ) {
        val awtWindow = this.window

        LaunchedEffect(Unit) {
            editorState.autoLoadLastSession()

            awtWindow.dropTarget = DropTarget().apply {
                addDropTargetListener(object : DropTargetAdapter() {
                    override fun drop(dtde: DropTargetDropEvent) {
                        try {
                            dtde.acceptDrop(DnDConstants.ACTION_COPY)
                            val transferable = dtde.transferable
                            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                @Suppress("UNCHECKED_CAST")
                                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                                val nesFile = files.firstOrNull { it.extension.equals("nes", ignoreCase = true) }
                                val meditFile = files.firstOrNull { it.extension.equals(MetEditProject.EXTENSION, ignoreCase = true) }
                                if (meditFile != null) {
                                    editorState.loadProject(meditFile)
                                } else if (nesFile != null) {
                                    editorState.loadRom(nesFile)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            dtde.dropComplete(true)
                        }
                    }
                })
            }
        }

        MetroidEditorApp(
            editorState = editorState,
            parentWindow = awtWindow
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroidEditorApp(
    editorState: EditorState,
    parentWindow: java.awt.Window
) {
    val T = EditorTheme

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.accent,
            surface = T.surface,
            background = T.background,
            onSurface = T.textPrimary,
            onBackground = T.textPrimary,
            surfaceVariant = T.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(T.background)
        ) {
            // Top bar
            TopAppBar(
                title = {
                    Text(editorState.title, color = T.textPrimary)
                },
                actions = {
                    TextButton(onClick = { showOpenRomDialog(parentWindow, editorState) }) {
                        Text("Open ROM", fontSize = 12.sp, color = T.actionPrimary)
                    }

                    if (editorState.isRomLoaded) {
                        TextButton(onClick = { editorState.undo() }, enabled = editorState.canUndo) {
                            Text("Undo", fontSize = 12.sp,
                                color = if (editorState.canUndo) T.actionPrimary else T.actionDisabled)
                        }
                        TextButton(onClick = { editorState.redo() }, enabled = editorState.canRedo) {
                            Text("Redo", fontSize = 12.sp,
                                color = if (editorState.canRedo) T.actionPrimary else T.actionDisabled)
                        }

                        Spacer(Modifier.width(4.dp))
                        ToolButton(editorState, EditorTool.PAINT, "Paint", T.accent)
                        ToolButton(editorState, EditorTool.ERASE, "Erase", T.errorRed)
                        ToolButton(editorState, EditorTool.SAMPLE, "Sample", T.sampleGreen)

                        Spacer(Modifier.width(8.dp))

                        TextButton(onClick = {
                            if (editorState.hasProject) editorState.saveProject()
                            else showSaveProjectDialog(parentWindow, editorState)
                        }) {
                            Text("Save", fontSize = 12.sp, color = T.actionPrimary)
                        }
                        TextButton(onClick = { showSaveProjectDialog(parentWindow, editorState) }) {
                            Text("Save As", fontSize = 12.sp, color = T.actionPrimary)
                        }
                        TextButton(onClick = { showOpenProjectDialog(parentWindow, editorState) }) {
                            Text("Open Project", fontSize = 12.sp, color = T.actionPrimary)
                        }
                        TextButton(onClick = { showExportRomDialog(parentWindow, editorState) }) {
                            Text("Export ROM", fontSize = 12.sp, color = T.exportGreen)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = T.panelHeader
                )
            )

            // Area tabs
            if (editorState.isRomLoaded) {
                ScrollableTabRow(
                    selectedTabIndex = Area.entries.indexOf(editorState.selectedArea),
                    containerColor = T.tabBar,
                    contentColor = T.textPrimary,
                    edgePadding = 8.dp
                ) {
                    Area.entries.forEach { area ->
                        Tab(
                            selected = area == editorState.selectedArea,
                            onClick = { editorState.switchArea(area) },
                            text = {
                                Text(
                                    area.displayName,
                                    color = if (area == editorState.selectedArea) T.tabSelected
                                    else T.tabUnselected.copy(alpha = 0.7f)
                                )
                            }
                        )
                    }
                }
            }

            // Main content
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left panel: room list + tile palette
                if (editorState.isRomLoaded) {
                    Column(modifier = Modifier.width(220.dp).fillMaxHeight()) {
                        RoomListPanel(
                            rooms = editorState.rooms,
                            selectedRoom = editorState.selectedRoom,
                            onRoomSelected = { editorState.selectRoom(it) },
                            modifier = Modifier.weight(0.45f).fillMaxWidth()
                        )
                        Divider(color = T.divider, thickness = 1.dp)
                        TilePalettePanel(
                            editorState = editorState,
                            modifier = Modifier.weight(0.55f).fillMaxWidth()
                        )
                    }
                }

                // Center: map viewer
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(T.mapBackground)
                ) {
                    val room = editorState.selectedRoom
                    val renderer = editorState.mapRenderer
                    if (room != null && renderer != null) {
                        MapViewer(
                            room = room,
                            renderer = renderer,
                            editorState = editorState,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!editorState.isRomLoaded) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Open a Metroid NES ROM to begin",
                                color = T.textMuted,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Drag .nes / .medit file here, or use Open ROM button",
                                color = T.textDim,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Right panel: room info
                val room = editorState.selectedRoom
                val md = editorState.metroidData
                if (room != null && md != null) {
                    RoomInfoPanel(
                        room = room,
                        metroidData = md,
                        spaceBudget = editorState.spaceBudget,
                        modifier = Modifier.width(260.dp).fillMaxHeight()
                    )
                }
            }

            // Status bar
            Surface(
                color = T.statusBar,
                modifier = Modifier.fillMaxWidth().height(32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        editorState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = T.textSecondary.copy(alpha = 0.6f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editorState.isRomLoaded) {
                            ToggleChip("Grid", editorState.showGrid) { editorState.showGrid = it }
                            ToggleChip("BG", editorState.showCoverage) { editorState.showCoverage = it }
                            ToggleChip("Enemies", editorState.showEnemies) { editorState.showEnemies = it }
                            ToggleChip("Doors", editorState.showDoors) { editorState.showDoors = it }

                            val macroIdx = editorState.selectedMacroIndex
                            if (macroIdx >= 0) {
                                Text(
                                    "Macro #${"$%02X".format(macroIdx)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = T.accent.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolButton(editorState: EditorState, tool: EditorTool, label: String, activeColor: Color) {
    val T = EditorTheme
    val isActive = editorState.activeTool == tool
    Surface(
        color = if (isActive) activeColor.copy(alpha = 0.2f) else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .clickable { editorState.activeTool = tool }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (isActive) activeColor else T.textMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ToggleChip(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val T = EditorTheme
    Surface(
        color = if (enabled) T.toggleActive else Color.Transparent,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.clickable { onToggle(!enabled) }
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) T.textSecondary else T.textDim,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// -- File dialogs --

private fun showOpenRomDialog(window: java.awt.Window?, editorState: EditorState) {
    val dialog = java.awt.FileDialog(window as? java.awt.Frame, "Open Metroid NES ROM", java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".nes", ignoreCase = true) }
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir != null && file != null) {
        editorState.loadRom(File(dir, file))
    }
}

private fun showOpenProjectDialog(window: java.awt.Window?, editorState: EditorState) {
    val dialog = java.awt.FileDialog(window as? java.awt.Frame, "Open Project", java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".${MetEditProject.EXTENSION}", ignoreCase = true) }
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir != null && file != null) {
        editorState.loadProject(File(dir, file))
    }
}

private fun showSaveProjectDialog(window: java.awt.Window?, editorState: EditorState) {
    val dialog = java.awt.FileDialog(window as? java.awt.Frame, "Save Project", java.awt.FileDialog.SAVE)
    dialog.file = editorState.romFileName.replace(".nes", ".${MetEditProject.EXTENSION}")
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir != null && file != null) {
        var target = File(dir, file)
        if (!target.name.endsWith(".${MetEditProject.EXTENSION}")) {
            target = File(dir, file + ".${MetEditProject.EXTENSION}")
        }
        editorState.saveProject(target)
    }
}

private fun showExportRomDialog(window: java.awt.Window?, editorState: EditorState) {
    val dialog = java.awt.FileDialog(window as? java.awt.Frame, "Export ROM", java.awt.FileDialog.SAVE)
    dialog.file = editorState.romFileName.replace(".nes", "_edited.nes")
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir != null && file != null) {
        var target = File(dir, file)
        if (!target.name.endsWith(".nes")) {
            target = File(dir, "$file.nes")
        }
        editorState.exportRom(target)
    }
}
