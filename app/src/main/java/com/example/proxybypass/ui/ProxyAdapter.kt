package com.example.proxybypass.ui

import android.graphics.Color
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.proxybypass.R
import com.example.proxybypass.model.Proxy

class ProxyAdapter(
    private val onSelect: (Proxy) -> Unit
) : RecyclerView.Adapter<ProxyAdapter.VH>() {

    private val items = mutableListOf<Proxy>()
    private var selectedIndex = 0

    fun submitList(list: List<Proxy>) {
        items.clear()
        items.addAll(list)
        selectedIndex = 0
        notifyDataSetChanged()
    }

    fun getSelected(): Proxy? = items.getOrNull(selectedIndex)

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val addr: TextView    = view.findViewById(R.id.tvAddr)
        val proto: TextView   = view.findViewById(R.id.tvProto)
        val latency: TextView = view.findViewById(R.id.tvLatency)
        val badge: TextView   = view.findViewById(R.id.tvBadge)

        init {
            view.setOnClickListener {
                val prev = selectedIndex
                selectedIndex = adapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedIndex)
                items.getOrNull(selectedIndex)?.let(onSelect)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.addr.text    = p.address
        holder.proto.text   = p.protocol.uppercase()
        holder.latency.text = p.latencyLabel()
        holder.badge.text   = if (position == 0) "FASTEST" else "#${position + 1}"
        holder.badge.setTextColor(if (position == 0) Color.parseColor("#1565C0") else Color.GRAY)

        val isSelected = position == selectedIndex
        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#E3F2FD") else Color.WHITE
        )
    }

    override fun getItemCount() = items.size
}
