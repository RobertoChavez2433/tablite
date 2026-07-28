package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.process.LineProcessLauncher
import com.runelitetablet.logging.AppLog
import com.runelitetablet.platform.process.RuntimeLineProcessLauncher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CoreModule(context: Context) {
    val logger: Logger = AppLog.also { it.init(context); it.lifecycle("CoreModule: initialized logger + file writer") }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val lineProcessLauncher: LineProcessLauncher = RuntimeLineProcessLauncher()
}
