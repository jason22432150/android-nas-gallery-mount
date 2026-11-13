package com.nas.gallerymount.config

import android.content.Context
import com.nas.gallerymount.domain.IndexAvailability
import com.nas.gallerymount.domain.MountSnapshot

/**
 * 持久化上次掛載 session，供重開機後重新探測。
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveMount(snapshot: MountSnapshot, indexAvailability: IndexAvailability) {
        prefs.edit()
            .putBoolean(KEY_FUSE, snapshot.fuseMounted)
            .putBoolean(KEY_BIND, snapshot.bindMounted)
            .putString(KEY_MOUNT, snapshot.mountPoint)
            .putString(KEY_REMOTE, snapshot.remotePath)
            .putInt(KEY_PID, snapshot.mediaProviderPid ?: -1)
            .putString(KEY_INDEX, indexAvailability.name)
            .apply()
    }

    fun loadMount(): MountSnapshot {
        val pid = prefs.getInt(KEY_PID, -1)
        return MountSnapshot(
            fuseMounted = prefs.getBoolean(KEY_FUSE, false),
            bindMounted = prefs.getBoolean(KEY_BIND, false),
            mediaProviderPid = pid.takeIf { it > 0 },
            mountPoint = prefs.getString(KEY_MOUNT, "").orEmpty(),
            remotePath = prefs.getString(KEY_REMOTE, "").orEmpty(),
        )
    }

    fun loadIndexAvailability(): IndexAvailability {
        val raw = prefs.getString(KEY_INDEX, IndexAvailability.Unknown.name)
        return runCatching { IndexAvailability.valueOf(raw!!) }
            .getOrDefault(IndexAvailability.Unknown)
    }

    fun clearActiveFlags() {
        prefs.edit()
            .putBoolean(KEY_FUSE, false)
            .putBoolean(KEY_BIND, false)
            .putInt(KEY_PID, -1)
            .apply()
    }

    companion object {
        private const val PREFS = "session_store"
        private const val KEY_FUSE = "fuse"
        private const val KEY_BIND = "bind"
        private const val KEY_MOUNT = "mount"
        private const val KEY_REMOTE = "remote"
        private const val KEY_PID = "pid"
        private const val KEY_INDEX = "index"
    }
}
