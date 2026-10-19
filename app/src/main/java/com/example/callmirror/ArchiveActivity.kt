package com.example.callmirror

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple archive screen: all mirrored calls + notifications, filterable, deletable. */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var db: ArchiveDb
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var btnAll: Button
    private lateinit var btnCalls: Button
    private lateinit var btnNotifs: Button
    private var filter: String? = null
    private var entries: List<ArchiveDb.Entry> = emptyList()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ArchiveDb.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_dark))
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }

        // Header: title + clear all
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "📂 Archive"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnClear = Button(this).apply {
            text = "Clear all"
            isAllCaps = false
            setTextColor(color(R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@ArchiveActivity, R.color.accent_red)
            setOnClickListener { confirmClearAll() }
        }
        header.addView(btnClear)
        root.addView(header)

        // Filter buttons
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnAll = filterButton("All") { setFilter(null) }
        btnCalls = filterButton("📞 Calls") { setFilter(ArchiveDb.TYPE_CALL) }
        btnNotifs = filterButton("💬 Notifications") { setFilter(ArchiveDb.TYPE_NOTIFICATION) }
        listOf(btnAll, btnCalls, btnNotifs).forEach {
            filters.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(8), dp(3), dp(8))
            })
        }
        root.addView(filters)

        val hint = TextView(this).apply {
            text = "Long-press an item to delete it. Data stays only on this phone."
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
        }
        root.addView(hint)

        // List + empty state
        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(8)
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
        }
        emptyView = TextView(this).apply {
            text = "No archived items yet"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            visibility = View.GONE
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))

        listView.adapter = adapter
        listView.setOnItemLongClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { confirmDelete(it) }
            true
        }

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun filterButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(color(R.color.text_primary))
            setOnClickListener { onClick() }
        }

    private fun setFilter(type: String?) {
        filter = type
        refresh()
    }

    private fun refresh() {
        entries = db.list(filter)
        adapter.notifyDataSetChanged()
        val empty = entries.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE

        fun style(b: Button, selected: Boolean) {
            b.backgroundTintList = ContextCompat.getColorStateList(
                this, if (selected) R.color.purple_500 else R.color.card_border
            )
        }
        style(btnAll, filter == null)
        style(btnCalls, filter == ArchiveDb.TYPE_CALL)
        style(btnNotifs, filter == ArchiveDb.TYPE_NOTIFICATION)
    }

    private fun confirmDelete(entry: ArchiveDb.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete this item?")
            .setPositiveButton("Delete") { _, _ ->
                db.delete(entry.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear the whole archive?")
            .setMessage("This permanently deletes all saved calls and notifications.")
            .setPositiveButton("Clear all") { _, _ ->
                db.clear()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val card = LinearLayout(this@ArchiveActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }

            val heading = if (e.type == ArchiveDb.TYPE_CALL) {
                "📞 " + (e.title?.takeIf { it.isNotBlank() } ?: e.number ?: "Unknown caller")
            } else {
                val app = e.app?.takeIf { it.isNotBlank() } ?: "Notification"
                val t = e.title?.takeIf { it.isNotBlank() }
                "💬 " + if (t != null) "$app • $t" else app
            }
            card.addView(TextView(this@ArchiveActivity).apply {
                text = heading
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
            })

            val detail = if (e.type == ArchiveDb.TYPE_CALL) {
                e.number?.takeIf { it.isNotBlank() && it != e.title }
            } else {
                e.body?.takeIf { it.isNotBlank() }
            }
            if (detail != null) {
                card.addView(TextView(this@ArchiveActivity).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(color(R.color.text_primary))
                    setPadding(0, dp(4), 0, 0)
                })
            }

            card.addView(TextView(this@ArchiveActivity).apply {
                text = dateFormat.format(Date(e.ts))
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(6), 0, 0)
            })
            return card
        }
    }
}
