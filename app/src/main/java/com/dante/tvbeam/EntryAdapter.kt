package com.dante.tvbeam

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dante.tvbeam.databinding.ItemEntryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class EntryAdapter(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onClick: (Entry) -> Unit,
    private val onLong: (Entry) -> Boolean
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root) {
        var job: Job? = null
    }

    var items: List<Entry> = emptyList()
        private set

    private val density = ctx.resources.displayMetrics.density

    fun submit(list: List<Entry>, reset: Boolean = false) {
        val old = items
        if (reset) {
            items = list
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == list[n].key
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
        super.onViewRecycled(holder)
    }

    private fun showIcon(iv: ImageView, res: Int, tint: Int) {
        val pad = (13 * density).toInt()
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iv.setPadding(pad, pad, pad, pad)
        iv.imageTintList = ColorStateList.valueOf(tint)
        iv.setImageResource(res)
    }

    private fun showBitmap(iv: ImageView, bmp: Bitmap) {
        iv.imageTintList = null
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setPadding(0, 0, 0, 0)
        iv.setImageBitmap(bmp)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.job?.cancel()
        h.job = null

        h.b.title.text = e.name

        var sub = e.subtitle
        e.resumeKey?.let { key ->
            TvStore.resume(ctx, key)?.let { (p, d) ->
                sub += "   •   Resume ${fmtTime(p)} / ${fmtTime(d)}"
            }
        }
        h.b.sub.text = sub

        val (res, tint) = when (e.kind) {
            Kind.PHONE -> R.drawable.ic_phone to 0xFF5CE1E6.toInt()
            Kind.ROOT -> R.drawable.ic_folder to 0xFF5CE1E6.toInt()
            Kind.FOLDER -> R.drawable.ic_folder to 0xFFFFC857.toInt()
            Kind.VIDEO -> R.drawable.ic_movie to 0xFFFF7A3D.toInt()
            Kind.AUDIO -> R.drawable.ic_audio to 0xFFB28DFF.toInt()
        }
        val iv = h.b.thumb
        iv.tag = e.thumb
        showIcon(iv, res, tint)

        val url = e.thumb
        if (url != null) {
            val hit = ThumbLoader.cached(url)
            if (hit != null) {
                showBitmap(iv, hit)
            } else {
                h.job = scope.launch {
                    val bmp = ThumbLoader.get(url)
                    if (bmp != null && iv.tag == url) showBitmap(iv, bmp)
                }
            }
        }

        h.b.root.setOnClickListener { onClick(e) }
        h.b.root.setOnLongClickListener { onLong(e) }
        h.b.root.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.02f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}
