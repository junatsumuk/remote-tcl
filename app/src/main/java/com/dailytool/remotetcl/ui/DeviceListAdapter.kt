package com.dailytool.remotetcl.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dailytool.remotetcl.databinding.ItemTvDeviceBinding
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvType

class DeviceListAdapter(
    private val onDeviceClick: (TvDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<TvDevice>()

    fun setDevices(newDevices: List<TvDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun addDevice(device: TvDevice) {
        val existingIndex = devices.indexOfFirst { it.ipAddress == device.ipAddress }
        if (existingIndex == -1) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        } else {
            devices[existingIndex] = device
            notifyItemChanged(existingIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemTvDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(private val binding: ItemTvDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: TvDevice) {
            binding.tvItemName.text = device.name
            binding.tvItemIp.text = device.ipAddress
            binding.tvItemType.text = when (device.type) {
                TvType.ANDROID_TV -> "Android/Google TV"
                TvType.ROKU_TV -> "Roku TV"
                TvType.UNKNOWN -> "Smart TV"
            }

            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}
