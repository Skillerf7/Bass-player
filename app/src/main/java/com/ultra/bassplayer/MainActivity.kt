package com.ultra.bassplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    private val exts = setOf("mp3", "wav", "flac", "m4a", "ogg", "opus", "aac", "wma")
    private val proc = BassProcessor()
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private lateinit var cover: ImageView
    private lateinit var list: ListView
    private lateinit var eqScroll: ScrollView
    private lateinit var setScroll: ScrollView
    private lateinit var adapter: ArrayAdapter<String>
    private val bandBars = ArrayList<SeekBar>()
    private var names: List<String> = emptyList()
    private var uris: List<Uri> = emptyList()
    private val values = HashMap<String, Int>()
    private var limiterOn = true
    private var coverToken = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val d = player.duration
            if (d > 0) {
                seek.max = d.toInt()
                seek.progress = player.currentPosition.toInt()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("bass", MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(30, 70, 30, 20)

        val title = TextView(this)
        title.text = "🎧 Bass Player"
        title.textSize = 22f
        status = TextView(this)
        status.text = "Wähle einen Ordner"
        nowPlaying = TextView(this)
        nowPlaying.textSize = 16f

        cover = ImageView(this)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setBackgroundColor(0xFF2A2A2A.toInt())
        val coverLp = LinearLayout.LayoutParams(dp(150), dp(150))
        coverLp.gravity = Gravity.CENTER_HORIZONTAL
        coverLp.topMargin = dp(8)

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) player.seekTo(p.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val row = LinearLayout(this)
        playBtn = btn("▶") { if (player.isPlaying) player.pause() else player.play() }
        row.addView(btn("⏮") { player.seekToPreviousMediaItem() }, weight())
        row.addView(playBtn, weight())
        row.addView(btn("⏭") { player.seekToNextMediaItem() }, weight())
        row.addView(btn("🔀") {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            status.text = if (player.shuffleModeEnabled) "Zufall an" else "Zufall aus"
        }, weight())

        val tabs = LinearLayout(this)
        tabs.addView(btn("📜 Liste") { showPanel(0) }, weight())
        tabs.addView(btn("🎚 EQ") { showPanel(1) }, weight())
        tabs.addView(btn("⚙ Einstellungen") { showPanel(2) }, weight())

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            player.seekTo(pos, 0L)
            player.play()
        }

        // ---- EQ-Reiter ----
        eqScroll = ScrollView(this)
        val eq = LinearLayout(this)
        eq.orientation = LinearLayout.VERTICAL
        eq.addView(header("PreAmp"))
        slider(eq, "PreAmp", -12, 0, 1, "pre", -6) { "$it dB" }
        eq.addView(header("Bass (Shelf)"))
        slider(eq, "Verstärkung", 0, 12, 1, "bass", 5) { "$it dB" }
        slider(eq, "Freq", 40, 250, 5, "bassf", 100) { "$it Hz" }
        slider(eq, "Q", 3, 20, 1, "bassq", 10) { "${it / 10.0}" }
        eq.addView(header("Höhen (Shelf)"))
        slider(eq, "Verstärkung", 0, 10, 1, "treble", 3) { "$it dB" }
        slider(eq, "Freq", 4000, 12000, 500, "treblef", 8000) { "$it Hz" }
        slider(eq, "Q", 3, 20, 1, "trebleq", 8) { "${it / 10.0}" }
        eq.addView(header("Hochpass (0 = aus)"))
        slider(eq, "Hochpass", 0, 60, 5, "hp", 35) { "$it Hz" }
        val lim = Switch(this)
        lim.text = "Limiter"
        limiterOn = prefs.getBoolean("lim", true)
        lim.isChecked = limiterOn
        lim.setOnCheckedChangeListener { _, on ->
            limiterOn = on
            prefs.edit().putBoolean("lim", on).apply()
            applyEq()
        }
        eq.addView(lim)
        eq.addView(header("10-Band-EQ"))
        val labels = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        for (i in 0 until 10) {
            bandBars.add(slider(eq, "${labels[i]} Hz", -12, 12, 1, "b$i", 0) { "$it dB" })
        }
        eq.addView(btn("↺ 10-Band auf Flat") { bandBars.forEach { it.progress = 12 } })
        eqScroll.addView(eq)

        // ---- Einstellungen-Reiter ----
        setScroll = ScrollView(this)
        val set = LinearLayout(this)
        set.orientation = LinearLayout.VERTICAL
        val hi = Switch(this)
        hi.text = "Hi-Res-Ausgabe (32-Bit Float)"
        hi.isChecked = prefs.getBoolean("hires", false)
        hi.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("hires", on).apply()
            rebuildPlayer()
            status.text = if (on) "Hi-Res an" else "Hi-Res aus"
        }
        val hiInfo = TextView(this)
        hiInfo.text = "Wirkt bei 24-Bit-Dateien (FLAC/WAV). MP3 bleibt 16-Bit. Die Samplerate bleibt wie in der Datei."
        val cv = Switch(this)
        cv.text = "Cover automatisch im Internet suchen"
        cv.isChecked = prefs.getBoolean("covers", true)
        cv.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("covers", on).apply() }
        set.addView(hi)
        set.addView(hiInfo)
        set.addView(cv)
        set.addView(btn("🗑 Cover-Cache leeren") {
            cacheDir.listFiles()?.filter { it.name.startsWith("cover_") }?.forEach { it.delete() }
            status.text = "Cover-Cache geleert"
        })
        setScroll.addView(set)

        val box = FrameLayout(this)
        box.addView(list)
        box.addView(eqScroll)
        box.addView(setScroll)

        root.addView(title)
        root.addView(btn("📂 Ordner wählen") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        })
        root.addView(cover, coverLp)
        root.addView(status)
        root.addView(nowPlaying)
        root.addView(seek)
        root.addView(row)
        root.addView(tabs)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showPanel(0)

        buildPlayer()
        applyEq()
        handler.post(ticker)
        prefs.getString("folder", null)?.let { loadFolder(Uri.parse(it)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun header(t: String): TextView {
        val h = TextView(this)
        h.text = t
        h.textSize = 18f
        h.setPadding(0, 40, 0, 8)
        return h
    }

    private fun btn(t: String, a: () -> Unit): Button {
        val bt = Button(this)
        bt.text = t
        bt.setOnClickListener { a() }
        return bt
    }

    private fun showPanel(p: Int) {
        list.visibility = if (p == 0) View.VISIBLE else View.GONE
        eqScroll.visibility = if (p == 1) View.VISIBLE else View.GONE
        setScroll.visibility = if (p == 2) View.VISIBLE else View.GONE
    }

    private fun buildPlayer() {
        val hiRes = prefs.getBoolean("hires", false)
        val factory = object : DefaultRenderersFactory(this@MainActivity) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(hiRes)
                    .setAudioProcessors(arrayOf<AudioProcessor>(proc))
                    .build()
            }
        }
        player = ExoPlayer.Builder(this, factory).setHandleAudioBecomingNoisy(true).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true
        )
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.text = if (isPlaying) "⏸" else "▶"
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                nowPlaying.text = names.getOrNull(i) ?: ""
                loadCover(i)
            }
        })
    }

    private fun rebuildPlayer() {
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        val was = player.isPlaying
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        player.release()
        buildPlayer()
        if (items.isNotEmpty()) {
            player.setMediaItems(items, idx, pos)
            player.prepare()
            if (was) player.play()
        }
    }

    private fun v(k: String) = (values[k] ?: 0).toDouble()

    private fun applyEq() {
        proc.update(
            v("pre"), v("bass"), v("bassf"), v("bassq") / 10.0,
            v("treble"), v("treblef"), v("trebleq") / 10.0,
            v("hp"), limiterOn, DoubleArray(10) { v("b$it") }
        )
    }

    private fun slider(
        parent: LinearLayout, label: String, min: Int, max: Int,
        step: Int, key: String, def: Int, fmt: (Int) -> String
    ): SeekBar {
        val tv = TextView(this)
        val sb = SeekBar(this)
        sb.max = (max - min) / step
        val saved = prefs.getInt(key, def)
        values[key] = saved
        sb.progress = (saved - min) / step
        tv.text = "$label: ${fmt(saved)}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val nv = min + p * step
                values[key] = nv
                tv.text = "$label: ${fmt(nv)}"
                prefs.edit().putInt(key, nv).apply()
                applyEq()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
        return sb
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == 1 && res == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            loadFolder(uri)
        }
    }

    private fun collect(dir: DocumentFile, out: MutableList<Pair<String, Uri>>) {
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (f.isDirectory) {
                if (n != "_Quarantaene") collect(f, out)
            } else if (n.substringAfterLast('.', "").lowercase() in exts) {
                out.add(Pair(n, f.uri))
            }
        }
    }

    private fun loadFolder(uri: Uri) {
        status.text = "Lese Ordner…"
        Thread {
            val root = DocumentFile.fromTreeUri(this, uri)
            val found = ArrayList<Pair<String, Uri>>()
            if (root != null) collect(root, found)
            found.sortBy { it.first.lowercase() }
            runOnUiThread {
                names = found.map { it.first.substringBeforeLast('.') }
                uris = found.map { it.second }
                adapter.clear()
                adapter.addAll(names)
                adapter.notifyDataSetChanged()
                player.setMediaItems(found.map { MediaItem.fromUri(it.second) })
                player.prepare()
                status.text = "${found.size} Titel"
            }
        }.start()
    }

    // ---- Cover ----
    private fun loadCover(index: Int) {
        val token = ++coverToken
        val uri = uris.getOrNull(index) ?: return
        val name = names.getOrNull(index) ?: ""
        cover.setImageDrawable(null)
        Thread {
            var bmp: Bitmap? = null
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val art = r.embeddedPicture
                r.release()
                if (art != null) bmp = decodeScaled(art)
            } catch (e: Exception) {
            }
            if (bmp == null && prefs.getBoolean("covers", true)) bmp = onlineCover(name)
            val res = bmp
            runOnUiThread {
                if (token == coverToken && res != null) cover.setImageBitmap(res)
            }
        }.start()
    }

    private fun cleanName(n: String): String {
        var s = n.replace(Regex("_?\\d{2,3}k\\b"), " ")
        s = s.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        return s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun onlineCover(raw: String): Bitmap? {
        val term = cleanName(raw)
        if (term.length < 3) return null
        val f = File(cacheDir, "cover_" + term.hashCode() + ".jpg")
        val none = File(cacheDir, "cover_" + term.hashCode() + ".none")
        if (f.exists()) return BitmapFactory.decodeFile(f.path)
        if (none.exists()) return null
        return try {
            val q = URLEncoder.encode(term, "UTF-8")
            val js = String(
                httpBytes("https://itunes.apple.com/search?term=$q&entity=song&limit=1"),
                Charsets.UTF_8
            )
            val arr = JSONObject(js).getJSONArray("results")
            if (arr.length() == 0) {
                none.writeText("x")
                return null
            }
            val url = arr.getJSONObject(0).getString("artworkUrl100").replace("100x100", "600x600")
            val bytes = httpBytes(url)
            f.writeBytes(bytes)
            decodeScaled(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun httpBytes(u: String): ByteArray {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "BassPlayer/1.0")
        return try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun decodeScaled(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 700) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        player.release()
        super.onDestroy()
    }
}
