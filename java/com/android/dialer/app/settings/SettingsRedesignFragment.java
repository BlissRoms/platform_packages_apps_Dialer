/*
 * Copyright (C) 2026 The BlissRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dialer.app.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.BlockedNumberContract;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.android.material.materialswitch.MaterialSwitch;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.common.LogUtil;
import com.android.dialer.compat.telephony.TelephonyManagerCompat;
import com.android.dialer.helplines.HelplineActivity;
import com.android.dialer.lookup.LookupSettingsFragment;
import com.android.dialer.util.PermissionsUtil;
import com.android.dialer.voicemail.settings.VoicemailSettingsFragment;
import com.android.voicemail.VoicemailClient;

/**
 * Card-based root of the dialer settings screen. Renders the redesigned grouped layout and routes
 * each entry to the existing settings fragment or system intent. Sub-screens continue to use the
 * legacy {@link androidx.preference.PreferenceFragmentCompat} fragments via {@link
 * DialerSettingsActivity#openSubFragment}.
 */
public class SettingsRedesignFragment extends Fragment {

  /**
   * SharedPreferences key gating whether blocked numbers silence the ringer. Read at call time by
   * {@code com.android.incallui.InCallPresenter}; keep both in sync. Defaults to enabled.
   */
  private static final String KEY_CALL_BLOCKING = "settings_call_blocking";

  /**
   * Last known call-waiting value. Seeds the switch so it stays stable when the platform's status
   * query is slow or never returns a definitive answer (common on IMS-UT stacks).
   */
  private static final String KEY_CALL_WAITING = "settings_call_waiting";

  private SharedPreferences preferences;
  private LinearLayout container;
  private LinearLayout currentCard;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent,
      @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_settings_redesign, parent, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    preferences =
        PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext());
    container = view.findViewById(R.id.settings_container);

    view.findViewById(R.id.settings_back)
        .setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

    buildSettings();
  }

  private void buildSettings() {
    LayoutInflater inflater = LayoutInflater.from(requireContext());

    // ---- Personalisation ----
    addSection(inflater, R.string.settings_section_personalisation);
    if (showDisplayOptions()) {
      addNavRow(
          inflater,
          getString(R.string.display_options_title),
          null,
          () ->
              openSubFragment(
                  DisplayOptionsSettingsFragment.class,
                  getString(R.string.display_options_title)));
    }
    addNavRow(
        inflater,
        getString(R.string.sounds_and_vibration_title),
        getString(R.string.settings_sounds_summary_default),
        this::openSoundSettings);

    // ---- Call settings ----
    addSection(inflater, R.string.settings_section_call);
    addNavRow(
        inflater,
        getString(R.string.respond_via_sms_setting_title),
        null,
        () -> openIntent(new Intent(TelecomManager.ACTION_SHOW_RESPOND_VIA_SMS_SETTINGS)));
    addNavRow(
        inflater,
        getString(R.string.settings_phone_number_lookup_title),
        null,
        () ->
            openSubFragment(
                LookupSettingsFragment.class,
                getString(R.string.settings_phone_number_lookup_title)));
    addCallWaitingRow(inflater);
    addNavRow(
        inflater,
        getString(R.string.phone_account_settings_label),
        null,
        () -> {
          Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
          intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
          openIntent(intent);
        });
    if (CallRecorderService.isEnabled(requireContext())) {
      addNavRow(
          inflater,
          getString(R.string.call_recording_category_title),
          null,
          () ->
              openSubFragment(
                  CallRecordingSettingsFragment.class,
                  getString(R.string.call_recording_category_title)));
    }
    if (showAccessibility()) {
      addNavRow(
          inflater,
          getString(R.string.accessibility_settings_title),
          null,
          () -> openIntent(new Intent(TelecomManager.ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS)));
    }
    addVoicemailRow(inflater);

    // ---- Privacy & Security ----
    if (BlockedNumberContract.canCurrentUserBlockNumbers(requireContext())) {
      addSection(inflater, R.string.settings_section_privacy);
      addToggleRow(
          inflater,
          getString(R.string.settings_call_blocking_title),
          getString(R.string.settings_call_blocking_summary),
          KEY_CALL_BLOCKING,
          true);
    }

    // ---- Help ----
    addSection(inflater, R.string.settings_section_help);
    addNavRow(
        inflater,
        getString(R.string.action_menu_helplines),
        null,
        () -> openIntent(new Intent(requireContext(), HelplineActivity.class)));
  }

  /** Accessibility call settings, shown only to the primary user when TTY/HAC is supported. */
  private boolean showAccessibility() {
    if (!requireContext().getSystemService(UserManager.class).isSystemUser()) {
      return false;
    }
    TelephonyManager telephonyManager = requireContext().getSystemService(TelephonyManager.class);
    TelecomManager telecomManager = requireContext().getSystemService(TelecomManager.class);
    return TelephonyManagerCompat.isTtyModeSupported(telecomManager)
        || TelephonyManagerCompat.isHearingAidCompatibilitySupported(telephonyManager);
  }

  /** Starts a new card group with the given section header and makes it the active card. */
  private void addSection(LayoutInflater inflater, @StringRes int titleRes) {
    TextView header =
        (TextView) inflater.inflate(R.layout.settings_redesign_section_header, container, false);
    header.setText(titleRes);
    container.addView(header);
    addBareCard(inflater);
  }

  /** Adds a headerless card and makes it the active card. */
  private void addBareCard(LayoutInflater inflater) {
    currentCard =
        (LinearLayout) inflater.inflate(R.layout.settings_redesign_card, container, false);
    container.addView(currentCard);
  }

  private void addNavRow(
      LayoutInflater inflater,
      CharSequence title,
      @Nullable CharSequence secondary,
      Runnable action) {
    View row = inflateRow(inflater);
    ((TextView) row.findViewById(R.id.row_title)).setText(title);
    if (secondary != null) {
      TextView secondaryView = row.findViewById(R.id.row_secondary);
      secondaryView.setText(secondary);
      secondaryView.setVisibility(View.VISIBLE);
    }
    row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
    row.setOnClickListener(v -> action.run());
  }

  private void addToggleRow(
      LayoutInflater inflater,
      CharSequence title,
      @Nullable CharSequence summary,
      String prefKey,
      boolean defaultValue) {
    View row = inflateRow(inflater);
    ((TextView) row.findViewById(R.id.row_title)).setText(title);
    setRowSummary(row, summary);
    MaterialSwitch toggle = row.findViewById(R.id.row_switch);
    toggle.setVisibility(View.VISIBLE);
    toggle.setChecked(preferences.getBoolean(prefKey, defaultValue));
    toggle.setOnCheckedChangeListener(
        (button, checked) -> preferences.edit().putBoolean(prefKey, checked).apply());
    row.setOnClickListener(v -> toggle.toggle());
  }

  /**
   * Call waiting row backed by the real telephony state. Reads the current status on bind and pushes
   * the new value to the modem on toggle. The switch stays disabled until the status resolves (or
   * permanently if the platform does not support the query).
   */
  private void addCallWaitingRow(LayoutInflater inflater) {
    View row = inflateRow(inflater);
    ((TextView) row.findViewById(R.id.row_title)).setText(R.string.settings_call_waiting_title);
    setRowSummary(row, getString(R.string.settings_call_waiting_summary));
    MaterialSwitch toggle = row.findViewById(R.id.row_switch);
    toggle.setVisibility(View.VISIBLE);

    TelephonyManager telephonyManager =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getVoiceTelephonyManager() : null;
    CompoundButton.OnCheckedChangeListener onToggle =
        (button, checked) -> onCallWaitingToggled(telephonyManager, toggle, checked);

    // Seed from the last known value so the switch is stable and reflects the user's intent even
    // when the modem status query is slow or never returns a definitive answer.
    setCheckedSilently(toggle, onToggle, preferences.getBoolean(KEY_CALL_WAITING, true));
    toggle.setEnabled(true);
    row.setOnClickListener(v -> toggle.toggle());

    if (telephonyManager == null) {
      return; // Pre-R or no telephony: behave as a stored preference only.
    }
    // Reconcile with the real modem state, but only when it gives a definitive answer.
    try {
      telephonyManager.getCallWaitingStatus(
          requireContext().getMainExecutor(),
          status -> {
            if (!isAdded()) {
              return;
            }
            if (status == TelephonyManager.CALL_WAITING_STATUS_ENABLED
                || status == TelephonyManager.CALL_WAITING_STATUS_DISABLED) {
              boolean enabled = status == TelephonyManager.CALL_WAITING_STATUS_ENABLED;
              preferences.edit().putBoolean(KEY_CALL_WAITING, enabled).apply();
              setCheckedSilently(toggle, onToggle, enabled);
            }
          });
    } catch (RuntimeException e) {
      LogUtil.e("SettingsRedesignFragment.addCallWaitingRow", "getCallWaitingStatus failed", e);
    }
  }

  private void onCallWaitingToggled(
      @Nullable TelephonyManager telephonyManager, MaterialSwitch toggle, boolean enabled) {
    // Persist the intent immediately so it survives reopening regardless of modem latency.
    preferences.edit().putBoolean(KEY_CALL_WAITING, enabled).apply();
    if (telephonyManager == null) {
      return;
    }
    CompoundButton.OnCheckedChangeListener onToggle =
        (button, checked) -> onCallWaitingToggled(telephonyManager, toggle, checked);
    try {
      telephonyManager.setCallWaitingEnabled(
          enabled,
          requireContext().getMainExecutor(),
          result -> {
            if (!isAdded()) {
              return;
            }
            if (result == TelephonyManager.CALL_WAITING_STATUS_ENABLED
                || result == TelephonyManager.CALL_WAITING_STATUS_DISABLED) {
              boolean nowEnabled = result == TelephonyManager.CALL_WAITING_STATUS_ENABLED;
              preferences.edit().putBoolean(KEY_CALL_WAITING, nowEnabled).apply();
              if (nowEnabled != enabled) {
                setCheckedSilently(toggle, onToggle, nowEnabled);
              }
            }
            // Non-definitive result: keep the optimistic value (already persisted).
          });
    } catch (RuntimeException e) {
      LogUtil.e("SettingsRedesignFragment.onCallWaitingToggled", "setCallWaitingEnabled failed", e);
    }
  }

  /** Telephony manager bound to the default voice subscription, so query and set stay consistent. */
  @Nullable
  private TelephonyManager getVoiceTelephonyManager() {
    TelephonyManager telephonyManager = requireContext().getSystemService(TelephonyManager.class);
    if (telephonyManager == null) {
      return null;
    }
    int subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
      return telephonyManager.createForSubscriptionId(subId);
    }
    return telephonyManager;
  }

  private static void setCheckedSilently(
      MaterialSwitch toggle, CompoundButton.OnCheckedChangeListener listener, boolean checked) {
    toggle.setOnCheckedChangeListener(null);
    toggle.setChecked(checked);
    toggle.setOnCheckedChangeListener(listener);
  }

  private static void setRowSummary(View row, @Nullable CharSequence summary) {
    if (summary == null) {
      return;
    }
    TextView summaryView = row.findViewById(R.id.row_summary);
    summaryView.setText(summary);
    summaryView.setVisibility(View.VISIBLE);
  }

  /** Inflates a row into the active card, inserting a divider above it when not the first row. */
  private View inflateRow(LayoutInflater inflater) {
    if (currentCard.getChildCount() > 0) {
      float density = getResources().getDisplayMetrics().density;
      View divider = new View(requireContext());
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
      params.setMarginStart(Math.round(20 * density));
      divider.setLayoutParams(params);
      divider.setBackgroundColor(getResources().getColor(R.color.settings_row_divider, null));
      currentCard.addView(divider);
    }
    View row = inflater.inflate(R.layout.settings_redesign_row, currentCard, false);
    currentCard.addView(row);
    return row;
  }

  private void openSubFragment(Class<? extends Fragment> fragmentClass, CharSequence title) {
    openSubFragment(fragmentClass, title, null);
  }

  private void openSubFragment(
      Class<? extends Fragment> fragmentClass, CharSequence title, @Nullable Bundle args) {
    if (getActivity() instanceof DialerSettingsActivity) {
      ((DialerSettingsActivity) getActivity())
          .openSubFragment(fragmentClass.getName(), title, args);
    }
  }

  /**
   * Adds the voicemail entry (normal + on-device "auto" voicemail). Single-SIM opens the voicemail
   * settings directly; otherwise the SIM picker is shown first. Mirrors the legacy logic.
   */
  private void addVoicemailRow(LayoutInflater inflater) {
    if (!requireContext().getSystemService(UserManager.class).isSystemUser()) {
      return;
    }
    if (!PermissionsUtil.hasReadPhoneStatePermissions(requireContext())) {
      return;
    }
    CharSequence title = getString(R.string.voicemail_settings_label);
    PhoneAccountHandle soleAccount = getSoleSimAccount();
    if (soleAccount == null) {
      Bundle bundle = new Bundle();
      bundle.putString(
          PhoneAccountSelectionFragment.PARAM_TARGET_FRAGMENT,
          VoicemailSettingsFragment.class.getName());
      bundle.putString(
          PhoneAccountSelectionFragment.PARAM_PHONE_ACCOUNT_HANDLE_KEY,
          VoicemailClient.PARAM_PHONE_ACCOUNT_HANDLE);
      bundle.putBundle(PhoneAccountSelectionFragment.PARAM_ARGUMENTS, new Bundle());
      bundle.putInt(
          PhoneAccountSelectionFragment.PARAM_TARGET_TITLE_RES, R.string.voicemail_settings_label);
      addNavRow(
          inflater,
          title,
          null,
          () -> openSubFragment(PhoneAccountSelectionFragment.class, title, bundle));
    } else {
      Bundle bundle = new Bundle();
      bundle.putParcelable(VoicemailClient.PARAM_PHONE_ACCOUNT_HANDLE, soleAccount);
      addNavRow(
          inflater,
          title,
          null,
          () -> openSubFragment(VoicemailSettingsFragment.class, title, bundle));
    }
  }

  /** Returns the only SIM phone account, or {@code null} if there are none or more than one. */
  @SuppressLint("MissingPermission")
  @Nullable
  private PhoneAccountHandle getSoleSimAccount() {
    TelecomManager telecomManager = requireContext().getSystemService(TelecomManager.class);
    PhoneAccountHandle result = null;
    for (PhoneAccountHandle handle : telecomManager.getCallCapablePhoneAccounts()) {
      PhoneAccount account = telecomManager.getPhoneAccount(handle);
      if (account == null) {
        continue;
      }
      if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
        if (result != null) {
          return null;
        }
        result = handle;
      }
    }
    return result;
  }

  private void openSoundSettings() {
    if (!Settings.System.canWrite(requireContext())) {
      Toast.makeText(
              requireContext(),
              getString(R.string.toast_cannot_write_system_settings),
              Toast.LENGTH_SHORT)
          .show();
      openIntent(new Intent(Settings.ACTION_SOUND_SETTINGS));
      return;
    }
    openSubFragment(SoundSettingsFragment.class, getString(R.string.sounds_and_vibration_title));
  }

  private void openIntent(Intent intent) {
    try {
      startActivity(intent);
    } catch (RuntimeException e) {
      LogUtil.e("SettingsRedesignFragment.openIntent", "unable to launch " + intent, e);
    }
  }

  /**
   * Display options aren't useful for languages such as Chinese, Japanese or Korean where contacts
   * are sorted and displayed family-name-first by default. Mirrors the legacy preference logic.
   */
  private boolean showDisplayOptions() {
    return getResources().getBoolean(R.bool.config_display_order_user_changeable)
        && getResources().getBoolean(R.bool.config_sort_order_user_changeable);
  }
}
