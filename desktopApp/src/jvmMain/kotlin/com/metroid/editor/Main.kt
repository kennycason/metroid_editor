package com.metroid.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        state = windowState
    ) {
        val awtWindow = this.window

        LaunchedEffect(Unit) {
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
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8E6FFF),
            surface = Color(0xFF1A1A2E),
            background = Color(0xFF0F0F1A),
            onSurface = Color(0xFFE0E0E0),
            onBackground = Color(0xFFE0E0E0),
            surfaceVariant = Color(0xFF252540)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top bar with menu actions
            TopAppBar(
                title = {
                    Text(
                        editorState.title,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    TextButton(onClick = { showOpenRomDialog(parentWindow, editorState) }) {
                        Text("Open ROM", fontSize = 12.sp, color = Color(0xFFA0A0C0))
                    }

                    if (editorState.isRomLoaded) {
                        // Undo/Redo
                        TextButton(
                            onClick = { editorState.undo() },
                            enabled = editorState.canUndo
                        ) {
                            Text("Undo", fontSize = 12.sp,
                                color = if (editorState.canUndo) Color(0xFFA0A0C0) else Color(0xFF505060))
                        }
                        TextButton(
                            onClick = { editorState.redo() },
                            enabled = editorState.canRedo
                        ) {
                            Text("Redo", fontSize = 12.sp,
                                color = if (editorState.canRedo) Color(0xFFA0A0C0) else Color(0xFF505060))
                        }

                        // Tool selector
                        Spacer(Modifier.width(4.dp))
                        ToolButton(editorState, EditorTool.PAINT, "Paint", Color(0xFF8E6FFF))
                        ToolButton(editorState, EditorTool.ERASE, "Erase", Color(0xFFFF6666))
                        ToolButton(editorState, EditorTool.SAMPLE, "Sample", Color(0xFF66FF66))

                        Spacer(Modifier.width(8.dp))

                        TextButton(onClick = {
                            if (editorState.hasProject) editorState.saveProject()
                            else showSaveProjectDialog(parentWindow, editorState)
                        }) {
                            Text("Save", fontSize = 12.sp, color = Color(0xFFA0A0C0))
                        }
                        TextButton(onClick = { showSaveProjectDialog(parentWindow, editorState) }) {
                            Text("Save As", fontSize = 12.sp, color = Color(0xFFA0A0C0))
                        }
                        TextButton(onClick = { showOpenProjectDialog(parentWindow, editorState) }) {
                            Text("Open Project", fontSize = 12.sp, color = Color(0xFFA0A0C0))
                        }
                        TextButton(onClick = { showExportRomDialog(parentWindow, editorState) }) {
                            Text("Export ROM", fontSize = 12.sp, color = Color(0xFF80CC80))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16162B)
                )
            )

            // Area tabs
            if (editorState.isRomLoaded) {
                ScrollableTabRow(
                    selectedTabIndex = Area.entries.indexOf(editorState.selectedArea),
                    containerColor = Color(0xFF1E1E38),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    edgePadding = 8.dp
                ) {
                    Area.entries.forEach { area ->
                        Tab(
                            selected = area == editorState.selectedArea,
                            onClick = { editorState.switchArea(area) },
                            text = {
                                Text(
                                    area.displayName,
                                    color = if (area == editorState.selectedArea) Color(0xFF8E6FFF)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        )
                    }
                }
            }

            // Main content
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left panel: room list (top) + tile palette (bottom)
                if (editorState.isRomLoaded) {
                    Column(modifier = Modifier.width(220.dp).fillMaxHeight()) {
                        RoomListPanel(
                            rooms = editorState.rooms,
                            selectedRoom = editorState.selectedRoom,
                            onRoomSelected = { editorState.selectRoom(it) },
                            modifier = Modifier.weight(0.45f).fillMaxWidth()
                        )
                        Divider(color = Color(0xFF303050), thickness = 1.dp)
                        TilePalettePanel(
                            editorState = editorState,
                            modifier = Modifier.weight(0.55f).fillMaxWidth()
                        )
                    }
                }

                // Center: map viewer
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(Color(0xFF0A0A14))
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
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Drag .nes / .medit file here, or use Open ROM button",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
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
                        modifier = Modifier.width(260.dp).fillMaxHeight()
                    )
                }
            }

            // Status bar
            Surface(
                color = Color(0xFF12122A),
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Overlay toggles
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
                                    color = Color(0xFF8E6FFF).copy(alpha = 0.7f)
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
            color = if (isActive) activeColor else Color(0xFF606080),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ToggleChip(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        color = if (enabled) Color(0xFF2A2A50) else Color.Transparent,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.clickable { onToggle(!enabled) }
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) Color(0xFFA0A0C0) else Color(0xFF404060),
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
