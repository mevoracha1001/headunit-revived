package com.adamate.aaforaaos.utils

import android.content.IntentFilter
import com.adamate.aaforaaos.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}