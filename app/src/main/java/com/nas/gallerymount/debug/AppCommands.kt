package com.nas.gallerymount.debug

/**
 * ADB／自動化指令常數（對應 UI 按鈕，無需截圖點擊）。
 *
 * 範例：
 * ```
 * adb shell am broadcast -a com.nas.gallerymount.cmd.CONNECT -n com.nas.gallerymount/.debug.CommandReceiver
 * adb shell run-as com.nas.gallerymount cat files/debug/last_result.txt
 * ```
 */
object AppCommands {
    const val RECEIVER = "com.nas.gallerymount/.debug.CommandReceiver"

    const val ACTION_RECHECK = "com.nas.gallerymount.cmd.RECHECK"
    const val ACTION_SAVE = "com.nas.gallerymount.cmd.SAVE"
    const val ACTION_TEST = "com.nas.gallerymount.cmd.TEST"
    const val ACTION_LIST = "com.nas.gallerymount.cmd.LIST"
    const val ACTION_CONNECT = "com.nas.gallerymount.cmd.CONNECT"
    const val ACTION_DISCONNECT = "com.nas.gallerymount.cmd.DISCONNECT"
    const val ACTION_REFRESH = "com.nas.gallerymount.cmd.REFRESH"
    const val ACTION_CANCEL_SCAN = "com.nas.gallerymount.cmd.CANCEL_SCAN"
    const val ACTION_CLEAR_INDEX = "com.nas.gallerymount.cmd.CLEAR_INDEX"
    const val ACTION_DUMP_STATUS = "com.nas.gallerymount.cmd.DUMP_STATUS"
    const val ACTION_DUMP_LOGS = "com.nas.gallerymount.cmd.DUMP_LOGS"
    const val ACTION_CLEAR_LOGS = "com.nas.gallerymount.cmd.CLEAR_LOGS"
    const val ACTION_SET_SHOW_LOGS = "com.nas.gallerymount.cmd.SET_SHOW_LOGS"
    const val ACTION_SET_MOUNT = "com.nas.gallerymount.cmd.SET_MOUNT"
    const val ACTION_SET_SMB = "com.nas.gallerymount.cmd.SET_SMB"

    const val EXTRA_SHOW = "show"
    const val EXTRA_PATH = "path"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_USER = "user"
    const val EXTRA_PASSWORD = "password"
    const val EXTRA_DOMAIN = "domain"
    const val EXTRA_SHARE = "share"
    const val EXTRA_SUBPATH = "subpath"
    const val EXTRA_GPHOTOS = "gphotos"

    const val RESULT_RELATIVE = "files/debug/last_result.txt"
    const val STATUS_RELATIVE = "files/debug/status.txt"
    const val LOG_RELATIVE = "files/debug/app.log"
}
