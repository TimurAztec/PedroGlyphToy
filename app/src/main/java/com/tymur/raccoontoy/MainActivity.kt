package com.tymur.raccoontoy

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tymur.raccoontoy.core.LumaFrames
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: ToyStore
    private lateinit var preview: MatrixPreviewView
    private lateinit var status: TextView

    /** Decoded GIF kept in memory so slider changes re-render instantly. */
    @Volatile private var luma: LumaFrames? = null
    private val rebuildRunnable = Runnable { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ToyStore(this)
        buildUi()

        io.execute {
            val decoded = store.loadLuma()
            luma = decoded
            val frames = decoded?.let { store.convert(it) } ?: store.loadFrames()
            val hasGif = decoded != null
            main.post {
                preview.show(frames)
                status.text = getString(
                    if (hasGif) R.string.status_fmt else R.string.no_gif,
                    frames.count, frames.delaysMs.sum() / 1000f
                )
            }
        }
        if (hasCallPermission()) CallWatchService.start(this)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(text(getString(R.string.app_name), 24f))
        preview = MatrixPreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = pad; bottomMargin = pad / 2
        })
        status = text("", 14f).apply { gravity = Gravity.CENTER; setTextColor(Color.GRAY) }
        root.addView(status)

        root.addView(slider(R.string.brightness, 10, 100, (store.brightness * 100).toInt()) {
            store.brightness = it / 100f; scheduleRebuild()
        })

        root.addView(button(getString(R.string.grant_permissions)) { requestPermissions() })
        root.addView(button(getString(R.string.activate)) { openToysManager() })
        root.addView(text(getString(R.string.hint), 13f).apply {
            setTextColor(Color.GRAY); setPadding(0, pad / 2, 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun slider(labelRes: Int, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            val label = text("", 14f)
            fun render(v: Int) { label.text = "${getString(labelRes)}: $v%" }
            render(value)
            addView(label)
            addView(SeekBar(context).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        render(p + min); onChange(p + min)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }

    private fun scheduleRebuild() {
        main.removeCallbacks(rebuildRunnable)
        main.postDelayed(rebuildRunnable, 120)
    }

    private fun rebuild() {
        val l = luma ?: return
        io.execute {
            val frames = store.convert(l)
            main.post { preview.show(frames) }
        }
    }

    private fun hasCallPermission() =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS),
            REQ_PERMS
        )
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQ_PERMS && hasCallPermission()) CallWatchService.start(this)
    }

    private fun openToysManager() {
        val intent = Intent().setComponent(
            ComponentName(
                "com.nothing.thirdparty",
                "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"
            )
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.manager_missing, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQ_PERMS = 1
    }
}
