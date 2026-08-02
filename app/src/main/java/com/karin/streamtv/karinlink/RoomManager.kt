package com.karin.streamtv.karinlink

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class RoomManager {

    companion object {
        private const val TAG = "RoomManager"
    }

    data class Room(
        val id: String = UUID.randomUUID().toString().take(8).uppercase(),
        val name: String,
        val hostDeviceId: String,
        val hostDeviceName: String,
        val members: MutableList<MemberInfo> = mutableListOf(),
        var currentEpisode: SyncState? = null,
        var isPlaying: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class MemberInfo(
        val deviceId: String,
        val deviceName: String,
        var isReady: Boolean = false,
        var progressMs: Long = 0L
    )

    data class SyncState(
        val episodeTitle: String = "",
        val episodeUrl: String = "",
        val siteName: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _currentRoom = MutableStateFlow<Room?>(null)
    val currentRoom: StateFlow<Room?> = _currentRoom

    private val _availableRooms = MutableStateFlow<List<Room>>(emptyList())
    val availableRooms: StateFlow<List<Room>> = _availableRooms

    fun createRoom(name: String, hostDeviceId: String, hostDeviceName: String): Room {
        val room = Room(
            name = name,
            hostDeviceId = hostDeviceId,
            hostDeviceName = hostDeviceName
        )
        room.members.add(MemberInfo(hostDeviceId, hostDeviceName, isReady = true))
        _currentRoom.value = room
        _availableRooms.value = _availableRooms.value + room
        Log.i(TAG, "Room created: ${room.id} - $name")
        return room
    }

    fun joinRoom(roomId: String, deviceId: String, deviceName: String): Room? {
        val room = _availableRooms.value.find { it.id == roomId } ?: run {
            // Room not discovered yet (QR/link join) — create placeholder
            val placeholder = Room(
                id = roomId,
                name = "Sala $roomId",
                hostDeviceId = roomId,
                hostDeviceName = "Remoto"
            )
            _availableRooms.value = _availableRooms.value + placeholder
            return@run placeholder
        }
        room.members.add(MemberInfo(deviceId, deviceName))
        _currentRoom.value = room
        Log.i(TAG, "Joined room: $roomId")
        return room
    }

    fun leaveRoom() {
        val room = _currentRoom.value ?: return
        Log.i(TAG, "Leaving room: ${room.id}")
        _currentRoom.value = null
    }

    fun updateSync(state: SyncState) {
        val room = _currentRoom.value ?: return
        val updated = room.copy(
            currentEpisode = state,
            isPlaying = state.isPlaying,
            members = room.members.toMutableList()
        )
        _currentRoom.value = updated
        Log.d(TAG, "Sync update: ${state.episodeTitle} @ ${state.positionMs}ms")
    }

    fun updateProgress(deviceId: String, positionMs: Long) {
        val room = _currentRoom.value ?: return
        val updatedMembers = room.members.map {
            if (it.deviceId == deviceId) it.copy(progressMs = positionMs) else it
        }
        _currentRoom.value = room.copy(members = updatedMembers.toMutableList())
    }

    fun memberReady(deviceId: String, ready: Boolean) {
        val room = _currentRoom.value ?: return
        val updatedMembers = room.members.map {
            if (it.deviceId == deviceId) it.copy(isReady = ready) else it
        }
        _currentRoom.value = room.copy(members = updatedMembers.toMutableList())
    }

    fun getRoomUrl(roomId: String): String {
        return "karinflinx://room/$roomId"
    }

    fun parseRoomId(url: String): String? {
        return when {
            url.startsWith("karinflinx://room/") -> url.substringAfter("karinflinx://room/")
            url.contains("/room/") -> url.substringAfterLast("/room/")
            else -> null
        }
    }
}
