package com.karin.streamtv.karinlink

/**
 * Protocolo del control remoto Karin Link.
 *
 * Todos los mensajes viajan como `{"type","data"}` sobre el WebSocket
 * existente (`LinkServer` / `LinkClient`). El teléfono (cliente) envía y el
 * equipo controlado (servidor) los ejecuta vía [RemoteControlHub].
 */
object RemoteProtocol {

    const val KEY = "remote.key"
    const val TEXT = "remote.text"
    const val MOUSE_MOVE = "remote.mouse.move"
    const val MOUSE_TAP = "remote.mouse.tap"
    const val MOUSE_SCROLL = "remote.mouse.scroll"
    const val MEDIA = "remote.media"

    // Comandos de remote.media
    const val CMD_TOGGLE = "toggle"
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_FF = "ff"          // adelantar (value = ms)
    const val CMD_RW = "rw"          // retroceder (value = ms)
    const val CMD_VOL_UP = "vol_up"
    const val CMD_VOL_DOWN = "vol_down"
    const val CMD_MUTE = "mute"
    const val CMD_BACK = "back"
    const val CMD_HOME = "home"

    fun isRemote(type: String): Boolean = type.startsWith("remote.")
}
