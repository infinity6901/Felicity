package app.simple.felicity.milkdrop.bridges

/**
 * JNI bindings for the native projectM 4.x milkdrop visualizer.
 *
 * Thread-safe: all access to [nativeHandle] is synchronized on [lock] so that
 * [addPcmData] from the audio thread and [destroy] / [renderFrame] from the GL
 * thread never race on a freed native pointer.
 *
 * @author Hamza417
 */
class ProjectMBridge {

    private val lock = Any()

    @Volatile
    internal var nativeHandle: Long = 0L
        private set

    val isCreated: Boolean
        get() = nativeHandle != 0L

    fun create(width: Int, height: Int) {
        synchronized(lock) {
            if (nativeHandle != 0L) return
            nativeHandle = nativeCreate(width, height)
        }
    }

    fun surfaceChanged(width: Int, height: Int) {
        synchronized(lock) {
            if (nativeHandle == 0L) return
            nativeSurfaceChanged(nativeHandle, width, height)
        }
    }

    fun addPcmData(samples: FloatArray, count: Int, isStereo: Boolean = false) {
        synchronized(lock) {
            if (nativeHandle == 0L) return
            nativeAddPcmData(nativeHandle, samples, count, isStereo)
        }
    }

    fun renderFrame() {
        synchronized(lock) {
            if (nativeHandle == 0L) return
            nativeRenderFrame(nativeHandle)
        }
    }

    fun destroy() {
        synchronized(lock) {
            val handle = nativeHandle
            if (handle == 0L) return
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    fun loadPresetData(content: String, smooth: Boolean = true) {
        synchronized(lock) {
            if (nativeHandle == 0L) return
            nativeLoadPresetData(nativeHandle, content, smooth)
        }
    }

    private external fun nativeCreate(width: Int, height: Int): Long
    private external fun nativeSurfaceChanged(nativePtr: Long, width: Int, height: Int)
    private external fun nativeAddPcmData(nativePtr: Long, samples: FloatArray, count: Int, isStereo: Boolean)
    private external fun nativeRenderFrame(nativePtr: Long)
    private external fun nativeLoadPresetData(nativePtr: Long, data: String, smooth: Boolean)
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            System.loadLibrary("felicity_milkdrop")
        }
    }
}