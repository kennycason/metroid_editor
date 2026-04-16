package com.metroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metroid.editor.data.*
import com.metroid.editor.data.RomPreferences
import com.metroid.editor.rom.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

enum class EditorTool(val label: String, val icon: String) {
    PAINT("Paint", "P"),
    ERASE("Erase", "E"),
    SAMPLE("Sample", "S")
}

class EditorState {
    // ROM state
    var romParser by mutableStateOf<NesRomParser?>(null); private set
    var metroidData by mutableStateOf<MetroidRomData?>(null); private set
    var patternDecoder by mutableStateOf<NesPatternDecoder?>(null); private set
    var mapRenderer by mutableStateOf<MapRenderer?>(null); private set
    var romFile by mutableStateOf<File?>(null); private set
    var romFileName by mutableStateOf(""); private set

    // Project state
    var project by mutableStateOf(MetEditProject()); private set
    var projectFile by mutableStateOf<File?>(null); private set
    var dirty by mutableStateOf(false); private set

    // UI navigation
    var selectedArea by mutableStateOf(Area.BRINSTAR)
    var selectedRoom by mutableStateOf<Room?>(null)
    var rooms by mutableStateOf<List<Room>>(emptyList()); private set
    var statusMessage by mutableStateOf("No ROM loaded")

    // Tool
    var activeTool by mutableStateOf(EditorTool.PAINT)

    // Working room grid (editable)
    var workingGrid by mutableStateOf<MapRenderer.MacroGrid?>(null); private set
    var originalGrid by mutableStateOf<MapRenderer.MacroGrid?>(null); private set

    // Undo/redo
    data class StrokeEdit(val x: Int, val y: Int, val oldMacro: Int, val newMacro: Int, val oldAttr: Int, val newAttr: Int)
    data class Stroke(val edits: List<StrokeEdit>, val description: String = "")

    val undoStack = mutableListOf<Stroke>()
    val redoStack = mutableListOf<Stroke>()
    var undoVersion by mutableStateOf(0); private set
    var editVersion by mutableStateOf(0); private set

    // Current stroke
    private val pendingEdits = mutableListOf<StrokeEdit>()
    private val pendingPositions = mutableSetOf<Long>()
    var isPainting by mutableStateOf(false); private set

    // Tile palette
    var tilePaletteImage by mutableStateOf<IntArray?>(null); private set
    var tilePaletteWidth by mutableStateOf(0); private set
    var tilePaletteHeight by mutableStateOf(0); private set
    var selectedMacroIndex by mutableStateOf(-1)
    var selectedSubPalette by mutableStateOf(0)

    // Coverage map — true means position has ROM structure backing (exportable)
    var coverageMap by mutableStateOf<BooleanArray?>(null); private set

    // Space budget for the current area
    var spaceBudget by mutableStateOf<RoomEncoder.SpaceBudget?>(null); private set

    // Overlay toggles
    var showEnemies by mutableStateOf(true)
    var showDoors by mutableStateOf(true)
    var showGrid by mutableStateOf(true)
    var showCoverage by mutableStateOf(true)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val isRomLoaded: Boolean get() = romParser != null
    val hasProject: Boolean get() = projectFile != null
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val title: String
        get() {
            val dirtyMark = if (dirty) " *" else ""
            val projectName = projectFile?.nameWithoutExtension
                ?: romFileName.ifEmpty { null }
            return if (projectName != null) "Metroid NES Editor — $projectName$dirtyMark"
            else "Metroid NES Editor"
        }

    // -- ROM loading --

    fun loadRom(file: File): Boolean {
        try {
            logger.info { "loadRom: ${file.absolutePath} (${file.length()} bytes)" }
            if (!file.exists() || file.length() == 0L) {
                statusMessage = "File is empty or does not exist: ${file.name}"
                return false
            }
            val rawData = file.readBytes()
            val data = NesRomParser.ensureHeader(rawData)
            val wasHeaderless = data.size > rawData.size
            val parser = NesRomParser(data)
            if (!parser.isMetroidRom()) {
                statusMessage = "Not a valid Metroid NES ROM"
                return false
            }

            // Clear all editor state BEFORE loading new data —
            // prevents saveCurrentRoomEdits() from leaking old edits into the new project
            workingGrid = null
            originalGrid = null
            coverageMap = null
            selectedRoom = null
            undoStack.clear()
            redoStack.clear()
            undoVersion++

            romParser = parser
            val md = MetroidRomData(parser)
            metroidData = md
            val pd = NesPatternDecoder(parser)
            patternDecoder = pd
            mapRenderer = MapRenderer(md, pd)
            romFile = file
            romFileName = file.name
            projectFile = null  // Clear old project reference so title updates
            RomPreferences.setLastRomPath(file.absolutePath)

            val headerNote = if (wasHeaderless) " (headerless, iNES header added)" else ""
            statusMessage = "Loaded: ${file.name}$headerNote | ${parser.header.prgBanks}×16KB PRG, Mapper ${parser.mapper}"

            project = MetEditProject(romPath = file.absolutePath)
            dirty = false
            switchArea(selectedArea)
            return true
        } catch (e: Exception) {
            statusMessage = "Error loading ROM: ${e.message}"
            e.printStackTrace()
            return false
        }
    }

    // -- Navigation --

    fun switchArea(area: Area) {
        saveCurrentRoomEdits()
        selectedArea = area
        metroidData?.let { md ->
            rooms = md.readAllRooms(area)
            selectedRoom = rooms.firstOrNull()
            rebuildTilePalette()
            selectRoom(selectedRoom)
            recalcBudget()
        }
    }

    fun selectRoom(room: Room?) {
        saveCurrentRoomEdits()
        selectedRoom = room
        undoStack.clear()
        redoStack.clear()
        undoVersion++
        if (room != null) {
            val md = metroidData ?: return
            val renderer = mapRenderer ?: return
            val grid = renderer.buildMacroGrid(room)
            originalGrid = grid.copy()
            workingGrid = grid
            coverageMap = RomExporter.buildCoverageMap(md, room)
            restoreRoomEdits(room)
            editVersion++
        } else {
            originalGrid = null
            workingGrid = null
            coverageMap = null
        }
    }

    // -- Painting --

    fun beginStroke() {
        isPainting = true
        pendingEdits.clear()
        pendingPositions.clear()
    }

    fun paintAt(macroX: Int, macroY: Int) {
        val grid = workingGrid ?: return
        if (macroX !in 0 until MapRenderer.ROOM_WIDTH_MACROS) return
        if (macroY !in 0 until MapRenderer.ROOM_HEIGHT_MACROS) return

        val posKey = macroX.toLong() or (macroY.toLong() shl 32)
        if (posKey in pendingPositions) return
        pendingPositions.add(posKey)

        val oldMacro = grid.get(macroX, macroY)
        val oldAttr = grid.getAttr(macroX, macroY)

        when (activeTool) {
            EditorTool.PAINT -> {
                val macroIdx = selectedMacroIndex
                if (macroIdx < 0) return
                val newAttr = selectedSubPalette
                if (oldMacro == macroIdx && oldAttr == newAttr) return
                grid.set(macroX, macroY, macroIdx)
                grid.setAttr(macroX, macroY, newAttr)
                pendingEdits.add(StrokeEdit(macroX, macroY, oldMacro, macroIdx, oldAttr, newAttr))
            }
            EditorTool.ERASE -> {
                val orig = originalGrid ?: return
                val origMacro = orig.get(macroX, macroY)
                val origAttr = orig.getAttr(macroX, macroY)
                if (oldMacro == origMacro && oldAttr == origAttr) return
                grid.set(macroX, macroY, origMacro)
                grid.setAttr(macroX, macroY, origAttr)
                pendingEdits.add(StrokeEdit(macroX, macroY, oldMacro, origMacro, oldAttr, origAttr))
            }
            EditorTool.SAMPLE -> {
                sampleAt(macroX, macroY)
                return
            }
        }

        workingGrid = grid
        editVersion++
    }

    fun endStroke() {
        isPainting = false
        if (pendingEdits.isNotEmpty()) {
            val desc = when (activeTool) {
                EditorTool.PAINT -> "Paint ${pendingEdits.size} macros"
                EditorTool.ERASE -> "Erase ${pendingEdits.size} macros"
                EditorTool.SAMPLE -> "Sample"
            }
            undoStack.add(Stroke(pendingEdits.toList(), desc))
            redoStack.clear()
            undoVersion++
            dirty = true
            recalcBudget()
        }
        pendingEdits.clear()
        pendingPositions.clear()
    }

    fun undo() {
        val grid = workingGrid ?: return
        val stroke = undoStack.removeLastOrNull() ?: return
        for (edit in stroke.edits.reversed()) {
            grid.set(edit.x, edit.y, edit.oldMacro)
            grid.setAttr(edit.x, edit.y, edit.oldAttr)
        }
        redoStack.add(stroke)
        workingGrid = grid
        editVersion++
        undoVersion++
        dirty = hasGridChanges()
        recalcBudget()
    }

    fun redo() {
        val grid = workingGrid ?: return
        val stroke = redoStack.removeLastOrNull() ?: return
        for (edit in stroke.edits) {
            grid.set(edit.x, edit.y, edit.newMacro)
            grid.setAttr(edit.x, edit.y, edit.newAttr)
        }
        undoStack.add(stroke)
        workingGrid = grid
        editVersion++
        undoVersion++
        dirty = true
        recalcBudget()
    }

    fun sampleAt(macroX: Int, macroY: Int) {
        val grid = workingGrid ?: return
        val macroIdx = grid.get(macroX, macroY)
        if (macroIdx >= 0) {
            selectedMacroIndex = macroIdx
            selectedSubPalette = grid.getAttr(macroX, macroY) and 0x03
            activeTool = EditorTool.PAINT
        }
    }

    private fun hasGridChanges(): Boolean {
        val wg = workingGrid ?: return false
        val og = originalGrid ?: return false
        return !wg.macros.contentEquals(og.macros) || !wg.attrs.contentEquals(og.attrs)
    }

    // -- Persist room edits to/from project --

    private fun saveCurrentRoomEdits() {
        val room = selectedRoom ?: return
        val wg = workingGrid ?: return
        val og = originalGrid ?: return

        val edits = mutableListOf<MacroEdit>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val origMacro = og.get(mx, my)
                val origAttr = og.getAttr(mx, my)
                val curMacro = wg.get(mx, my)
                val curAttr = wg.getAttr(mx, my)
                if (origMacro != curMacro || origAttr != curAttr) {
                    edits.add(MacroEdit(mx, my, curMacro, curAttr))
                }
            }
        }

        val key = roomKey(room.area, room.roomNumber)
        if (edits.isNotEmpty()) {
            val re = project.rooms.getOrPut(key) { RoomEdits() }
            re.macroEdits.clear()
            re.macroEdits.addAll(edits)
        } else {
            project.rooms.remove(key)
        }
    }

    private fun restoreRoomEdits(room: Room) {
        val key = roomKey(room.area, room.roomNumber)
        val re = project.rooms[key] ?: return
        val grid = workingGrid ?: return

        for (edit in re.macroEdits) {
            grid.set(edit.x, edit.y, edit.macroIndex)
            grid.setAttr(edit.x, edit.y, edit.palette)
        }
        workingGrid = grid
        if (re.macroEdits.isNotEmpty()) dirty = true
    }

    // -- Tile palette --

    fun rebuildTilePalette() {
        val pd = patternDecoder ?: return
        val md = metroidData ?: return
        val area = selectedArea
        val bankNumber = MetroidRomData.AREA_BANKS[area] ?: return

        val chrRam = pd.buildChrRam(area)
        val fullPalette = pd.readAreaPalette(bankNumber, 0)

        val tileSize = NesPatternDecoder.TILE_SIZE
        val macroSize = tileSize * 2

        val tilesPerRow = 16
        val tileRows = 16

        val macros = md.readAllMacros(area)
        val macrosPerRow = 8
        val macroRows = (macros.size + macrosPerRow - 1) / macrosPerRow

        val tileGridH = tileRows * tileSize
        val macroGridH = macroRows * macroSize
        val separatorH = 2
        val totalWidth = tilesPerRow * tileSize
        val totalHeight = tileGridH + separatorH + macroGridH

        val pixels = IntArray(totalWidth * totalHeight)
        pixels.fill(fullPalette[0])

        val subPal = pd.getSubPalette(fullPalette, selectedSubPalette and 0x03)

        for (tileIdx in 0 until 256) {
            val col = tileIdx % tilesPerRow
            val row = tileIdx / tilesPerRow
            val tilePixels = pd.decodeBgTileFromChrRam(chrRam, tileIdx)
            val rendered = pd.renderTile(tilePixels, subPal)
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    val dx = col * tileSize + px
                    val dy = row * tileSize + py
                    val pixel = rendered[py * tileSize + px]
                    if (pixel != 0x00000000) pixels[dy * totalWidth + dx] = pixel
                }
            }
        }

        val sepY = tileGridH
        for (x in 0 until totalWidth) {
            pixels[sepY * totalWidth + x] = 0xFF404060.toInt()
            pixels[(sepY + 1) * totalWidth + x] = 0xFF404060.toInt()
        }

        val macroStartY = tileGridH + separatorH
        for (mIdx in macros.indices) {
            val col = mIdx % macrosPerRow
            val row = mIdx / macrosPerRow
            val macro = macros[mIdx]
            val tiles = intArrayOf(macro.topLeft, macro.topRight, macro.botLeft, macro.botRight)
            for (ti in 0 until 4) {
                val tileCol = ti % 2
                val tileRow = ti / 2
                val tilePixels = pd.decodeBgTileFromChrRam(chrRam, tiles[ti])
                val rendered = pd.renderTile(tilePixels, subPal)
                for (py in 0 until tileSize) {
                    for (px in 0 until tileSize) {
                        val dx = col * macroSize + tileCol * tileSize + px
                        val dy = macroStartY + row * macroSize + tileRow * tileSize + py
                        if (dx < totalWidth && dy < totalHeight) {
                            val pixel = rendered[py * tileSize + px]
                            if (pixel != 0x00000000) pixels[dy * totalWidth + dx] = pixel
                        }
                    }
                }
            }
        }

        tilePaletteImage = pixels
        tilePaletteWidth = totalWidth
        tilePaletteHeight = totalHeight
    }

    // -- Project save/load --

    fun saveProject(file: File? = null) {
        val target = file ?: projectFile ?: return
        try {
            saveCurrentRoomEdits()
            val updated = project.copy(
                lastArea = selectedArea.id,
                lastRoom = selectedRoom?.roomNumber ?: -1
            )
            val jsonStr = json.encodeToString(MetEditProject.serializer(), updated)
            target.writeText(jsonStr)
            project = updated
            projectFile = target
            dirty = false
            RomPreferences.setLastProjectPath(target.absolutePath)
            val editCount = project.rooms.values.sumOf { it.macroEdits.size }
            statusMessage = "Saved: ${target.name} ($editCount macro edits across ${project.rooms.size} rooms)"
        } catch (e: Exception) {
            statusMessage = "Error saving project: ${e.message}"
        }
    }

    fun loadProject(file: File) {
        try {
            val jsonStr = file.readText()
            val loaded = json.decodeFromString(MetEditProject.serializer(), jsonStr)
            RomPreferences.setLastProjectPath(file.absolutePath)

            if (loaded.romPath.isNotEmpty()) {
                val romF = File(loaded.romPath)
                if (romF.exists()) {
                    loadRom(romF)  // This resets project to empty
                    // Now restore the loaded project data (edits, settings)
                    project = loaded
                    projectFile = file
                    dirty = false
                    Area.entries.getOrNull(loaded.lastArea)?.let { switchArea(it) }
                    if (loaded.lastRoom >= 0) {
                        val room = rooms.find { it.roomNumber == loaded.lastRoom }
                        if (room != null) selectRoom(room)
                    }
                } else {
                    project = loaded
                    projectFile = file
                    dirty = false
                    statusMessage = "Project loaded, but ROM not found: ${loaded.romPath}"
                }
            } else {
                project = loaded
                projectFile = file
                dirty = false
            }
            val editCount = loaded.rooms.values.sumOf { it.macroEdits.size }
            statusMessage = "Loaded: ${file.name} ($editCount macro edits across ${loaded.rooms.size} rooms)"
        } catch (e: Exception) {
            statusMessage = "Error loading project: ${e.message}"
        }
    }

    fun newProject() {
        project = MetEditProject()
        projectFile = null
        dirty = false
        undoStack.clear()
        redoStack.clear()
        undoVersion++
    }

    // -- Auto-load last session --

    fun autoLoadLastSession() {
        val lastProject = RomPreferences.getLastProjectPath()
        if (lastProject != null) {
            try {
                loadProject(File(lastProject))
                return
            } catch (e: Exception) {
                println("Failed to auto-load project: ${e.message}")
            }
        }
        val lastRom = RomPreferences.getLastRomPath()
        if (lastRom != null) {
            try {
                loadRom(File(lastRom))
            } catch (e: Exception) {
                println("Failed to auto-load ROM: ${e.message}")
            }
        }
    }

    // -- Space budget --

    fun recalcBudget() {
        val parser = romParser ?: return
        val md = metroidData ?: return
        val renderer = mapRenderer ?: return
        val area = selectedArea
        try {
            saveCurrentRoomEdits()
            val areaRooms = md.readAllRooms(area)
            val grids = areaRooms.map { room ->
                val grid = renderer.buildMacroGrid(room)
                val key = roomKey(room.area, room.roomNumber)
                val edits = project.rooms[key]
                if (edits != null) {
                    for (edit in edits.macroEdits) {
                        grid.set(edit.x, edit.y, edit.macroIndex)
                        grid.setAttr(edit.x, edit.y, edit.palette)
                    }
                }
                grid
            }
            spaceBudget = RoomEncoder.calculateBudget(parser, md, area, areaRooms, grids)
        } catch (_: Exception) {
            spaceBudget = null
        }
    }

    // -- ROM export --

    fun exportRom(outputFile: File) {
        val parser = romParser ?: return
        val md = metroidData ?: return
        val renderer = mapRenderer ?: return

        try {
            saveCurrentRoomEdits()
            logger.info { "exportRom: ${project.rooms.size} edited rooms → ${outputFile.name}" }

            // Start with the current ROM data
            var exportData = parser.copyRomData()
            var exportParser = NesRomParser(exportData)

            // First attempt: export to current ROM format
            var result = RomExporter.exportAll(
                exportParser, parser, md, renderer, project.rooms
            )

            // If uncovered edits failed on standard ROM, auto-expand and retry
            if (!result.success && RomExpander.isStandardRom(exportData)) {
                logger.info { "exportRom: standard ROM has uncovered edits, expanding to 256KB..." }
                val expanded = RomExpander.expand(parser.romData)
                if (expanded != null) {
                    exportData = expanded
                    val expandedOrigParser = NesRomParser(expanded)
                    val expandedMd = MetroidRomData(expandedOrigParser)
                    val expandedPd = NesPatternDecoder(expandedOrigParser)
                    val expandedRenderer = MapRenderer(expandedMd, expandedPd)
                    exportParser = NesRomParser(expanded.copyOf())
                    result = RomExporter.exportAll(
                        exportParser, expandedOrigParser, expandedMd, expandedRenderer, project.rooms
                    )
                    exportData = exportParser.romData
                }
            }

            if (!result.success) {
                logger.error { "exportRom: FAILED — ${result.warnings.joinToString("; ")}" }
                statusMessage = "Export failed: ${result.warnings.joinToString("; ")}"
                return
            }

            outputFile.writeBytes(exportData)

            val expandNote = if (exportData.size > parser.romData.size) " (ROM expanded to ${exportData.size / 1024}KB)" else ""
            logger.info { "exportRom: done — ${result.coveredPatched} edits applied$expandNote" }
            statusMessage = "ROM exported: ${outputFile.name} (${result.coveredPatched} edits applied$expandNote)"
        } catch (e: Exception) {
            logger.error(e) { "exportRom failed" }
            statusMessage = "Error exporting ROM: ${e.message}"
        }
    }
}
