package com.sycamorecreek.sonoswidget.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * SonosWidget Application class.
 *
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection
 * across the entire application, including the companion Activity,
 * foreground service, and widget receiver.
 */
@HiltAndroidApp
class SonosApplication : Application()
