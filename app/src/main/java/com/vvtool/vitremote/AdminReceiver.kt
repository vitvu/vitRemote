package com.vvtool.vitremote

import android.app.admin.DeviceAdminReceiver

/** Only used to turn the screen off on Android < 9 (no accessibility lock action there). */
class AdminReceiver : DeviceAdminReceiver()
