package me.tagavari.airmessage.compositeplugin;

import android.content.res.Configuration;
import android.os.Bundle;
import androidx.annotation.Nullable;
import me.tagavari.airmessage.activity.Preferences;
import me.tagavari.airmessage.composite.AppCompatActivityPlugin;
import me.tagavari.airmessage.helper.ThemeHelper;

public class PluginThemeUpdater extends AppCompatActivityPlugin {
	private int currentNightMode;
	private boolean currentAMOLEDState;
	private String currentCustomTheme;
	
	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		//Recording the state
		currentNightMode = getCurrentNightMode();
		currentAMOLEDState = Preferences.getPreferenceAMOLED(getActivity());
		currentCustomTheme = ThemeHelper.getCurrentTheme(getActivity());
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		//Recreating the activity if the theme has changed
		String newCustomTheme = ThemeHelper.getCurrentTheme(getActivity());
		if(currentNightMode != getCurrentNightMode() || 
		   currentAMOLEDState != Preferences.getPreferenceAMOLED(getActivity()) ||
		   !currentCustomTheme.equals(newCustomTheme)) {
			getActivity().recreate();
		}
	}
	
	private int getCurrentNightMode() {
		return getActivity().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
	}
}