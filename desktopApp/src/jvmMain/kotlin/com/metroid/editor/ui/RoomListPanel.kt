package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.Room

@Composable
fun RoomListPanel(
    rooms: List<Room>,
    selectedRoom: Room?,
    onRoomSelected: (Room) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Surface(
        color = Color(0xFF161630),
        modifier = modifier
    ) {
        Column {
            // Header
            Surface(
                color = Color(0xFF1E1E3A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Rooms (${rooms.size})",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFB0B0CC)
                )
            }

            // Room list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(rooms) { room ->
                    val isSelected = room == selectedRoom
                    Surface(
                        color = if (isSelected) Color(0xFF2E2E5E) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRoomSelected(room) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$%02X".format(room.roomNumber),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isSelected) Color(0xFF8E6FFF) else Color(0xFF707090)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Room ${room.roomNumber}",
                                    fontSize = 13.sp,
                                    color = if (isSelected) Color(0xFFE0E0FF) else Color(0xFFA0A0C0)
                                )
                                Text(
                                    "${room.objects.size} objects, ${room.enemies.size} enemies, ${room.doors.size} doors",
                                    fontSize = 10.sp,
                                    color = Color(0xFF606080)
                                )
                            }
                        }
                    }
                    if (room != rooms.last()) {
                        Divider(color = Color(0xFF1A1A35), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
