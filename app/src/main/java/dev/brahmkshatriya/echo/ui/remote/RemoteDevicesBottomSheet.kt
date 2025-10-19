package dev.brahmkshatriya.echo.ui.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.DialogRemoteDevicesBinding
import dev.brahmkshatriya.echo.databinding.ItemRemoteDeviceBinding
import dev.brahmkshatriya.echo.remote.ConnectionState
import dev.brahmkshatriya.echo.remote.EchoWebSocketServer
import dev.brahmkshatriya.echo.remote.RemoteDevice
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class RemoteDevicesBottomSheet : BottomSheetDialogFragment() {

    private var binding by autoCleared<DialogRemoteDevicesBinding>()
    private val viewModel by activityViewModel<RemoteViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogRemoteDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.topAppBar.setNavigationOnClickListener { dismiss() }

        // Start device discovery
        viewModel.startDiscovery()
        binding.progressIndicator.isVisible = true

        // Manual connection button
        binding.manualConnectButton.setOnClickListener {
            showManualConnectionDialog()
        }

        // Observe discovered devices
        observe(viewModel.discoveredDevices) { devices ->
            updateDeviceList(devices)

            // Hide progress when devices are found or after timeout
            if (devices.isNotEmpty()) {
                binding.progressIndicator.isVisible = false
                binding.statusText.text = getString(R.string.available_players)
            }
        }

        // Observe connection state
        observe(viewModel.connectionState) { state ->
            when (state) {
                ConnectionState.CONNECTING -> {
                    binding.statusText.text = getString(R.string.connecting)
                    binding.progressIndicator.isVisible = true
                }
                ConnectionState.CONNECTED -> {
                    binding.statusText.text = getString(
                        R.string.connected_to_x,
                        viewModel.connectedDevice.value?.name ?: ""
                    )
                    binding.progressIndicator.isVisible = false
                    // Auto-dismiss after connection
                    dismiss()
                }
                ConnectionState.ERROR -> {
                    binding.statusText.text = getString(R.string.connection_failed)
                    binding.progressIndicator.isVisible = false
                }
                ConnectionState.DISCONNECTED -> {
                    binding.statusText.text = getString(R.string.searching_for_devices)
                }
            }
        }
    }

    private fun updateDeviceList(devices: List<RemoteDevice>) {
        binding.deviceToggleGroup.removeAllViews()

        if (devices.isEmpty()) {
            binding.statusText.text = getString(R.string.no_devices_found)
            return
        }

        devices.forEach { device ->
            val button = ItemRemoteDeviceBinding.inflate(
                layoutInflater,
                binding.deviceToggleGroup,
                false
            ).root

            button.text = device.name
            button.setOnClickListener {
                connectToDevice(device)
            }

            binding.deviceToggleGroup.addView(button)
        }
    }

    private fun connectToDevice(device: RemoteDevice) {
        viewModel.connectToDevice(device)
    }

    private fun showManualConnectionDialog() {
        val editText = TextInputEditText(requireContext()).apply {
            hint = getString(R.string.ip_address_hint)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manual_connection)
            .setMessage(R.string.enter_ip_address)
            .setView(editText)
            .setPositiveButton(R.string.connect) { _, _ ->
                val ip = editText.text?.toString() ?: return@setPositiveButton
                if (ip.isNotBlank()) {
                    val device = RemoteDevice(
                        name = "Manual: $ip",
                        address = ip,
                        port = EchoWebSocketServer.DEFAULT_PORT,
                        deviceId = "manual_$ip"
                    )
                    connectToDevice(device)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop discovery when bottom sheet is closed
        viewModel.stopDiscovery()
    }
}

