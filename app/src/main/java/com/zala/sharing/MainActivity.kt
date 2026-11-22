package com.zala.sharing

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : BaseActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        askPerms()
        val r = newRoot()
        r.addView(label("Zala Sharing", 34f, ORANGE, true))
        r.addView(label("Send and receive videos and files between phone and TV.", 16f, GRAY))
        r.addView(label("No internet needed. Just connect both devices to the same Hotspot.", 16f, GRAY))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) }
        }
        fun big(t: String, go: Class<*>): Button = btn(t, 28f) { startActivity(Intent(this, go)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(150), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        val send = big("SEND", SendActivity::class.java)
        val recv = big("RECEIVE", ReceiveActivity::class.java)
        row.addView(send)
        row.addView(recv)
        r.addView(row)
        r.addView(btn("Received files (play / install)") { showReceived() })
        r.addView(label("\nSafe: everything is encrypted, a code is needed to connect, and nothing goes to the internet.", 14f, GREEN))
        show(r)
        send.requestFocus()
    }
}
