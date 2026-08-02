package com.karin.streamtv.ui.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.GamepadHelper

class TvExoPlayerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_exo_player)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_player_root, TvPlaybackFragment().apply {
                    arguments = intent.extras
                })
                .commit()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        return super.onKeyDown(keyCode, event)
    }
}
