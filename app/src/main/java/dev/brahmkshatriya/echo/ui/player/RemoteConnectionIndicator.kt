package dev.brahmkshatriya.echo.ui.player

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.remote.ConnectionState
import dev.brahmkshatriya.echo.ui.remote.RemoteViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe

object RemoteConnectionIndicator {
    
    /**
     * Setup remote connection indicator
     * Shows banner when controlling remote player or being controlled
     */
    fun setup(
        lifecycleOwner: LifecycleOwner,
        context: Context,
        remoteViewModel: RemoteViewModel,
        indicatorView: TextView?
    ) {
        if (indicatorView == null) return
        
        // Observe connection state for controller mode
        lifecycleOwner.observe(remoteViewModel.connectionState) { state ->
            when (state) {
                ConnectionState.CONNECTED -> {
                    val deviceName = remoteViewModel.connectedDevice.value?.name ?: "Remote Device"
                    indicatorView.text = context.getString(R.string.controlling_x, deviceName)
                    indicatorView.isVisible = true
                }
                ConnectionState.CONNECTING -> {
                    indicatorView.text = context.getString(R.string.connecting)
                    indicatorView.isVisible = true
                }
                else -> {
                    // Check if being controlled (player mode)
                    updateForPlayerMode(lifecycleOwner, context, remoteViewModel, indicatorView)
                }
            }
        }
        
        // Also observe if being controlled (player mode)
        lifecycleOwner.observe(remoteViewModel.isBeingControlled) { isControlled ->
            if (remoteViewModel.connectionState.value != ConnectionState.CONNECTED) {
                updateForPlayerMode(lifecycleOwner, context, remoteViewModel, indicatorView)
            }
        }
    }
    
    private fun updateForPlayerMode(
        lifecycleOwner: LifecycleOwner,
        context: Context,
        remoteViewModel: RemoteViewModel,
        indicatorView: TextView
    ) {
        if (remoteViewModel.isBeingControlled.value) {
            val controllerName = remoteViewModel.controllerName.value ?: "Remote Controller"
            indicatorView.text = context.getString(R.string.controlled_by_x, controllerName)
            indicatorView.isVisible = true
        } else {
            indicatorView.isVisible = false
        }
    }
}

