package com.camerapremium.app

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import com.camerapremium.app.camera.CameraInventory
import com.camerapremium.app.processing.PhotoProcessor
import com.camerapremium.app.settings.AppSettings
import com.camerapremium.app.storage.CaptureStore

/** Process-wide singletons: the processing queue must outlive activity recreation. */
class CameraPremiumApp : Application() {
    val settings by lazy { AppSettings(this) }
    val store by lazy { CaptureStore(this) }
    val inventory by lazy { CameraInventory(getSystemService(Context.CAMERA_SERVICE) as CameraManager) }
    val processor by lazy { PhotoProcessor(this, inventory, store) }
    @Volatile var inventoryLoaded = false

    fun ensureInventory(): CameraInventory {
        if (!inventoryLoaded) { inventory.load(); inventoryLoaded = true }
        return inventory
    }

    companion object {
        fun of(ctx: Context) = ctx.applicationContext as CameraPremiumApp
    }
}
