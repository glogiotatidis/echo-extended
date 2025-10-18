package dev.brahmkshatriya.echo.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.remote.RemoteViewModel.Companion.PLAYER_MODE_ENABLED
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME

class SettingsRemoteFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.remote_control)
    override val icon get() = R.drawable.ic_sensors.toResourceImageHolder()
    override val creator = { RemotePreference() }
    
    class RemotePreference : PreferenceFragmentCompat() {
        
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen
            
            PreferenceCategory(context).apply {
                title = getString(R.string.remote_player_mode)
                key = "remote_player"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)
                
                SwitchPreferenceCompat(context).apply {
                    key = PLAYER_MODE_ENABLED
                    title = getString(R.string.enable_player_mode)
                    summary = getString(R.string.enable_player_mode_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                    addPreference(this)
                }
            }
            
            PreferenceCategory(context).apply {
                title = getString(R.string.remote_controller_mode)
                key = "remote_controller"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)
                
                Preference(context).apply {
                    title = getString(R.string.discover_devices)
                    summary = getString(R.string.discover_devices_summary)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    key = "discover_devices"
                    addPreference(this)
                }
            }
        }
        
        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            val view = listView.findViewById<View>(preference.key.hashCode())
            return when (preference.key) {
                "discover_devices" -> {
                    // TODO: Open device discovery bottom sheet
                    true
                }
                else -> false
            }
        }
    }
}

