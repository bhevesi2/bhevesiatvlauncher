package hu.otthon.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the user pick one Home Assistant entity from a searchable list.
 * Extras in: "title", "domains" (comma separated, empty = all). Result: "entity", "name".
 */
class PickEntityActivity : Activity() {

    private var all: List<EntityInfo> = emptyList()
    private var shown: List<EntityInfo> = emptyList()
    private var domain = ""
    private var query = ""
    private lateinit var listView: ListView
    private lateinit var chipRow: LinearLayout
    private lateinit var status: TextView
    private val chips = ArrayList<Pair<String, TextView>>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val name: TextView
            val id: TextView
            val state: TextView
            if (convertView is LinearLayout) {
                row = convertView
                val col = row.getChildAt(0) as LinearLayout
                name = col.getChildAt(0) as TextView
                id = col.getChildAt(1) as TextView
                state = row.getChildAt(1) as TextView
            } else {
                row = LinearLayout(this@PickEntityActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                }
                val col = LinearLayout(this@PickEntityActivity).apply { orientation = LinearLayout.VERTICAL }
                name = label("", 12f, C.TEXT).apply { maxLines = 1 }
                id = label("", 10f, C.TEXT3).apply { maxLines = 1; setPadding(0, dp(2f), 0, 0) }
                col.addView(name)
                col.addView(id)
                state = label("", 11f, C.TEXT2).apply { maxLines = 1 }
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(state, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
            }
            val e = shown[position]
            name.text = e.name
            id.text = e.id
            state.text = if (e.state.length > 22) e.state.take(21) + "…" else e.state
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config.load(this)
        if (!cfg.haEnabled) {
            Toast.makeText(this, "Set up Home Assistant first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val allowed = (intent.getStringExtra("domains") ?: "").split(',').filter { it.isNotBlank() }

        val root = FrameLayout(this)
        val backdrop = Backdrop(this)
        backdrop.setDim(0.8f, flat = true)
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.PANEL, dp(18f).toFloat(), 1, C.BORDER)
            setPadding(dp(20f), dp(18f), dp(20f), dp(14f))
        }
        panel.addView(label(intent.getStringExtra("title") ?: "Choose an entity", 17f, C.TEXT, bold = true))

        val search = EditText(this).apply {
            hint = "Search by name"
            setHintTextColor(0x66FFFFFF)
            setTextColor(C.TEXT)
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            background = rounded(0x1FFFFFFF, dp(8f).toFloat(), 1, C.BORDER)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                apply()
            }
        })
        search.setOnEditorActionListener { _, _, _ -> listView.requestFocus(); true }
        panel.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        val scroller = RowScroller(this)
        scroller.setPadding(0, dp(8f), 0, dp(8f))
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(chipRow)
        panel.addView(scroller, LinearLayout.LayoutParams(-1, -2))

        listView = ListView(this).apply {
            divider = null
            selector = rounded(0x33FFFFFF, dp(8f).toFloat())
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = shown.getOrNull(pos)
            if (e != null) {
                setResult(RESULT_OK, Intent().putExtra("entity", e.id).putExtra("name", e.name))
                finish()
            }
        }
        panel.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Loading entities from Home Assistant…", 11f, C.TEXT3).apply { setPadding(dp(2f), dp(8f), 0, 0) }
        panel.addView(status)

        root.addView(panel, FrameLayout.LayoutParams(dp(700f), dp(470f), Gravity.CENTER))
        setContentView(root)

        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        Bg.run({ client.states() }) { list ->
            if (list == null) {
                status.text = "Couldn't load entities from Home Assistant"
            } else {
                all = list.filter { allowed.isEmpty() || it.domain in allowed }.sortedBy { it.name.lowercase() }
                buildChips()
                apply()
                listView.requestFocus()
            }
        }
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        chips.clear()
        val counts = all.groupingBy { it.domain }.eachCount().entries.sortedByDescending { it.value }
        if (counts.size < 2) return
        val names = listOf("") + counts.take(16).map { it.key }
        names.forEachIndexed { i, d ->
            val v = pill(if (d.isEmpty()) "All" else d.replace('_', ' '), null) {
                domain = d
                paintChips()
                apply()
            }
            chips.add(Pair(d, v))
            chipRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(6f) })
        }
        paintChips()
    }

    private fun paintChips() {
        for ((d, v) in chips) {
            val base = if (d.isEmpty()) "All" else d.replace('_', ' ')
            v.text = if (d == domain) "✓ $base" else base
        }
    }

    private fun apply() {
        shown = all.filter {
            (domain.isEmpty() || it.domain == domain) &&
                (query.isEmpty() || it.name.lowercase().contains(query) || it.id.contains(query))
        }
        adapter.notifyDataSetChanged()
        status.text = if (all.isEmpty()) "No matching entities" else "${shown.size} of ${all.size} entities"
    }
}
