package me.tagavari.airmessage.activity;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import me.tagavari.airmessage.R;
import me.tagavari.airmessage.composite.AppCompatCompositeActivity;
import me.tagavari.airmessage.compositeplugin.PluginConnectionService;
import me.tagavari.airmessage.compositeplugin.PluginQNavigation;
import me.tagavari.airmessage.connection.ConnectionTaskManager;
import me.tagavari.airmessage.connection.MassRetrievalParams;
import me.tagavari.airmessage.constants.ColorConstants;
import me.tagavari.airmessage.contract.ContractDefaultMessagingApp;
import me.tagavari.airmessage.contract.ContractNotificationRingtoneSelector;
import me.tagavari.airmessage.data.DatabaseManager;
import me.tagavari.airmessage.data.MessagesDataHelper;
import me.tagavari.airmessage.data.SharedPreferencesManager;
import me.tagavari.airmessage.enums.ProxyType;
import me.tagavari.airmessage.flavor.FirebaseAuthBridge;
import me.tagavari.airmessage.helper.ConversationMemoryManager;
import me.tagavari.airmessage.helper.LanguageHelper;
import me.tagavari.airmessage.helper.MMSSMSHelper;
import me.tagavari.airmessage.helper.NotificationHelper;
import me.tagavari.airmessage.helper.PlatformHelper;
import me.tagavari.airmessage.helper.ThemeHelper;
import me.tagavari.airmessage.helper.WindowHelper;
import me.tagavari.airmessage.receiver.StartBootReceiver;
import me.tagavari.airmessage.service.ConnectionService;

public class Preferences extends AppCompatCompositeActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
	private static final String TAG = Preferences.class.getSimpleName();

	//Creating the plugin values
	private final PluginConnectionService pluginCS;

	public Preferences() {
		addPlugin(pluginCS = new PluginConnectionService());
		addPlugin(new PluginQNavigation());
	}

	PluginConnectionService getPluginCS() {
		return pluginCS;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		//Calling the super method
		super.onCreate(savedInstanceState);

		//Setting the content view
		setContentView(R.layout.activity_preferences);

		if(savedInstanceState == null) {
			// Create the fragment only when the activity is created for the first time.
			// ie. not after orientation changes
			Fragment fragment = getSupportFragmentManager().findFragmentByTag(SettingsFragment.FRAGMENT_TAG);
			if(fragment == null) fragment = new SettingsFragment();

			FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
			fragmentTransaction.replace(R.id.container, fragment, SettingsFragment.FRAGMENT_TAG);
			fragmentTransaction.commit();
		}

		//Enabling the toolbar and up navigation
		setSupportActionBar(findViewById(R.id.toolbar));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		//Configuring the AMOLED theme
		if(ThemeHelper.shouldUseAMOLED(this)) setDarkAMOLED();

		//Setting the status bar color
		PlatformHelper.updateChromeOSStatusBar(this);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat, PreferenceScreen preferenceScreen) {
		FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
		SettingsFragment fragment = new SettingsFragment();
		Bundle bundle = new Bundle();
		bundle.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.getKey());
		fragment.setArguments(bundle);
		fragmentTransaction.replace(R.id.container, fragment, preferenceScreen.getKey());
		fragmentTransaction.addToBackStack(preferenceScreen.getKey());
		fragmentTransaction.commit();

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		//Checking if the up button has been pressed
		if(item.getItemId() == android.R.id.home) {
			//Finishing the activity
			finish();

			//Returning true
			return true;
		}

		//Returning false
		return false;
	}

	private void setDarkAMOLED() {
		ThemeHelper.setActivityAMOLEDBase(this);
		findViewById(R.id.appbar).setBackgroundColor(ColorConstants.colorAMOLED);
	}

	public static class SettingsFragment extends PreferenceFragmentCompat {
		static final String FRAGMENT_TAG = "preferencefragment";

		//Creating the callback values
		private final ActivityResultLauncher<Uri> requestRingtoneLauncher = registerForActivityResult(new ContractNotificationRingtoneSelector(), result -> {
			if(result.getCanceled()) return;

			//Getting the selected ringtone URI
			Uri ringtoneURI = result.getSelectedURI();

			//Saving the ringtone URI
			if(ringtoneURI == null) { //"silent" selected
				PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
					.putString(getContext().getResources().getString(R.string.preference_messagenotifications_sound_key), "")
					.apply();
			} else {
				PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
					.putString(getContext().getResources().getString(R.string.preference_messagenotifications_sound_key), ringtoneURI.toString())
					.apply();
			}

			//Updating the preference summary
			Preference preference = findPreference(getResources().getString(R.string.preference_messagenotifications_sound_key));
			preference.setSummary(getRingtoneTitle(ringtoneURI));
		});
		private final ActivityResultLauncher<Void> requestDefaultMessagingAppLauncher = registerForActivityResult(new ContractDefaultMessagingApp(), granted -> {
			if(!granted) return;

			//Enabling the toggle
			SwitchPreference preference = findPreference(getResources().getString(R.string.preference_textmessage_enable_key));
			preference.setChecked(true);

			//Showing a snackbar
			Snackbar.make(getView(), R.string.message_textmessageimport, Snackbar.LENGTH_LONG).show();
		});
		private final ActivityResultLauncher<String[]> requestMessagingPermissionsLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
			//Check if all permissions are granted
			if(permissions.values().stream().allMatch((granted) -> granted)) {
				//Enabling the toggle
				SwitchPreference preference = findPreference(getResources().getString(R.string.preference_textmessage_enable_key));
				preference.setChecked(true);

				//Starting the import service (started automatically by broadcast listener DefaultMessagingAppChangedReceiver)
				//getActivity().startService(new Intent(getActivity(), SystemMessageImportService.class).setAction(SystemMessageImportService.selfIntentActionImport));

				//Showing a snackbar
				Snackbar.make(getView(), R.string.message_textmessageimport, Snackbar.LENGTH_LONG).show();
			} else {
				//Showing a snackbar
				Snackbar.make(getView(), R.string.message_permissionrejected, Snackbar.LENGTH_LONG)
					.setAction(R.string.screen_settings, view -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getActivity().getPackageName()))))
					.show();
			}
		});

		//Creating the subscription values
		private Disposable syncSubscription;

		// Google Sign-In for AI features
		private final ActivityResultLauncher<Intent> googleSignInLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(),
			result -> handleGoogleSignInResult(result)
		);

		Preference.OnPreferenceClickListener notificationSoundClickListener = preference -> {
			requestRingtoneLauncher.launch(getNotificationSound(getContext()));

			return true;
		};
		Preference.OnPreferenceChangeListener startOnBootChangeListener = (preference, value) -> {
			//Updating the service state
			updateConnectionServiceBootEnabled(getActivity(), (boolean) value);

			//Returning true (to allow the change)
			return true;
		};
		/* Preference.OnPreferenceChangeListener useForegroundServiceChangeListener = (preference, value) -> {
			//Casting the value
			boolean boolValue = (boolean) value;
			//Updating the service
			ConnectionService service = ConnectionService.getInstance();
			if(service != null) service.setForegroundState(boolValue);

			//Disabling the "start on boot" switch if the service is now a background service
			if(boolValue) ((SwitchPreferenceCompat) findPreference(getResources().getString(R.string.preference_server_disconnectionnotification_key))).setChecked(true);
			else ((SwitchPreferenceCompat) findPreference(getResources().getString(R.string.preference_server_connectionboot_key))).setChecked(false);

			//Updating the state of the dependant items
			findPreference(getResources().getString(R.string.preference_server_connectionboot_key)).setEnabled(boolValue);
			findPreference(getResources().getString(R.string.preference_server_disconnectionnotification_key)).setEnabled(!boolValue);

			//Returning true (to allow the change)
			return true;
		}; */
		Preference.OnPreferenceClickListener memoryManageClickListener = preference -> {
			//Creating a dialog to show memory contents and allow clearing
			AlertDialog dialog = new MaterialAlertDialogBuilder(getActivity())
					.setTitle("Conversation Memory")
					.setMessage("View and manage stored contextual information from conversations.")
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton("View Details", (dialogInterface, which) -> {
						//Show detailed memory viewer
						showMemoryDetailsDialog();
					})
					.setNeutralButton("Clear All", (dialogInterface, which) -> {
						//Confirm clearing memory
						new MaterialAlertDialogBuilder(getActivity())
								.setTitle("Clear Memory")
								.setMessage("Are you sure you want to clear all stored conversation memory? This cannot be undone.")
								.setNegativeButton(android.R.string.cancel, null)
								.setPositiveButton("Clear", (d, w) -> {
									//Clear memory
									ConversationMemoryManager.clearAllMemories(getActivity())
											.observeOn(AndroidSchedulers.mainThread())
											.subscribe(
													() -> Toast.makeText(getActivity(), "Conversation memory cleared", Toast.LENGTH_SHORT).show(),
													error -> Toast.makeText(getActivity(), "Failed to clear memory", Toast.LENGTH_SHORT).show()
											);
								})
								.show();
					})
					.create();
			
			//Displaying the dialog
			dialog.show();
			
			//Returning true
			return true;
		};
		
		/**
		 * Shows a dialog with detailed memory information
		 */
		private void showMemoryDetailsDialog() {
			// Check if fragment is still attached
			if (getActivity() == null || !isAdded()) {
				Log.d("Preferences", "Fragment not available for memory details dialog");
				return;
			}
			
			ConversationMemoryManager.getAllMemories(getActivity())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(
					memories -> {
						// Check if fragment is still attached when callback executes
						if (getActivity() == null || !isAdded()) {
							Log.d("Preferences", "Fragment no longer available when memory details loaded");
							return;
						}
						
						StringBuilder content = new StringBuilder();
						
						if (memories.isEmpty()) {
							content.append("No conversation memories stored yet.\n\n");
							content.append("Messages will be analyzed and stored as you chat to help improve smart replies.");
						} else {
							content.append("Stored memories: ").append(memories.size()).append("\n\n");
							
							for (ConversationMemoryManager.MemoryItem memory : memories) {
								content.append("From: ").append(memory.getConversationTitle() != null ? memory.getConversationTitle() : "Unknown conversation").append("\n");
								content.append("Info: ").append(memory.getExtractedInfo()).append("\n");
								content.append("Category: ").append(memory.getCategory()).append("\n");
								content.append("Date: ").append(new java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(memory.getTimestamp()))).append("\n\n");
							}
						}
						
						new MaterialAlertDialogBuilder(getActivity())
							.setTitle("Conversation Memory Details")
							.setMessage(content.toString())
							.setPositiveButton("OK", null)
							.setNegativeButton("Process Existing", (d, w) -> {
								processExistingMessages();
							})
							.setNeutralButton("Manage", (d, w) -> {
								showMemoryManagementOptions();
							})
							.show();
					},
					error -> {
						Toast.makeText(getActivity(), "Failed to load memory details", Toast.LENGTH_SHORT).show();
					}
				);
		}
		
		/**
		 * Shows memory management options (Clear All, Clear Old)
		 */
		private void showMemoryManagementOptions() {
			new MaterialAlertDialogBuilder(getActivity())
				.setTitle("Memory Management")
				.setMessage("Choose a memory management action:")
				.setPositiveButton("Clear All", (d, w) -> {
					new MaterialAlertDialogBuilder(getActivity())
						.setTitle("Clear All Memory")
						.setMessage("Are you sure you want to clear all stored conversation memory? This cannot be undone.")
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton("Clear All", (d2, w2) -> {
							ConversationMemoryManager.clearAllMemories(getActivity())
								.observeOn(AndroidSchedulers.mainThread())
								.subscribe(
									() -> {
										Toast.makeText(getActivity(), "All conversation memory cleared", Toast.LENGTH_SHORT).show();
										showMemoryDetailsDialog(); // Refresh the dialog
									},
									error -> Toast.makeText(getActivity(), "Failed to clear memory", Toast.LENGTH_SHORT).show()
								);
						})
						.show();
				})
				.setNegativeButton("Clear Old", (d, w) -> {
					int messageLimit = ConversationMemoryManager.getMessageLimit(getActivity());
					new MaterialAlertDialogBuilder(getActivity())
						.setTitle("Clear Old Memories")
						.setMessage("This will remove memories beyond the current limit of " + messageLimit + " messages, keeping only the most recent ones.")
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton("Clear Old", (d2, w2) -> {
							clearOldMemories();
						})
						.show();
				})
				.setNeutralButton("Cancel", null)
				.show();
		}
		
		/**
		 * Clears old memories beyond the current message limit
		 */
		private void clearOldMemories() {
			ConversationMemoryManager.clearOldMemories(getActivity())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(
					() -> {
						Toast.makeText(getActivity(), "Cleared old memories beyond current limit", Toast.LENGTH_SHORT).show();
						showMemoryDetailsDialog(); // Refresh the dialog
					},
					error -> {
						Toast.makeText(getActivity(), "Failed to clear old memories", Toast.LENGTH_SHORT).show();
					}
				);
		}
		
		/**
		 * Process existing messages to gather contextual data
		 */
		private void processExistingMessages() {
			int messageLimit = ConversationMemoryManager.getMessageLimit(getActivity());
			
			new MaterialAlertDialogBuilder(getActivity())
				.setTitle("Process Existing Messages")
				.setMessage("This will analyze the last " + messageLimit + " messages from each conversation to extract contextual information using AI.\n\nThis process may take several minutes and requires an active Ollama connection. The app will remain usable during processing.")
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton("Process", (d, w) -> {
					Toast.makeText(getActivity(), "Processing existing messages... This may take several minutes", Toast.LENGTH_LONG).show();
					
					// Use the bulk processing method from ConversationMemoryManager
					ConversationMemoryManager.processExistingMessages(getActivity())
						.observeOn(AndroidSchedulers.mainThread())
						.subscribe(
							result -> {
								// Show detailed completion dialog
								showProcessingCompletionDialog(result);
							},
							error -> {
								if (getActivity() != null && isAdded()) {
									Toast.makeText(getActivity(), "Error processing messages: " + error.getMessage(), Toast.LENGTH_LONG).show();
								} else {
									Log.d("Preferences", "Fragment not available for error toast, logging: " + error.getMessage());
								}
							}
						);
				})
				.show();
		}
		
		/**
		 * Shows a detailed completion dialog with processing results
		 */
		private void showProcessingCompletionDialog(ConversationMemoryManager.ProcessingResult result) {
			// Check if fragment is still attached and activity is available
			if (getActivity() == null || !isAdded() || getActivity().isFinishing()) {
				Log.d("Preferences", "Fragment not available for completion dialog, skipping");
				return;
			}
			
			// Format processing time
			String processingTime;
			long timeMs = result.getProcessingTimeMs();
			if (timeMs < 1000) {
				processingTime = timeMs + "ms";
			} else if (timeMs < 60000) {
				processingTime = String.format("%.1f seconds", timeMs / 1000.0);
			} else {
				processingTime = String.format("%.1f minutes", timeMs / 60000.0);
			}
			
			// Create detailed summary
			StringBuilder summary = new StringBuilder();
			summary.append("✅ Processing Complete!\n\n");
			summary.append("📊 Results Summary:\n");
			summary.append("• Conversations processed: ").append(result.getConversationsProcessed()).append("\n");
			summary.append("• Messages analyzed: ").append(result.getMessagesProcessed()).append("\n");
			summary.append("• Memories extracted: ").append(result.getMemoriesExtracted()).append("\n");
			summary.append("• Processing time: ").append(processingTime).append("\n\n");
			
			if (result.getMemoriesExtracted() > 0) {
				summary.append("🎉 Success! ").append(result.getMemoriesExtracted()).append(" contextual memories have been extracted and are now available for smart replies and message enhancement.");
			} else {
				summary.append("ℹ️ No new memories were extracted. This could be due to:\n");
				summary.append("• Messages already processed\n");
				summary.append("• No qualifying messages (too short or empty)\n");
				summary.append("• Ollama server not responding");
			}
			
			new MaterialAlertDialogBuilder(getActivity())
				.setTitle("Memory Processing Complete")
				.setMessage(summary.toString())
				.setPositiveButton("View Details", (d, w) -> {
					if (getActivity() != null && isAdded()) {
						showMemoryDetailsDialog(); // Show the memory details dialog
					}
				})
				.setNegativeButton("OK", null)
				.show();
		}
		
		/**
		 * Updates the memory limit SeekBar summary to show the current value
		 */
		private void updateMemoryLimitSummary(androidx.preference.SeekBarPreference seekBarPreference) {
			int currentValue = ConversationMemoryManager.getMessageLimit(getActivity());
			String summary = "Store contextual information from the last " + currentValue + " messages";
			if (currentValue > 500) {
				summary += " ⚠️ Large values may take longer to process";
			}
			seekBarPreference.setSummary(summary);
		}
		
		Preference.OnPreferenceClickListener deleteAttachmentsClickListener = preference -> {
			//Creating a dialog
			AlertDialog dialog = new MaterialAlertDialogBuilder(getActivity())
					//Setting the name
					.setMessage(R.string.message_confirm_deleteattachments)
					//Setting the negative button
					.setNegativeButton(android.R.string.cancel, (DialogInterface dialogInterface, int which) -> {
						//Dismissing the dialog
						dialogInterface.dismiss();
					})
					//Setting the positive button
					.setPositiveButton(R.string.action_delete, (DialogInterface dialogInterface, int which) -> {
						//Deleting the attachment files on disk and in the database
						MessagesDataHelper.deleteAMBAttachments(getContext()).subscribe();

						//Displaying a snackbar
						Snackbar.make(getView(), R.string.message_confirm_deleteattachments_started, Snackbar.LENGTH_SHORT).show();
					})
					//Creating the dialog
					.create();

			//Showing the dialog
			dialog.show();

			//Returning true
			return true;
		};
		Preference.OnPreferenceClickListener syncMessagesClickListener = preference -> {
			//Checking if the connection manager
			PluginConnectionService pluginCS = getPluginCS();
			if(pluginCS == null || !pluginCS.isServiceBound() || !pluginCS.getConnectionManager().isConnected()) {
				//Displaying a snackbar
				Snackbar.make(getView(), R.string.message_serverstatus_noconnection, Snackbar.LENGTH_LONG).show();

				//Returning
				return true;
			}

			//Checking if there is already a mass retrieval in progress
			if(pluginCS.getConnectionManager().isMassRetrievalInProgress()) {
				//Displaying a snackbar
				Snackbar.make(getView(), R.string.message_confirm_resyncmessages_inprogress, Snackbar.LENGTH_SHORT).show();

				//Returning
				return true;
			}

			//Creating a dialog
			AlertDialog dialog = new MaterialAlertDialogBuilder(getActivity())
					//Setting the text
					.setTitle(R.string.message_confirm_resyncmessages)
					.setMessage(R.string.message_confirm_resyncmessages_description)
					//Setting the negative button
					.setNegativeButton(android.R.string.cancel, (DialogInterface dialogInterface, int which) -> dialogInterface.dismiss())
					//Setting the positive button
					.setPositiveButton(R.string.action_sync, (DialogInterface dialogInterface, int which) -> requestSyncMessages(new MassRetrievalParams()))
					.setNeutralButton(R.string.action_advanced, (DialogInterface dialogInterface, int which) -> {
						dialogInterface.dismiss();

						//Creating the dialog manager
						AdvancedSyncDialogManager _dialogManager = new AdvancedSyncDialogManager(getLayoutInflater());

						//Creating the dialog
						AlertDialog _dialog = new MaterialAlertDialogBuilder(getActivity())
								.setTitle(R.string.message_confirm_resyncmessages_advanced)
								.setView(_dialogManager.getView())
								.setNegativeButton(android.R.string.cancel, (DialogInterface _dialogInterface, int _which) -> _dialogInterface.dismiss())
								.setPositiveButton(R.string.action_sync, (DialogInterface _dialogInterface, int _which) -> _dialogManager.startSync())
								.create();

						//Setting the dialog state updater
						_dialogManager.setStateListener(state -> _dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(state));

						//Showing the dialog
						_dialog.show();
					})
					.create();

			//Showing the dialog
			dialog.show();

			//Returning true
			return true;
		};
		
		// AI Settings click listener
		Preference.OnPreferenceClickListener aiSettingsClickListener = preference -> {
			Intent intent = new Intent(getActivity(), PreferencesAI.class);
			startActivity(intent);
			return true;
		};
		
		Preference.OnPreferenceChangeListener themeChangeListener = (preference, newValue) -> {
			//Applying the dark mode
			ThemeHelper.applyDarkMode((String) newValue);

			//Recreating the activity
			getActivity().recreate();

			//Accepting the change
			return true;
		};
		@RequiresApi(api = Build.VERSION_CODES.N)
		Preference.OnPreferenceChangeListener textIntegrationChangeListener = (preference, newValue) -> {
			//Checking if the preference is enabled
			if(((SwitchPreference) preference).isChecked()) {
				//Launching the app details screen
				try {
					startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); //Manage default apps
				} catch(ActivityNotFoundException exception) {
					startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getActivity().getPackageName()))); //App details page (fallback)
				}
			} else {
				if(MMSSMSHelper.isDefaultMessagingApp(getContext())) {
					//Requesting permissions
					requestMessagingPermissionsLauncher.launch(new String[]{Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS, Manifest.permission.READ_PHONE_STATE});
				} else {
					//Requesting to be the default messaging app
					requestDefaultMessagingAppLauncher.launch(null);
				}
			}

			//Returning false (to prevent the system from changing the option)
			return false;
		};
		Preference.OnPreferenceClickListener directResetClickListener = preference -> {
			//Creating a dialog
			AlertDialog dialog = new MaterialAlertDialogBuilder(getActivity())
					//Setting the name
					.setMessage(R.string.message_reset_direct)
					//Setting the negative button
					.setNegativeButton(android.R.string.cancel, null)
					//Setting the positive button
					.setPositiveButton(R.string.action_switchtoaccount, (DialogInterface dialogInterface, int which) -> {
						resetConfiguration();
					})
					//Creating the dialog
					.create();

			//Showing the dialog
			dialog.show();

			//Returning true
			return true;
		};
		Preference.OnPreferenceClickListener connectResetClickListener = preference -> {
			//Creating a dialog
			AlertDialog dialog = new MaterialAlertDialogBuilder(getActivity())
					//Setting the name
					.setMessage(R.string.message_reset_connect)
					//Setting the negative button
					.setNegativeButton(android.R.string.cancel, null)
					//Setting the positive button
					.setPositiveButton(R.string.action_signout, (DialogInterface dialogInterface, int which) -> {
						resetConfiguration();
					})
					//Creating the dialog
					.create();

			//Showing the dialog
			dialog.show();

			//Returning true
			return true;
		};

		// AI Features listeners
		Preference.OnPreferenceClickListener aiAuthClickListener = preference -> {
			try {
				me.tagavari.airmessage.helper.GeminiHelper geminiHelper = me.tagavari.airmessage.helper.GeminiHelper.getInstance();
				boolean isAuthenticated = geminiHelper.isUserAuthenticated();

				if (isAuthenticated) {
					// User is already authenticated - show status and sign out option
					me.tagavari.airmessage.flavor.FirebaseAuthBridge.getUserSummary();
					String userInfo = me.tagavari.airmessage.flavor.FirebaseAuthBridge.getUserSummary();
					String message = userInfo != null ?
						"Signed in as: " + userInfo + "\n\nYou can now use all AI features." :
						"You are signed in and ready to use AI features.";

					new MaterialAlertDialogBuilder(getActivity())
						.setTitle(R.string.preference_ai_auth_title)
						.setMessage(message)
						.setPositiveButton(android.R.string.ok, null)
						.setNegativeButton("Sign Out", (dialog, which) -> {
							// Sign out the user
							try {
								me.tagavari.airmessage.flavor.FirebaseAuthBridge.signOut();
								// Update the summary to reflect the change
								updateAIAuthSummary(preference);
								// Show confirmation
								new MaterialAlertDialogBuilder(getActivity())
									.setTitle("Signed Out")
									.setMessage("You have been signed out. AI features are now disabled.")
									.setPositiveButton(android.R.string.ok, null)
									.show();
							} catch (Exception e) {
								// Handle sign-out error
								new MaterialAlertDialogBuilder(getActivity())
									.setTitle("Error")
									.setMessage("Unable to sign out. Please try again.")
									.setPositiveButton(android.R.string.ok, null)
									.show();
							}
						})
						.show();
				} else {
					// User needs to sign in - launch sign-in flow
					if (me.tagavari.airmessage.flavor.FirebaseAuthBridge.isSupported()) {
						launchGoogleSignIn(preference);
					} else {
						new MaterialAlertDialogBuilder(getActivity())
							.setTitle("Setup API Key")
							.setMessage("To use AI features, add your Google AI API key to secrets.properties file.\n\nGet a free key at: https://makersuite.google.com/app/apikey")
							.setPositiveButton(android.R.string.ok, null)
							.show();
					}
				}
			} catch (Exception e) {
				// Error checking authentication status
				new MaterialAlertDialogBuilder(getActivity())
					.setTitle("AI Features")
					.setMessage("AI features require additional setup. Please add your Google AI API key to secrets.properties file.")
					.setPositiveButton(android.R.string.ok, null)
					.show();
			}

			return true;
		};

		Preference.OnPreferenceChangeListener aiEnabledChangeListener = (preference, newValue) -> {
			boolean enabled = (boolean) newValue;

			if (enabled) {
				// Check if user is authenticated before enabling
				try {
					me.tagavari.airmessage.helper.GeminiHelper geminiHelper = me.tagavari.airmessage.helper.GeminiHelper.getInstance();
					if (!geminiHelper.isUserAuthenticated()) {
						// Show message about API key configuration
						new MaterialAlertDialogBuilder(getActivity())
							.setTitle("API Key Required")
							.setMessage("To use Gemini AI features, please add your Google AI API key to secrets.properties.\n\nGet a free API key at: https://makersuite.google.com/app/apikey")
							.setPositiveButton(android.R.string.ok, null)
							.show();
						return false; // Don't enable the preference
					}
				} catch (Exception e) {
					// Error checking authentication - allow user to try enabling anyway
					new MaterialAlertDialogBuilder(getActivity())
						.setTitle("AI Features")
						.setMessage("AI features require additional setup. You can enable this setting, but features may not work until properly configured.")
						.setPositiveButton(android.R.string.ok, null)
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				}
			}

			return true;
		};

		Preference.OnPreferenceChangeListener autoDownloadAttachmentsChangeListener = (preference, newValue) -> {
			//If the user disables auto-download attachments, clear the status in the database
			if(!((boolean) newValue)) {
				Completable.fromAction(() -> DatabaseManager.getInstance().clearAutoDownloaded())
					.subscribeOn(Schedulers.single()).subscribe();
			}

			return true;
		};

		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			//Adding the preferences
			addPreferencesFromResource(R.xml.preferences_notification);
			addPreferencesFromResource(R.xml.preferences_main);
			int accountType = SharedPreferencesManager.getProxyType(getContext());
			if(accountType == ProxyType.direct) addPreferencesFromResource(R.xml.preferences_server);
			else if(accountType == ProxyType.connect) addPreferencesFromResource(R.xml.preferences_account);
			addPreferencesFromResource(R.xml.preferences_footer);

			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				//Creating the notification channel intent
				Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
				intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.notificationChannelMessage);
				intent.putExtra(Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());

				//Setting the listener
				findPreference(getResources().getString(R.string.preference_messagenotifications_key)).setIntent(intent);
			}

			{
				//Setting the theme options based on the system version
				ListPreference themePreference = findPreference(getResources().getString(R.string.preference_appearance_theme_key));
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) themePreference.setEntries(R.array.preference_appearance_theme_entries_androidQ);
				else themePreference.setEntries(R.array.preference_appearance_theme_entries_old);

				//Updating the AMOLED switch option
				SwitchPreference amoledSwitch = findPreference(getResources().getString(R.string.preference_appearance_amoled_key));
				amoledSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
					//Recreating the activity
					getActivity().recreate();

					return true;
				});
				amoledSwitch.setEnabled(!themePreference.getValue().equals(ThemeHelper.darkModeLight));

				//Checking if the device is running below Android 7.0 (API 24), or doesn't support telephony
				if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
					//Removing the text message preference group
					PreferenceGroup preferenceGroup = findPreference(getResources().getString(R.string.preferencegroup_textmessage_key));
					getPreferenceScreen().removePreference(preferenceGroup);
				} else {
					//Updating the text message integration option
					SwitchPreference textIntegrationSwitch = findPreference(getResources().getString(R.string.preference_textmessage_enable_key));
					textIntegrationSwitch.setOnPreferenceChangeListener(textIntegrationChangeListener);
				}
			}

			//Setting the listeners
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_messagenotifications_sound_key));
				if(preference != null) {
					//Setting the preference click listener
					preference.setOnPreferenceClickListener(notificationSoundClickListener);

					//Setting the summary
					preference.setSummary(getRingtoneTitle(getNotificationSound(getContext())));
				}
			}
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_server_connectionboot_key));
				if(preference != null) preference.setOnPreferenceChangeListener(startOnBootChangeListener);
			}
			findPreference(getResources().getString(R.string.preference_storage_deleteattachments_key)).setOnPreferenceClickListener(deleteAttachmentsClickListener);
			findPreference(getResources().getString(R.string.preference_server_downloadmessages_key)).setOnPreferenceClickListener(syncMessagesClickListener);
			
			// AI Settings button
			{
				Preference aiSettingsPreference = findPreference(getResources().getString(R.string.preference_ai_settings_key));
				if(aiSettingsPreference != null) {
					aiSettingsPreference.setOnPreferenceClickListener(aiSettingsClickListener);
				}
			}
			
			//Setting up memory management click listener
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_ai_memory_manage_key));
				if(preference != null) preference.setOnPreferenceClickListener(memoryManageClickListener);
			}
			
			//Setting up memory limit SeekBar to show current value
			{
				androidx.preference.SeekBarPreference seekBarPreference = findPreference("conversation_memory_message_limit");
				if(seekBarPreference != null) {
					// Update summary to show current value
					updateMemoryLimitSummary(seekBarPreference);
					
					// Listen for changes to update the summary
					seekBarPreference.setOnPreferenceChangeListener((preference, newValue) -> {
						// Round to nearest 10 for increment-like behavior
						int value = (Integer) newValue;
						int roundedValue = Math.round(value / 10.0f) * 10;
						
						// Update summary with warning if needed
						String summary = "Store contextual information from the last " + roundedValue + " messages";
						if (roundedValue > 500) {
							summary += " ⚠️ Large values may take longer to process";
						}
						seekBarPreference.setSummary(summary);
						
						// If we rounded the value, update the preference
						if (roundedValue != value) {
							seekBarPreference.setValue(roundedValue);
						}
						
						return true;
					});
				}
			}
			findPreference(getResources().getString(R.string.preference_appearance_theme_key)).setOnPreferenceChangeListener(themeChangeListener);
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_account_accountdetails_key));
				if(preference != null) {
					String summary = FirebaseAuthBridge.getUserSummary();
					preference.setSummary(summary);
				}
			}
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_server_reset_key));
				if(preference != null) {
					if(FirebaseAuthBridge.isSupported()) {
						preference.setOnPreferenceClickListener(directResetClickListener);
					} else {
						getPreferenceScreen().removePreferenceRecursively(preference.getKey());
					}
				}
			}
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_account_reset_key));
				if(preference != null) preference.setOnPreferenceClickListener(connectResetClickListener);
			}

			// AI Features setup
			{
				Preference aiAuthPreference = findPreference(getResources().getString(R.string.preference_ai_auth_key));
				if(aiAuthPreference != null) {
					aiAuthPreference.setOnPreferenceClickListener(aiAuthClickListener);
					updateAIAuthSummary(aiAuthPreference);
				}

				// Ollama scan models button
				Preference ollamaScanPreference = findPreference(getResources().getString(R.string.preference_ollama_scan_models_key));
				if(ollamaScanPreference != null) {
					ollamaScanPreference.setOnPreferenceClickListener(ollamaScanModelsClickListener);
				}

				// Load previously scanned models if available
				loadPreviouslyScannedModels();

				SwitchPreference aiEnabledSwitch = findPreference(getResources().getString(R.string.preference_ai_enabled_key));
				if(aiEnabledSwitch != null) {
					aiEnabledSwitch.setOnPreferenceChangeListener(aiEnabledChangeListener);
				}
			}
		}

		@Override
		public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
			super.onViewCreated(view, savedInstanceState);

			//Enforcing the maximum content width
			WindowHelper.enforceContentWidthView(getResources(), getListView());

			//Setting the list padding
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				View recyclerView = view.findViewById(R.id.recycler_view);
				ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
					recyclerView.setPadding(insets.getSystemWindowInsetLeft(), recyclerView.getPaddingTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
					return insets.consumeSystemWindowInsets();
				});
			}

			//if(Preferences.getPreferenceAMOLED(getContext())) setDarkAMOLED();
		}

		@Override
		public void onResume() {
			//Calling the super method
			super.onResume();

			//Updating the server URL
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_server_serverdetails_key));
				if(preference != null) updateServerURL(preference);
			}

			//Updating the notification preference
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updateMessageNotificationPreference(findPreference(getResources().getString(R.string.preference_messagenotifications_key)));

			//Updating the AI authentication preference
			{
				Preference preference = findPreference(getResources().getString(R.string.preference_ai_auth_key));
				if(preference != null) updateAIAuthSummary(preference);
			}
		}

		@Override
		public void onDestroyView() {
			super.onDestroyView();

			//Cancelling task subscriptions
			if(syncSubscription != null && !syncSubscription.isDisposed()) syncSubscription.dispose();
		}

		void setDarkAMOLEDSamsung() {
			//Configuring the list
			RecyclerView list = getListView();

			list.setBackgroundResource(R.drawable.background_amoledsamsung);
			list.setClipToOutline(true);
			list.invalidate();
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		private void updateMessageNotificationPreference(Preference preference) {
			//Getting the summary
			String summary;
			switch(((NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE)).getNotificationChannel(NotificationHelper.notificationChannelMessage).getImportance()) {
				case 0:
					summary = getResources().getString(R.string.notificationchannelimportance_0);
					break;
				case 1:
					summary = getResources().getString(R.string.notificationchannelimportance_1);
					break;
				case 2:
					summary = getResources().getString(R.string.notificationchannelimportance_2);
					break;
				case 3:
					summary = getResources().getString(R.string.notificationchannelimportance_3);
					break;
				case 4:
					summary = getResources().getString(R.string.notificationchannelimportance_4);
					break;
			/* case 5:
				summary = getResources().getString(R.string.notificationchannelimportance_5);
				break; */
				default:
					summary = getResources().getString(R.string.part_unknown);
					break;
			}

			//Setting the summary
			preference.setSummary(summary);
		}

		/* private void updateRingtonePreference(Preference preference) {
			//Getting the ringtone name
			String ringtoneUri = PreferenceManager.getDefaultSharedPreferences(getActivity()).getString(preference.getKey(), Constants.defaultNotificationSound);
			Ringtone ringtone = RingtoneManager.getRingtone(getActivity(), Uri.parse(ringtoneUri));
			String ringtoneName = ringtone == null ? getResources().getString(R.string.part_unknown) : ringtone.getTitle(getActivity());

			//Setting the summary
			preference.setSummary(ringtoneName);
		} */

		private void updateTextNumberPreference(EditTextPreference editTextPreference, int summaryString) {
			//Setting the summary
			editTextPreference.setSummary(getResources().getString(summaryString, Integer.parseInt(editTextPreference.getText())));
		}

		private void updateListPreference(ListPreference listPreference) {
			//Setting the summary
			listPreference.setSummary(listPreference.getEntry());
		}

		private void updateServerURL(Preference preference) {
			//Setting the summary
			try {
				preference.setSummary(SharedPreferencesManager.getDirectConnectionAddress(getContext()));
			} catch(GeneralSecurityException | IOException exception) {
				exception.printStackTrace();
				preference.setSummary(R.string.part_unknown);
			}
		}

		private void updateAIAuthSummary(Preference preference) {
			try {
				me.tagavari.airmessage.helper.GeminiHelper geminiHelper = me.tagavari.airmessage.helper.GeminiHelper.getInstance();
				boolean isAuthenticated = geminiHelper.isUserAuthenticated();

				if (isAuthenticated) {
					String userInfo = me.tagavari.airmessage.flavor.FirebaseAuthBridge.getUserSummary();
					preference.setSummary(userInfo != null ? userInfo : "Signed in and ready to use AI features");
				} else {
					preference.setSummary("Sign in required to use AI features");
				}
			} catch (Exception e) {
				preference.setSummary("AI features require additional setup");
			}
		}

		private void launchGoogleSignIn(Preference preference) {
			// Show API key setup instructions instead of sign-in
			new MaterialAlertDialogBuilder(getActivity())
				.setTitle("Setup Google Gemini API")
				.setMessage("To use Gemini AI features:\n\n" +
					"1. Get a free API key at:\n" +
					"   https://makersuite.google.com/app/apikey\n\n" +
					"2. Add it to your secrets.properties file:\n" +
					"   GEMINI_API_KEY=your_key_here\n\n" +
					"3. Rebuild the app\n\n" +
					"The free tier includes 15 requests/min and 1,500 requests/day.")
				.setPositiveButton("Got it", null)
				.setNegativeButton("Use Ollama instead", (dialog, which) -> {
					// Switch to Ollama provider
					ListPreference aiProviderPref = findPreference(getString(R.string.preference_features_aiprovider_key));
					if (aiProviderPref != null) {
						aiProviderPref.setValue("ollama");
					}
				})
				.show();
		}

		private void handleGoogleSignInResult(ActivityResult result) {
			// Placeholder for now
		}

		Preference.OnPreferenceClickListener ollamaScanModelsClickListener = preference -> {
			scanOllamaModels();
			return true;
		};

		private void scanOllamaModels() {
			String hostname = getPreferenceOllamaHostname(getContext());
			if (hostname.isEmpty()) {
				Toast.makeText(getContext(), "Please enter Ollama server hostname first", Toast.LENGTH_LONG).show();
				return;
			}

			String baseUrl = getOllamaBaseUrl(getContext());

			// Show progress dialog
			AlertDialog progressDialog = new MaterialAlertDialogBuilder(getActivity())
				.setTitle("Scanning Models")
				.setMessage("Checking available models on " + hostname + "...")
				.setCancelable(false)
				.create();
			progressDialog.show();

			// Make API call to get models
			new Thread(() -> {
				try {
					OkHttpClient client = new OkHttpClient.Builder()
						.connectTimeout(10, TimeUnit.SECONDS)
						.readTimeout(30, TimeUnit.SECONDS)
						.build();

					Request request = new Request.Builder()
						.url(baseUrl + "/api/tags")
						.get()
						.build();

					Response response = client.newCall(request).execute();
					String responseBody = response.body().string();

					getActivity().runOnUiThread(() -> {
						progressDialog.dismiss();
						if (response.isSuccessful()) {
							updateModelList(responseBody);
						} else {
							Toast.makeText(getContext(), "Failed to connect to Ollama server: " + response.code(), Toast.LENGTH_LONG).show();
						}
					});
				} catch (Exception e) {
					getActivity().runOnUiThread(() -> {
						progressDialog.dismiss();
						Toast.makeText(getContext(), "Error connecting to Ollama server: " + e.getMessage(), Toast.LENGTH_LONG).show();
					});
				}
			}).start();
		}

		private void updateModelList(String responseBody) {
			try {
				// Parse JSON response to extract model names
				// Simple JSON parsing - looking for "name" fields in models array
				java.util.List<String> modelNames = new java.util.ArrayList<>();
				java.util.List<String> modelDisplayNames = new java.util.ArrayList<>();

				// Basic JSON parsing without external library
				String[] lines = responseBody.split("\"name\":");
				for (int i = 1; i < lines.length; i++) {
					String line = lines[i].trim();
					if (line.startsWith("\"")) {
						int endIndex = line.indexOf("\"", 1);
						if (endIndex > 1) {
							String modelName = line.substring(1, endIndex);
							modelNames.add(modelName);
							modelDisplayNames.add(modelName);
						}
					}
				}

				if (modelNames.isEmpty()) {
					Toast.makeText(getContext(), "No models found on server", Toast.LENGTH_LONG).show();
					return;
				}

				// Save the scanned models to SharedPreferences
				String[] modelNamesArray = modelNames.toArray(new String[0]);
				String[] modelDisplayNamesArray = modelDisplayNames.toArray(new String[0]);
				saveOllamaScannedModels(getContext(), modelNamesArray, modelDisplayNamesArray);

				// Update the ListPreference with found models
				updateModelListPreference(modelNamesArray, modelDisplayNamesArray);

				Toast.makeText(getContext(), "Found " + modelNames.size() + " models", Toast.LENGTH_SHORT).show();
			} catch (Exception e) {
				Toast.makeText(getContext(), "Error parsing server response", Toast.LENGTH_LONG).show();
			}
		}

		private void updateModelListPreference(String[] modelNames, String[] modelDisplayNames) {
			ListPreference modelPreference = findPreference(getResources().getString(R.string.preference_ollama_model_key));
			if (modelPreference != null) {
				CharSequence[] entries = modelDisplayNames;
				CharSequence[] entryValues = modelNames;

				modelPreference.setEntries(entries);
				modelPreference.setEntryValues(entryValues);

				// Set default to first model if none selected
				if ((modelPreference.getValue() == null || modelPreference.getValue().isEmpty()) && modelNames.length > 0) {
					modelPreference.setValue(modelNames[0]);
				}
			}
		}

		private void loadPreviouslyScannedModels() {
			if (hasScannedModels(getContext())) {
				String[] modelNames = getScannedModelNames(getContext());
				String[] modelDisplayNames = getScannedModelDisplayNames(getContext());

				if (modelNames.length > 0 && modelDisplayNames.length > 0) {
					updateModelListPreference(modelNames, modelDisplayNames);
				}
			}
		}

		private class AdvancedSyncDialogManager {
			private static final float disabledAlpha = 0.5F;

			private final View view;
			private Consumer<Boolean> stateListener = null;

			private int currentSliderID;

			private final Slider sliderDateMessages;
			private final Slider sliderDateAttachments;

			private final Slider sliderAttachmentSize;
			private final TextView labelAttachmentSize;
			private final ViewGroup viewgroupAttachmentFilters;

			private final AdvancedSyncTime[] advancedSyncTimes = {
					new AdvancedSyncTime(60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 1), //1 hour
					new AdvancedSyncTime(2 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 2), //2 hour
					new AdvancedSyncTime(3 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 3), //3 hour
					new AdvancedSyncTime(4 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 4), //4 hour
					new AdvancedSyncTime(8 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 8), //8 hour
					new AdvancedSyncTime(12 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_hour, 12), //12 hour
					new AdvancedSyncTime(24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 1), //1 day
					new AdvancedSyncTime(2 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 2), //2 day
					new AdvancedSyncTime(3 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 3), //3 day
					new AdvancedSyncTime(4 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 4), //4 day
					new AdvancedSyncTime(5 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 5), //5 day
					new AdvancedSyncTime(6 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_day, 6), //6 day
					new AdvancedSyncTime(7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_week, 1), //1 week
					new AdvancedSyncTime(2 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_week, 2), //2 week
					new AdvancedSyncTime(3 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_week, 3), //3 week
					new AdvancedSyncTime(4 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_month, 1), //1 month
					new AdvancedSyncTime(2 * 4 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_month, 2), //2 month
					new AdvancedSyncTime(4 * 4 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_month, 4), //4 month
					new AdvancedSyncTime(8 * 4 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_month, 8), //8 month
					new AdvancedSyncTime(12 * 4 * 7 * 24 * 60 * 60 * 1000L, R.plurals.message_advancedsync_time_year, 1), //1 year
			};
			private final long[] advancedSyncSizes = {
                    1024 * 1024,
					2 * 1024 * 1024,
					4 * 1024 * 1024,
					8 * 1024 * 1024,
					16 * 1024 * 1024,
					32 * 1024 * 1024,
					64 * 1024 * 1024,
					128 * 1024 * 1024,
					256 * 1024 * 1024,
					512 * 1024 * 1024,
					1024 * 1024 * 1024
			};
			private final AdvancedSyncFilter[] advancedSyncFilters = {
					new AdvancedSyncFilter(new String[]{"image/*"}, R.drawable.gallery_outlined, R.string.message_advancedsync_type_image),
					new AdvancedSyncFilter(new String[]{"video/*"}, R.drawable.movie_outlined, R.string.message_advancedsync_type_video),
					new AdvancedSyncFilter(new String[]{"audio/*"}, R.drawable.volume_outlined, R.string.message_advancedsync_type_audio),
					new AdvancedSyncFilter(new String[]{
							"text/plain", "text/richtext", "application/rtf", "application/x-rtf",
							"application/pdf",
							"application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.wordprocessingml.template", "application/vnd.ms-word.document.macroEnabled.12", "application/vnd.ms-word.template.macroEnabled.12",
							"application/vnd.ms-excel", "pplication/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "pplication/vnd.openxmlformats-officedocument.spreadsheetml.template", "application/vnd.ms-excel.sheet.macroEnabled.12", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", "application/vnd.ms-excel.template.macroEnabled.12", "application/vnd.ms-excel.addin.macroEnabled.12",
							"application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.presentationml.template", "application/vnd.openxmlformats-officedocument.presentationml.slideshow", "application/vnd.ms-powerpoint.presentation.macroEnabled.12", "application/vnd.ms-powerpoint.template.macroEnabled.12", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12", "application/vnd.ms-powerpoint.addin.macroEnabled.12"},
							R.drawable.file_document_outlined, R.string.message_advancedsync_type_document),
					new AdvancedSyncFilter(null, R.drawable.file_outlined, R.string.message_advancedsync_type_other)
			};

			AdvancedSyncDialogManager(LayoutInflater inflater) {
				//Inflating the view
				view = inflater.inflate(R.layout.dialog_advancedsync, null);

				//Adding the file filters
				{
					ViewGroup group = viewgroupAttachmentFilters = view.findViewById(R.id.group_filters);
					for(AdvancedSyncFilter filter : advancedSyncFilters) {
						View itemView = filter.createView(inflater, group);
						group.addView(itemView);
					}
				}

				//Configuring the attachment slider
				{
					Slider slider = sliderAttachmentSize = view.findViewById(R.id.slider_attachments_size);
					TextView label = labelAttachmentSize = view.findViewById(R.id.label_attachments_size);
					slider.addOnChangeListener((changedSlider, value, fromUser) -> updateSyncSizeLabel(label, (int) value));

					//Updating the progress immediately
					updateSyncSizeLabel(label, (int) slider.getValue());
				}

				//Configuring the time sliders
				{
					sliderDateMessages = view.findViewById(R.id.slider_messages);
					TextView labelMessages = view.findViewById(R.id.label_messages);
					sliderDateAttachments = view.findViewById(R.id.slider_attachments);
					TextView labelAttachments = view.findViewById(R.id.label_attachments);

					SliderListener sliderListenerMessages = new SliderListener(sliderDateMessages, sliderDateAttachments, true, labelMessages);
					sliderDateMessages.addOnSliderTouchListener(sliderListenerMessages);
					sliderDateMessages.addOnChangeListener(sliderListenerMessages);

					SliderListener sliderListenerAttachments = new SliderListener(sliderDateAttachments, sliderDateMessages, false, labelAttachments);
					sliderDateAttachments.addOnSliderTouchListener(sliderListenerAttachments);
					sliderDateAttachments.addOnChangeListener(sliderListenerAttachments);
				}
			}

			private void updateSyncSizeLabel(TextView label, int progress) {
				if(progress == advancedSyncSizes.length) label.setText(R.string.message_advancedsync_anysize);
				else label.setText(getResources().getString(R.string.message_advancedsync_constraint_size, LanguageHelper.getHumanReadableByteCountInt(advancedSyncSizes[progress], false)));
			}

			private void setAttachmentSpecsEnabled(boolean state, boolean animate) {
				//Setting the input states
				sliderAttachmentSize.setEnabled(state);
				for(AdvancedSyncFilter filter : advancedSyncFilters) filter.setEnabled(state);
				//viewgroupAttachmentFilters.setClickable(state);

				if(animate) {
					float[] animationValues = state ? new float[]{disabledAlpha, 1} : new float[]{1, disabledAlpha};
					ValueAnimator animator = ValueAnimator.ofFloat(animationValues);
					animator.setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime));
					animator.addUpdateListener(animation -> {
						float value = (float) animation.getAnimatedValue();
						sliderAttachmentSize.setAlpha(value);
						labelAttachmentSize.setAlpha(value);
						viewgroupAttachmentFilters.setAlpha(value);
					});
					animator.start();
				} else {
					float value = state ? 1 : disabledAlpha;
					sliderAttachmentSize.setAlpha(value);
					labelAttachmentSize.setAlpha(value);
					viewgroupAttachmentFilters.setAlpha(value);
				}
			}

			void setStateListener(Consumer<Boolean> listener) {
				stateListener = listener;
			}

			View getView() {
				return view;
			}

			void startSync() {
				//Ignoring the request if the service is not set up to receive an advanced mass retrieval request
				//if(!ConnectionService.staticCheckSupportsFeature(ConnectionManager.featureAdvancedSync1)) return;

				//Getting the parameters
				boolean restrictMessages;
				long timeSinceMessages = -1;
				{
					int progress = (int) sliderDateMessages.getValue();
					if(progress == 0) return; //Don't download any messages
					else if(progress - 1 == advancedSyncTimes.length) restrictMessages = false; //Download all messages
					else {
						restrictMessages = true;
						timeSinceMessages = System.currentTimeMillis() - advancedSyncTimes[progress - 1].duration;
					}
				}

				boolean downloadAttachments;
				boolean restrictAttachments = false;
				long timeSinceAttachments = -1;
				{
					int progress = (int) sliderDateAttachments.getValue();
					if(progress == 0) downloadAttachments = false; //Don't download any attachments
					else {
						downloadAttachments = true;
						if(progress - 1 == advancedSyncTimes.length) restrictAttachments = false; //Download all attachments
						else {
							restrictAttachments = true;
							timeSinceAttachments = System.currentTimeMillis() - advancedSyncTimes[progress - 1].duration;
						}
					}
				}

				boolean restrictAttachmentSizes;
				long attachmentSizeLimit = -1;
				{
					int progress = (int) sliderAttachmentSize.getValue();
					if(progress == advancedSyncSizes.length) restrictAttachmentSizes = false; //Download any size
					else {
						restrictAttachmentSizes = true;
						attachmentSizeLimit = advancedSyncSizes[progress];
					}
				}

				List<String> attachmentFilterWhitelist = new ArrayList<>();
				List<String> attachmentFilterBlacklist = new ArrayList<>();
				boolean attachmentFilterDLOther = false;
				for(AdvancedSyncFilter filter : advancedSyncFilters) {
					if(filter.filers == null) attachmentFilterDLOther = filter.isChecked(); //Other files
					else (filter.isChecked() ? attachmentFilterWhitelist : attachmentFilterBlacklist).addAll(Arrays.asList(filter.filers));
				}

				requestSyncMessages(new MassRetrievalParams(restrictMessages, timeSinceMessages, downloadAttachments, restrictAttachments, timeSinceAttachments, restrictAttachmentSizes, attachmentSizeLimit, attachmentFilterWhitelist, attachmentFilterBlacklist, attachmentFilterDLOther));
			}

			private class SliderListener implements Slider.OnChangeListener, Slider.OnSliderTouchListener {
				private final Slider otherBar;
				private final boolean isMessagesSlider;
				private final TextView descriptiveLabel;

				private int otherBarStartValue;
				private boolean isActive;

				private boolean lastSpecState = true;

				@SuppressLint("ClickableViewAccessibility")
				SliderListener(Slider thisBar, Slider otherBar, boolean isMessagesSlider, TextView descriptiveLabel) {
					//Setting the parameters
					this.otherBar = otherBar;
					this.isMessagesSlider = isMessagesSlider;
					this.descriptiveLabel = descriptiveLabel;

					//Setting the touch prevention listener on the other slider
					otherBar.setOnTouchListener((view, event) -> isActive);

					//Updating the label
					updateChanges((int) thisBar.getValue(), false);
				}

				@Override
				public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
					//Updating the label
					updateChanges((int) value, true);

					//Updating the button state
					if(isMessagesSlider) stateListener.accept(value > 0);

					//Returning if this is not the active slider (to prevent both sliders from being activated at once)
					if(currentSliderID != slider.getId()) return;

					//Enforcing the position onto the other slider
					if(isMessagesSlider) {
						if(otherBar.getValue() > slider.getValue()) otherBar.setValue(slider.getValue());
						else if(otherBar.getValue() != otherBarStartValue) otherBar.setValue(Math.min(otherBarStartValue, slider.getValue()));
					} else {
						if(otherBar.getValue() < slider.getValue()) otherBar.setValue(slider.getValue());
						else if(otherBar.getValue() != otherBarStartValue) otherBar.setValue(Math.max(otherBarStartValue, slider.getValue()));
					}
				}

				@Override
				public void onStartTrackingTouch(@NonNull Slider slider) {
					currentSliderID = slider.getId();
					otherBarStartValue = (int) otherBar.getValue();
					isActive = true;
				}

				@Override
				public void onStopTrackingTouch(@NonNull Slider slider) {
					isActive = false;
				}

				private void updateChanges(int progress, boolean animate) {
					//Updating the label
					String text;
					if(progress == 0) text = getResources().getString(isMessagesSlider ? R.string.message_advancedsync_downloadmessages_none : R.string.message_advancedsync_downloadattachments_none);
					else if(progress - 1 == advancedSyncTimes.length) text = getResources().getString(R.string.message_advancedsync_anytime);
					else {
						AdvancedSyncTime data = advancedSyncTimes[progress - 1];
						text = getResources().getString(R.string.message_advancedsync_constraint_time, getResources().getQuantityString(data.pluralRes, data.quantity, data.quantity));
					}
					descriptiveLabel.setText(text);

					//Updating the attachment specs
					if(!isMessagesSlider) {
						boolean state = progress > 0;
						if(lastSpecState != state) {
							lastSpecState = state;
							setAttachmentSpecsEnabled(state, animate);
						}
					}
				}
			}

			private class AdvancedSyncFilter {
				private final String[] filers;
				@DrawableRes
				private final int iconRes;
				@StringRes
				private final int stringRes;

				private View view;
				private CheckBox viewCheckbox;

				private boolean isClickable = true;

				AdvancedSyncFilter(String[] filers, int iconRes, int stringRes) {
					this.filers = filers;
					this.iconRes = iconRes;
					this.stringRes = stringRes;
				}

				View createView(LayoutInflater inflater, ViewGroup parent) {
					view = inflater.inflate(R.layout.layout_advancedsync_filefilter, parent, false);

					((ImageView) view.findViewById(R.id.icon)).setImageResource(iconRes);
					((TextView) view.findViewById(R.id.label)).setText(stringRes);

					viewCheckbox = view.findViewById(R.id.checkbox);
					viewCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> handleUpdateZeroPrevention(isChecked));
					view.setOnClickListener(view -> viewCheckbox.setChecked(!viewCheckbox.isChecked()));

					return view;
				}

				void setEnabled(boolean state) {
					view.setClickable(state && isClickable);
					viewCheckbox.setEnabled(state);
				}

				void setClickable(boolean state) {
					isClickable = state;
					view.setClickable(state);
				}

				boolean isChecked() {
					return viewCheckbox.isChecked();
				}

				//Prevents all checkboxes from being unchecked at once
				private void handleUpdateZeroPrevention(boolean isChecked) {
					if(isChecked) {
						//Counting the amount of checked boxes
						AdvancedSyncFilter lastFilter = null;
						for(AdvancedSyncFilter filter : advancedSyncFilters) {
							if(filter == this) continue;
							if(!filter.isChecked()) continue;
							if(lastFilter != null) return; //If there were already 2 or more checked boxes, then there is no need to change anything
							lastFilter = filter;
						}

						//Enabling the last filter (since it was previously the only checkbox)
						lastFilter.setClickable(true);
					} else {
						//Counting the amount of checked boxes
						AdvancedSyncFilter lastFilter = null;
						for(AdvancedSyncFilter filter : advancedSyncFilters) {
							//if(filter == this) continue;
							if(!filter.isChecked()) continue;
							if(lastFilter != null) return; //If there were already 2 or more checked boxes, then there is no need to change anything
							lastFilter = filter;
						}

						//Disabling the last filter (since it is now the only checkbox)
						lastFilter.setClickable(false);
					}
				}
			}

			private class AdvancedSyncTime {
				private final long duration;
				@PluralsRes
				private final int pluralRes;
				private final int quantity;

				AdvancedSyncTime(long duration, int pluralRes, int quantity) {
					this.duration = duration;
					this.pluralRes = pluralRes;
					this.quantity = quantity;
				}
			}
		}

		@Override
		public Fragment getCallbackFragment() {
			return this;
		}

		private String getRingtoneTitle(Uri ringtoneURI) {
			//Silent ringtone
			if(ringtoneURI == null) return getContext().getResources().getString(R.string.part_none);

			//Getting the ringtone title
			Ringtone ringtone = RingtoneManager.getRingtone(getContext(), ringtoneURI);
			if(ringtone == null) return getContext().getResources().getString(R.string.part_unknown);
			String title = ringtone.getTitle(getContext());
			ringtone.stop();

			//Returning the ringtone title
			return title;
		}

		private void resetConfiguration() {
			//Setting the server as not confirmed
			SharedPreferencesManager.setConnectionConfigured(getContext(), false);

			//Stopping the connection service
			getContext().stopService(new Intent(getContext(), ConnectionService.class));

			//Opening the onboarding activity
			startActivity(new Intent(getActivity(), Onboarding.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
		}

		private void requestSyncMessages(MassRetrievalParams params) {
			//Clearing download tasks
			ConnectionTaskManager.clearDownloads();

			//Deleting the messages
			syncSubscription = MessagesDataHelper.deleteAMBMessages(getContext())
					.toSingle(() -> {
						//Requesting a re-sync
						PluginConnectionService pluginCS = getPluginCS();
						if(pluginCS != null && pluginCS.isServiceBound() && pluginCS.getConnectionManager().isConnected()) {
							pluginCS.getConnectionManager().fetchMassConversationData(params).doOnError(error -> {
								Log.i(TAG, "Failed to sync messages", error);
							}).onErrorComplete().subscribe();
							return true;
						} else {
							return false;
						}
					})
					.subscribe(success -> {
						//Displaying a snackbar
						View view = getView();
						if(view != null) {
							if(success) Snackbar.make(view, R.string.message_confirm_resyncmessages_started, Snackbar.LENGTH_SHORT).show();
							else Snackbar.make(view, R.string.message_serverstatus_noconnection, Snackbar.LENGTH_LONG).show();
						}
			});
		}

		private PluginConnectionService getPluginCS() {
			Activity activity = getActivity();
			if(activity == null) return null;
			else return ((Preferences) activity).getPluginCS();
		}

		/* @Override
		public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat, PreferenceScreen preferenceScreen) {
			FragmentTransaction ft = getActivity().getSupportFragmentManager().beginTransaction();
			MyPreferenceFragment fragment = new MyPreferenceFragment();
			Bundle args = new Bundle();
			args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.getKey());
			fragment.setArguments(args);
			ft.add(R.id.container, fragment, preferenceScreen.getKey());
			ft.addToBackStack(preferenceScreen.getKey());
			ft.commit();
			return true;
		} */
	}

	public static Uri getNotificationSound(Context context) {
		String selectedSound = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_messagenotifications_sound_key), null);
		if(selectedSound == null) return Settings.System.DEFAULT_NOTIFICATION_URI;
		else if(selectedSound.isEmpty()) return null;
		else return Uri.parse(selectedSound);
	}

	public static boolean getPreferenceAMOLED(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_appearance_amoled_key), false);
	}

	public static String getPreferenceDarkMode(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_appearance_theme_key), "follow_system");
	}

	public static boolean getPreferenceReplySuggestions(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_features_replysuggestions_key), true);
	}

	public static boolean getPreferenceAIEnabled(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_ai_enabled_key), false);
	}
	
	public static String getPreferenceAIProvider(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_features_aiprovider_key), "gemini");
	}
	
	public static String getPreferenceGeminiApiKey(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_features_gemini_apikey_key), "");
	}
	
	public static String getPreferenceOllamaTurboApiKey(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_ollama_turbo_apikey_key), "");
	}

	public static String getPreferenceOllamaHostname(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_ollama_hostname_key), "");
	}

	public static int getPreferenceOllamaPort(Context context) {
		return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_ollama_port_key), "11434"));
	}

	public static String getPreferenceOllamaModel(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.preference_ollama_model_key), "");
	}

	public static boolean getPreferenceOllamaKeepAlive(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_ollama_keepalive_key), true);
	}

	public static String getOllamaBaseUrl(Context context) {
		String hostname = getPreferenceOllamaHostname(context);
		if (hostname.isEmpty()) {
			return "";
		}
		int port = getPreferenceOllamaPort(context);
		return "http://" + hostname + ":" + port;
	}

	// Save scanned Ollama models to SharedPreferences
	public static void saveOllamaScannedModels(Context context, String[] modelNames, String[] modelDisplayNames) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor editor = prefs.edit();

		// Convert arrays to comma-separated strings for storage
		String namesStr = String.join(",", modelNames);
		String displayNamesStr = String.join(",", modelDisplayNames);

		editor.putString("ollama_scanned_model_names", namesStr);
		editor.putString("ollama_scanned_model_display_names", displayNamesStr);
		editor.putBoolean("ollama_models_scanned", true);
		editor.apply();
	}

	// Load previously scanned Ollama models from SharedPreferences
	public static boolean hasScannedModels(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getBoolean("ollama_models_scanned", false);
	}

	public static String[] getScannedModelNames(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String namesStr = prefs.getString("ollama_scanned_model_names", "");
		return namesStr.isEmpty() ? new String[0] : namesStr.split(",");
	}

	public static String[] getScannedModelDisplayNames(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String displayNamesStr = prefs.getString("ollama_scanned_model_display_names", "");
		return displayNamesStr.isEmpty() ? new String[0] : displayNamesStr.split(",");
	}

	public static boolean getPreferenceAdvancedColor(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_appearance_advancedcolor_key), false);
	}

	public static boolean getPreferenceMessagePreviews(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_features_messagepreviews_key), true);
	}

	public static boolean getPreferenceMessageSounds(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_features_messagesounds_key), true);
	}

	public static boolean getPreferenceAutoDownloadAttachments(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_storage_autodownload_key), true);
	}

	public static boolean getPreferenceSMSDeliveryReports(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_textmessage_deliveryreport_key), false);
	}

	public static boolean getPreferenceTextMessageIntegration(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_textmessage_enable_key), false);
	}

	public static void setPreferenceTextMessageIntegration(Context context, boolean value) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putBoolean(context.getResources().getString(R.string.preference_textmessage_enable_key), value)
				.apply();
	}

	public static boolean isTextMessageIntegrationActive(Context context) {
		return MMSSMSHelper.isDefaultMessagingApp(context) &&
			   context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
			   context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
			   context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
			   context.checkSelfPermission(Manifest.permission.RECEIVE_MMS) == PackageManager.PERMISSION_GRANTED &&
			   context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
			   getPreferenceTextMessageIntegration(context);
	}

	public static boolean getPreferenceStartOnBoot(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_server_connectionboot_key), false);
	}

	public static void updateConnectionServiceBootEnabled(Context context) {
		int accountType = SharedPreferencesManager.getProxyType(context);
		updateConnectionServiceBootEnabled(context, getPreferenceStartOnBoot(context) && accountType == ProxyType.direct); //Don't start on boot if we're using Connect
	}

	public static void updateConnectionServiceBootEnabled(Context context, boolean enable) {
		context.getPackageManager().setComponentEnabledSetting(new ComponentName(context, StartBootReceiver.class),
				enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP);
	}
}