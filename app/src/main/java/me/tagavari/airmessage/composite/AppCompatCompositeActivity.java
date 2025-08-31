package me.tagavari.airmessage.composite;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import me.tagavari.airmessage.R;
import me.tagavari.airmessage.activity.Preferences;
import me.tagavari.airmessage.helper.ThemeHelper;

public class AppCompatCompositeActivity extends AppCompatActivity {
	private final List<AppCompatActivityPlugin> pluginList = new ArrayList<>();
	
	public void addPlugin(AppCompatActivityPlugin activityPlugin) {
		pluginList.add(activityPlugin);
		activityPlugin.setActivity(this);
	}
	
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		// Apply custom theme before calling super.onCreate()
		applyCustomTheme();
		super.onCreate(savedInstanceState);
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onCreate(savedInstanceState);
	}
	
	private void applyCustomTheme() {
		String currentTheme = ThemeHelper.getCurrentTheme(this);
		boolean isDarkMode = ThemeHelper.isNightMode(getResources());
		
		switch (currentTheme) {
			case ThemeHelper.themeLuxurious:
				setTheme(isDarkMode ? R.style.Theme_Luxurious_Dark : R.style.Theme_Luxurious_Light);
				break;
			case ThemeHelper.themeOceanBreeze:
				setTheme(isDarkMode ? R.style.Theme_Ocean_Dark : R.style.Theme_Ocean_Light);
				break;
			case ThemeHelper.themeSunsetGlow:
				setTheme(isDarkMode ? R.style.Theme_Sunset_Dark : R.style.Theme_Sunset_Light);
				break;
			// For standard themes (off, on, follow_system), use the default theme set in AndroidManifest.xml
		}
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onStart();
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onResume();
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onPause();
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onStop();
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		for(AppCompatActivityPlugin plugin : pluginList) plugin.onDestroy();
	}
}