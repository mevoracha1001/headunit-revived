package com.adamate.aaforaaos.app

import android.os.Bundle
import com.adamate.aaforaaos.R

abstract class SurfaceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_headunit)
    }
}
