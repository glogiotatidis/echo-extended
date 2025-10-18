package dev.brahmkshatriya.echo.remote.connection

import android.content.Context
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R

class PairingDialog {

    companion object {
        /**
         * Show a dialog to accept or reject an incoming connection request
         */
        fun show(
            context: Context,
            deviceName: String,
            onAccept: (trustDevice: Boolean) -> Unit,
            onReject: () -> Unit
        ) {
            var trustDevice = false

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.connection_request)
                .setMessage(context.getString(R.string.x_wants_to_connect, deviceName))
                .setPositiveButton(R.string.accept) { _, _ ->
                    onAccept(trustDevice)
                }
                .setNegativeButton(R.string.reject) { _, _ ->
                    onReject()
                }
                .setNeutralButton(R.string.trust_device) { _, _ ->
                    trustDevice = true
                    onAccept(true)
                }
                .setCancelable(false)
                .create()

            dialog.show()
        }
    }
}

