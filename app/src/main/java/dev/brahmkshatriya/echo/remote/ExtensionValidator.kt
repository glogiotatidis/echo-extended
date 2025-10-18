package dev.brahmkshatriya.echo.remote

import android.util.Log
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.extensions.ExtensionLoader

class ExtensionValidator(private val extensionLoader: ExtensionLoader) {

    companion object {
        private const val TAG = "ExtensionValidator"
    }

    /**
     * Validate if the required extension is available on this device
     */
    fun validateExtension(extensionId: String): ValidationResult {
        val extension = extensionLoader.music.getExtension(extensionId)

        return if (extension != null) {
            ValidationResult.Valid
        } else {
            val available = extensionLoader.music.extensions.value.map { it.metadata.id }
            Log.w(TAG, "Extension not found: $extensionId. Available: $available")
            ValidationResult.Invalid(
                code = RemoteMessage.ErrorCode.EXTENSION_NOT_FOUND,
                message = "Extension '$extensionId' not installed on this device",
                missingExtensions = listOf(extensionId)
            )
        }
    }

    /**
     * Validate if all required extensions for a media item are available
     */
    fun validateForMediaItem(extensionId: String, item: EchoMediaItem): ValidationResult {
        return validateExtension(extensionId)
    }

    /**
     * Get list of all installed extension IDs
     */
    fun getInstalledExtensionIds(): List<String> {
        return extensionLoader.music.extensions.value.map { it.metadata.id }
    }

    /**
     * Check compatibility between two devices based on their installed extensions
     */
    fun checkCompatibility(
        localExtensions: List<String>,
        remoteExtensions: List<String>
    ): CompatibilityResult {
        val commonExtensions = localExtensions.intersect(remoteExtensions.toSet())
        val missingOnLocal = remoteExtensions.filterNot { it in localExtensions }
        val missingOnRemote = localExtensions.filterNot { it in remoteExtensions }

        Log.d(TAG, "Compatibility check:")
        Log.d(TAG, "  Common extensions: ${commonExtensions.size}")
        Log.d(TAG, "  Missing on local: ${missingOnLocal.size}")
        Log.d(TAG, "  Missing on remote: ${missingOnRemote.size}")

        return CompatibilityResult(
            isCompatible = commonExtensions.isNotEmpty(),
            commonExtensions = commonExtensions.toList(),
            missingOnLocal = missingOnLocal,
            missingOnRemote = missingOnRemote
        )
    }

    /**
     * Result of extension validation
     */
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(
            val code: RemoteMessage.ErrorCode,
            val message: String,
            val missingExtensions: List<String>
        ) : ValidationResult()
    }

    /**
     * Result of compatibility check between two devices
     */
    data class CompatibilityResult(
        val isCompatible: Boolean,
        val commonExtensions: List<String>,
        val missingOnLocal: List<String>,
        val missingOnRemote: List<String>
    )
}

