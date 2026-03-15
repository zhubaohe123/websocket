package com.syncwatch.app

import android.app.Application
import android.util.Log

class SyncWatchApplication : Application() {

    companion object {
        private const val TAG = "SyncWatchApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application starting...")
        Log.d(TAG, "Android Version: ${android.os.Build.VERSION.SDK_INT}")
        Log.d(TAG, "Manufacturer: ${android.os.Build.MANUFACTURER}")
        Log.d(TAG, "Model: ${android.os.Build.MODEL}")
        
        // 保存默认异常处理器
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        // 设置全局异常处理器
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "========== UNCAUGHT EXCEPTION ==========")
            Log.e(TAG, "Thread: ${thread.name}")
            Log.e(TAG, "Exception: ${throwable.javaClass.name}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Stack trace:", throwable)
            Log.e(TAG, "========================================")
            
            // 清理所有服务
            try {
                com.syncwatch.app.dlna.DlnaReceiverService.stop(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service in exception handler", e)
            }
            
            // 调用原始的默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        Log.d(TAG, "Application started successfully")
    }
    
    override fun onTerminate() {
        Log.d(TAG, "Application terminating...")
        try {
            // 确保停止所有服务
            com.syncwatch.app.dlna.DlnaReceiverService.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service in onTerminate", e)
        }
        super.onTerminate()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning!")
        try {
            // 低内存时停止服务
            com.syncwatch.app.dlna.DlnaReceiverService.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service in onLowMemory", e)
        }
    }
}
