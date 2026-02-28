package com.metroid.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metroid.editor.data.Area
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MapRenderer
import com.metroid.editor.rom.MetroidRomData
import com.metroid.editor.rom.NesPatternDecoder
import com.metroid.editor.rom.NesRomParser
import com.metroid.editor.ui.MapViewer
import com.metroid.editor.ui.RoomListPanel
import com.metroid.editor.ui.RoomInfoPanel
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metroid NES Map Viewer",
        state = windowState
    ) {
        val awtWindow = this.window
        var onFileDrop by remember { mutableStateOf<((File) -> Unit)?>(null) }

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
                                if (nesFile != null) {
                                    onFileDrop?.invoke(nesFile)
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
            parentWindow = awtWindow,
            registerDropHandler = { handler -> onFileDrop = handler }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroidEditorApp(
    parentWindow: java.awt.Window,
    registerDropHandler: ((File) -> Unit) -> Unit
) {
    var romParser by remember { mutableStateOf<NesRomParser?>(null) }
    var metroidData by remember { mutableStateOf<MetroidRomData?>(null) }
    var mapRenderer by remember { mutableStateOf<MapRenderer?>(null) }
    var selectedArea by remember { mutableStateOf(Area.BRINSTAR) }
    var selectedRoom by remember { mutableStateOf<Room?>(null) }
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("No ROM loaded") }
    var romFileName by remember { mutableStateOf("") }

    fun loadRom(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) {
                statusMessage = "File is empty or does not exist: ${file.name}"
                return
            }
            val rawData = file.readBytes()
            val data = NesRomParser.ensureHeader(rawData)
            val wasHeaderless = data.size > rawData.size
            val parser = NesRomParser(data)
            if (!parser.isMetroidRom()) {
                statusMessage = "Not a valid Metroid NES ROM — expected 8×16KB PRG, Mapper 1 or 4"
                return
            }
            romParser = parser
            val md = MetroidRomData(parser)
            metroidData = md
            val pd = NesPatternDecoder(parser)
            mapRenderer = MapRenderer(md, pd, parser)
            romFileName = file.name
            val headerNote = if (wasHeaderless) " (headerless, iNES header added)" else ""
            statusMessage = "Loaded: ${file.name}$headerNote | ${parser.header.prgBanks}×16KB PRG, Mapper ${parser.mapper}"

            rooms = md.readAllRooms(selectedArea)
            selectedRoom = rooms.firstOrNull()
        } catch (e: Exception) {
            statusMessage = "Error loading ROM: ${e.message}"
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        registerDropHandler { file -> loadRom(file) }
    }

    fun switchArea(area: Area) {
        selectedArea = area
        metroidData?.let { md ->
            rooms = md.readAllRooms(area)
            selectedRoom = rooms.firstOrNull()
        }
    }

    fun openFileDialog() {
        val dialog = java.awt.FileDialog(parentWindow as? java.awt.Frame, "Open Metroid NES ROM", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".nes", ignoreCase = true) }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) {
            loadRom(File(dir, file))
        }
    }

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
            // Top bar
            TopAppBar(
                title = {
                    Text(
                        if (romFileName.isNotEmpty()) "Metroid NES Map Viewer — $romFileName"
                        else "Metroid NES Map Viewer",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    Button(
                        onClick = { openFileDialog() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6E4FCC)
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Open ROM...")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16162B)
                )
            )

            // Area tabs
            if (romParser != null) {
                ScrollableTabRow(
                    selectedTabIndex = Area.entries.indexOf(selectedArea),
                    containerColor = Color(0xFF1E1E38),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    edgePadding = 8.dp
                ) {
                    Area.entries.forEach { area ->
                        Tab(
                            selected = area == selectedArea,
                            onClick = { switchArea(area) },
                            text = {
                                Text(
                                    area.displayName,
                                    color = if (area == selectedArea) Color(0xFF8E6FFF)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        )
                    }
                }
            }

            // Main content
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left panel: room list
                if (romParser != null) {
                    RoomListPanel(
                        rooms = rooms,
                        selectedRoom = selectedRoom,
                        onRoomSelected = { selectedRoom = it },
                        modifier = Modifier.width(220.dp).fillMaxHeight()
                    )
                }

                // Center: map viewer
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(Color(0xFF0A0A14))
                ) {
                    if (selectedRoom != null && mapRenderer != null) {
                        MapViewer(
                            room = selectedRoom!!,
                            renderer = mapRenderer!!,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (romParser == null) {
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
                                "File → Open ROM... or drag a .nes file",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Right panel: room info
                if (selectedRoom != null) {
                    RoomInfoPanel(
                        room = selectedRoom!!,
                        metroidData = metroidData!!,
                        modifier = Modifier.width(260.dp).fillMaxHeight()
                    )
                }
            }

            // Status bar
            Surface(
                color = Color(0xFF12122A),
                modifier = Modifier.fillMaxWidth().height(28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
