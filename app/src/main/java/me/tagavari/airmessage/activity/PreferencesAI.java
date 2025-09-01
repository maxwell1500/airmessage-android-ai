package me.tagavari.airmessage.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

import me.tagavari.airmessage.R;
import me.tagavari.airmessage.helper.ConversationMemoryManager;

/**
 * AI Settings activity for configuring AI providers and features
 */
public class PreferencesAI extends AppCompatActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
	private static final String TAG = PreferencesAI.class.getSimpleName();

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_preferences);
		
		// Set up the toolbar
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle("AI Settings");
		}

		// Load the AI preferences fragment
		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.container, new AISettingsFragment())
					.commit();
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		onBackPressed();
		return true;
	}

	@Override
	public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat, androidx.preference.PreferenceScreen preferenceScreen) {
		// Handle sub-screens if needed (similar to main preferences)
		return false;
	}

	/**
	 * Fragment for AI settings
	 */
	public static class AISettingsFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			// Load the AI preferences
			addPreferencesFromResource(R.xml.preferences_ai);
			
			// Set up preference listeners
			setupPreferences();
		}

		private void setupPreferences() {
			// AI Provider selection listener
			ListPreference aiProviderPref = findPreference(getString(R.string.preference_features_aiprovider_key));
			if (aiProviderPref != null) {
				aiProviderPref.setOnPreferenceChangeListener((preference, newValue) -> {
					String provider = (String) newValue;
					Log.d(TAG, "AI provider changed to: " + provider);
					
					// Show appropriate configuration sections based on provider
					updateProviderVisibility(provider);
					return true;
				});
				
				// Set initial visibility
				String currentProvider = Preferences.getPreferenceAIProvider(getContext());
				updateProviderVisibility(currentProvider);
			}

			// Memory limit slider with dynamic warnings
			SeekBarPreference seekBarPreference = findPreference("conversation_memory_message_limit");
			if (seekBarPreference != null) {
				seekBarPreference.setOnPreferenceChangeListener((preference, newValue) -> {
					int value = (Integer) newValue;
					int roundedValue = Math.round(value / 10.0f) * 10;
					String summary = "Store contextual information from the last " + roundedValue + " messages";
					if (roundedValue > 500) {
						summary += " ⚠️ Large values may take longer to process";
					}
					seekBarPreference.setSummary(summary);
					return true;
				});

				// Set initial summary
				int currentValue = seekBarPreference.getValue();
				int roundedValue = Math.round(currentValue / 10.0f) * 10;
				String summary = "Store contextual information from the last " + roundedValue + " messages";
				if (roundedValue > 500) {
					summary += " ⚠️ Large values may take longer to process";
				}
				seekBarPreference.setSummary(summary);
			}

			// Memory management button
			Preference memoryManagePreference = findPreference(getString(R.string.preference_ai_memory_manage_key));
			if (memoryManagePreference != null) {
				memoryManagePreference.setOnPreferenceClickListener(preference -> {
					showMemoryManagementDialog();
					return true;
				});
			}

			// Ollama scan models button
			Preference scanModelsPreference = findPreference(getString(R.string.preference_ollama_scan_models_key));
			if (scanModelsPreference != null) {
				scanModelsPreference.setOnPreferenceClickListener(preference -> {
					scanOllamaModels();
					return true;
				});
			}
			
			// Load previously scanned models if available
			loadPreviouslyScannedModels();

			// API auth button
			Preference authPreference = findPreference(getString(R.string.preference_ai_auth_key));
			if (authPreference != null) {
				authPreference.setOnPreferenceClickListener(preference -> {
					// TODO: Implement API key validation
					Toast.makeText(getContext(), "API key validation not yet implemented", Toast.LENGTH_SHORT).show();
					return true;
				});
			}
		}

		private void updateProviderVisibility(String provider) {
			// Show/hide provider-specific categories based on selection
			androidx.preference.PreferenceCategory geminiCategory = findPreference(getString(R.string.preferencegroup_gemini_key));
			androidx.preference.PreferenceCategory ollamaCategory = findPreference(getString(R.string.preferencegroup_ollama_key));
			androidx.preference.PreferenceCategory ollamaTurboCategory = findPreference(getString(R.string.preferencegroup_ollama_turbo_key));

			if (geminiCategory != null && ollamaCategory != null && ollamaTurboCategory != null) {
				switch (provider) {
					case "gemini":
						geminiCategory.setVisible(true);
						ollamaCategory.setVisible(false);
						ollamaTurboCategory.setVisible(false);
						break;
					case "ollama":
						geminiCategory.setVisible(false);
						ollamaCategory.setVisible(true);
						ollamaTurboCategory.setVisible(false);
						break;
					case "ollama_turbo":
						geminiCategory.setVisible(false);
						ollamaCategory.setVisible(false);
						ollamaTurboCategory.setVisible(true);
						break;
					default:
						// Show all for flexibility
						geminiCategory.setVisible(true);
						ollamaCategory.setVisible(true);
						ollamaTurboCategory.setVisible(true);
						break;
				}
			}
		}

		/**
		 * Shows the memory management dialog with options to view details, process existing messages, or clear memory
		 */
		private void showMemoryManagementDialog() {
			// Check if fragment is still attached
			if (getActivity() == null || !isAdded()) {
				Log.d(TAG, "Fragment not available for memory management dialog");
				return;
			}

			String[] options = {"View Memory Details", "Process Existing Messages", "Clear All Memories"};
			
			new MaterialAlertDialogBuilder(getActivity())
					.setTitle("Conversation Memory")
					.setItems(options, (dialog, which) -> {
						switch (which) {
							case 0: // View Details
								showMemoryDetailsDialog();
								break;
							case 1: // Process Messages
								processExistingMessages();
								break;
							case 2: // Clear All
								showClearMemoryConfirmation();
								break;
						}
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		}

		/**
		 * Shows a dialog with detailed memory information
		 */
		private void showMemoryDetailsDialog() {
			// Check if fragment is still attached
			if (getActivity() == null || !isAdded()) {
				Log.d(TAG, "Fragment not available for memory details dialog");
				return;
			}

			ConversationMemoryManager.getAllMemories(getActivity())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(
					memories -> {
						// Check if fragment is still attached when callback executes
						if (getActivity() == null || !isAdded()) {
							Log.d(TAG, "Fragment detached during memory loading");
							return;
						}

						StringBuilder content = new StringBuilder();
						if (memories.isEmpty()) {
							content.append("No conversation memories stored.\n\nUse 'Process Messages' to extract contextual information from your message history.");
						} else {
							content.append("Stored Memories: ").append(memories.size()).append(" items\n\n");
							
							int displayCount = Math.min(memories.size(), 10); // Show first 10
							for (int i = 0; i < displayCount; i++) {
								ConversationMemoryManager.MemoryItem memory = memories.get(i);
								content.append("📱 ").append(memory.getConversationTitle()).append("\n");
								content.append("💭 ").append(memory.getExtractedInfo()).append("\n");
								content.append("📅 ").append(new java.util.Date(memory.getTimestamp()).toString()).append("\n\n");
							}
							
							if (memories.size() > 10) {
								content.append("... and ").append(memories.size() - 10).append(" more memories");
							}
						}

						// Create scrollable text view
						TextView textView = new TextView(getActivity());
						textView.setText(content.toString());
						textView.setPadding(50, 30, 50, 30);
						textView.setTextIsSelectable(true);
						
						ScrollView scrollView = new ScrollView(getActivity());
						scrollView.addView(textView);

						new MaterialAlertDialogBuilder(getActivity())
								.setTitle("Memory Details")
								.setView(scrollView)
								.setPositiveButton(android.R.string.ok, null)
								.setNegativeButton("Clear All", (d, w) -> {
									showClearMemoryConfirmation();
								})
								.show();
					},
					error -> {
						Log.e(TAG, "Failed to load memories", error);
						if (getActivity() != null && isAdded()) {
							Toast.makeText(getActivity(), "Failed to load memory details", Toast.LENGTH_SHORT).show();
						}
					}
				);
		}

		/**
		 * Process existing messages to extract memories
		 */
		private void processExistingMessages() {
			Context context = getContext();
			if (context != null) {
				// Show progress toast
				Toast.makeText(context, "Processing existing messages...", Toast.LENGTH_SHORT).show();
				
				// Process messages in background
				ConversationMemoryManager.processExistingMessages(context)
						.observeOn(AndroidSchedulers.mainThread())
						.subscribe(
								result -> {
									if (getActivity() != null) {
										String message = String.format("Processed %d messages, extracted %d memories from %d conversations",
												result.getMessagesProcessed(),
												result.getMemoriesExtracted(),
												result.getConversationsProcessed());
										Toast.makeText(context, message, Toast.LENGTH_LONG).show();
									}
								},
								error -> {
									Log.e(TAG, "Failed to process existing messages", error);
									if (getActivity() != null) {
										Toast.makeText(context, "Failed to process messages: " + error.getMessage(), Toast.LENGTH_LONG).show();
									}
								}
						);
			}
		}

		/**
		 * Shows confirmation dialog for clearing all memories
		 */
		private void showClearMemoryConfirmation() {
			if (getActivity() == null || !isAdded()) {
				return;
			}

			new MaterialAlertDialogBuilder(getActivity())
					.setTitle("Clear Memory")
					.setMessage("Are you sure you want to clear all stored conversation memory? This cannot be undone.")
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton("Clear", (d, w) -> {
						// Clear memory
						ConversationMemoryManager.clearAllMemories(getActivity())
								.observeOn(AndroidSchedulers.mainThread())
								.subscribe(
										() -> Toast.makeText(getActivity(), "Conversation memory cleared", Toast.LENGTH_SHORT).show(),
										error -> Toast.makeText(getActivity(), "Failed to clear memory", Toast.LENGTH_SHORT).show()
								);
					})
					.show();
		}

		/**
		 * Scan for available Ollama models on the configured server
		 */
		private void scanOllamaModels() {
			String hostname = Preferences.getPreferenceOllamaHostname(getContext());
			if (hostname.isEmpty()) {
				Toast.makeText(getContext(), "Please enter Ollama server hostname first", Toast.LENGTH_LONG).show();
				return;
			}

			int port = Preferences.getPreferenceOllamaPort(getContext());
			String baseUrl = "http://" + hostname + ":" + port;

			Toast.makeText(getContext(), "Scanning for models...", Toast.LENGTH_SHORT).show();

			// Create HTTP client and request
			OkHttpClient client = new OkHttpClient();
			Request request = new Request.Builder()
					.url(baseUrl + "/api/tags")
					.build();

			// Execute request in background thread
			new Thread(() -> {
				try {
					Response response = client.newCall(request).execute();
					if (response.isSuccessful() && response.body() != null) {
						String responseBody = response.body().string();
						parseOllamaModelsResponse(responseBody);
					} else {
						if (getActivity() != null) {
							getActivity().runOnUiThread(() -> {
								Toast.makeText(getContext(), "Failed to connect to Ollama server", Toast.LENGTH_LONG).show();
							});
						}
					}
				} catch (IOException e) {
					if (getActivity() != null) {
						getActivity().runOnUiThread(() -> {
							Toast.makeText(getContext(), "Network error: " + e.getMessage(), Toast.LENGTH_LONG).show();
						});
					}
				}
			}).start();
		}

		/**
		 * Parse the response from Ollama server and update the model list
		 */
		private void parseOllamaModelsResponse(String responseBody) {
			try {
				JSONObject jsonResponse = new JSONObject(responseBody);
				JSONArray modelsArray = jsonResponse.getJSONArray("models");

				java.util.List<String> modelNames = new java.util.ArrayList<>();
				java.util.List<String> modelDisplayNames = new java.util.ArrayList<>();

				for (int i = 0; i < modelsArray.length(); i++) {
					JSONObject model = modelsArray.getJSONObject(i);
					String name = model.getString("name");
					String size = model.optString("size", "");
					
					modelNames.add(name);
					
					// Create display name with size info if available
					String displayName = name;
					if (!size.isEmpty()) {
						displayName += " (" + formatSize(size) + ")";
					}
					modelDisplayNames.add(displayName);
				}

				if (getActivity() != null) {
					getActivity().runOnUiThread(() -> {
						// Save the scanned models to SharedPreferences
						String[] modelNamesArray = modelNames.toArray(new String[0]);
						String[] modelDisplayNamesArray = modelDisplayNames.toArray(new String[0]);
						Preferences.saveOllamaScannedModels(getContext(), modelNamesArray, modelDisplayNamesArray);

						// Update the ListPreference with found models
						updateModelListPreference(modelNamesArray, modelDisplayNamesArray);

						Toast.makeText(getContext(), "Found " + modelNames.size() + " models", Toast.LENGTH_SHORT).show();
					});
				}
			} catch (JSONException e) {
				if (getActivity() != null) {
					getActivity().runOnUiThread(() -> {
						Toast.makeText(getContext(), "Error parsing server response", Toast.LENGTH_LONG).show();
					});
				}
			}
		}

		/**
		 * Format size string for display
		 */
		private String formatSize(String sizeStr) {
			try {
				long sizeBytes = Long.parseLong(sizeStr);
				if (sizeBytes < 1024) return sizeBytes + " B";
				if (sizeBytes < 1024 * 1024) return (sizeBytes / 1024) + " KB";
				if (sizeBytes < 1024 * 1024 * 1024) return (sizeBytes / (1024 * 1024)) + " MB";
				return (sizeBytes / (1024 * 1024 * 1024)) + " GB";
			} catch (NumberFormatException e) {
				return sizeStr;
			}
		}

		/**
		 * Update the model ListPreference with scanned models
		 */
		private void updateModelListPreference(String[] modelNames, String[] modelDisplayNames) {
			androidx.preference.ListPreference modelPreference = findPreference(getString(R.string.preference_ollama_model_key));
			if (modelPreference != null) {
				modelPreference.setEntries(modelDisplayNames);
				modelPreference.setEntryValues(modelNames);
				
				// If no model is currently selected, select the first one
				if (modelPreference.getValue() == null && modelNames.length > 0) {
					modelPreference.setValue(modelNames[0]);
				}
			}
		}

		/**
		 * Load previously scanned models if available
		 */
		private void loadPreviouslyScannedModels() {
			if (Preferences.hasScannedModels(getContext())) {
				String[] modelNames = Preferences.getScannedModelNames(getContext());
				String[] modelDisplayNames = Preferences.getScannedModelDisplayNames(getContext());
				
				if (modelNames.length > 0 && modelDisplayNames.length > 0) {
					updateModelListPreference(modelNames, modelDisplayNames);
				}
			}
		}
	}
}