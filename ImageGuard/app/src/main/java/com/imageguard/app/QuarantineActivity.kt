package com.imageguard.app

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class QuarantineActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var scanner: Scanner
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var btnDeleteAll: Button
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quarantine)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = Store.get(this)
        scanner = Scanner(this)

        list = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)
        btnDeleteAll = findViewById(R.id.btnDeleteAll)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnDeleteAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_all_title)
                .setMessage(R.string.delete_all_body)
                .setPositiveButton(R.string.btn_delete_all) { _, _ ->
                    store.quarantined().forEach { scanner.deleteForever(it) }
                    reload()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        adapter.items = store.quarantined()
        adapter.notifyDataSetChanged()
        val empty = adapter.items.isEmpty()
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        btnDeleteAll.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun view(item: QuarantinedItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.view_warning_title)
            .setMessage(R.string.view_warning_body)
            .setPositiveButton(R.string.show) { _, _ -> showImage(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showImage(item: QuarantinedItem) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                NsfwClassifier.decode(contentResolver, store.quarantinedFile(item).toUri(), 1080)
            } ?: return@launch
            val image = ImageView(this@QuarantineActivity).apply {
                adjustViewBounds = true
                setImageBitmap(bitmap)
            }
            MaterialAlertDialogBuilder(this@QuarantineActivity)
                .setView(image)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun restore(item: QuarantinedItem) {
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) { scanner.restore(item) }
            val msg = if (target != null) getString(R.string.restored_to, target.parent)
            else getString(R.string.restore_failed)
            Toast.makeText(this@QuarantineActivity, msg, Toast.LENGTH_LONG).show()
            reload()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<QuarantinedItem> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_quarantine, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.displayName
            holder.details.text = getString(
                R.string.quarantine_details,
                (item.score * 100).roundToInt(),
                DateUtils.getRelativeTimeSpanString(item.quarantinedAt),
                item.originalPath?.let { File(it).parent } ?: getString(R.string.unknown)
            )
            holder.btnView.setOnClickListener { view(item) }
            holder.btnRestore.setOnClickListener { restore(item) }
            holder.btnDelete.setOnClickListener {
                scanner.deleteForever(item)
                reload()
            }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val details: TextView = v.findViewById(R.id.details)
        val btnView: Button = v.findViewById(R.id.btnView)
        val btnRestore: Button = v.findViewById(R.id.btnRestore)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
    }
}
