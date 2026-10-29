/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.contactprofile;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;
import com.android.dialer.blocking.Blocking;
import com.android.dialer.callintent.CallInitiationType;
import com.android.dialer.callintent.CallIntentBuilder;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.android.dialer.common.concurrent.SupportUiListener;
import com.android.dialer.location.GeoUtil;
import com.android.dialer.precall.PreCall;
import com.android.dialer.speeddial.ContactCalls;
import com.android.dialer.speeddial.database.SpeedDialEntry.Channel;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.android.dialer.util.DialerUtils;
import com.android.dialer.util.IntentUtil;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Optional;

final class ContactProfileActions {

  private static final CallInitiationType.Type INITIATION_TYPE =
      CallInitiationType.Type.QUICK_CONTACTS;

  private final Fragment fragment;
  private final Runnable onChanged;
  private final Runnable onDeleted;
  private final SupportUiListener<Void> mutationListener;

  ContactProfileActions(Fragment fragment, Runnable onChanged, Runnable onDeleted) {
    this.fragment = fragment;
    this.onChanged = onChanged;
    this.onDeleted = onDeleted;
    mutationListener =
        DialerExecutorComponent.get(fragment.requireContext())
            .createUiListener(fragment.getChildFragmentManager(), "contact_profile_mutation");
  }

  void toggleStar(ContactProfile profile) {
    Context appContext = context().getApplicationContext();
    runMutation(
        DialerExecutorComponent.get(appContext)
            .backgroundExecutor()
            .submit(
                () -> {
                  ContentValues values = new ContentValues();
                  values.put(Contacts.STARRED, profile.starred ? 0 : 1);
                  appContext
                      .getContentResolver()
                      .update(
                          Contacts.CONTENT_URI,
                          values,
                          Contacts._ID + " = ?",
                          new String[] {Long.toString(profile.contactId)});
                  return null;
                }),
        onChanged);
  }

  void edit(ContactProfile profile) {
    DialerUtils.startActivityWithErrorToast(
        context(), new Intent(Intent.ACTION_EDIT, profile.lookupUri));
  }

  void openInContacts(ContactProfile profile) {
    DialerUtils.startActivityWithErrorToast(
        context(), new Intent(Intent.ACTION_VIEW, profile.lookupUri));
  }

  void share(ContactProfile profile) {
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType(Contacts.CONTENT_VCARD_TYPE)
            .putExtra(
                Intent.EXTRA_STREAM,
                Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, profile.lookupKey))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    DialerUtils.startActivityWithErrorToast(
        context(), Intent.createChooser(send, context().getString(R.string.contact_profile_share)));
  }

  void call(ContactProfile profile) {
    profile.phoneItem.ifPresent(
        item ->
            ContactCalls.call(context(), item, fragment.getChildFragmentManager(), INITIATION_TYPE));
  }

  void callNumber(String number) {
    PreCall.start(context(), new CallIntentBuilder(number, INITIATION_TYPE));
  }

  void message(ContactProfile profile) {
    if (!profile.hasNumbers()) {
      return;
    }
    DialerUtils.startActivityWithErrorToast(
        context(), IntentUtil.getSendSmsIntent(preferredNumber(profile)));
  }

  void videoCall(ContactProfile profile) {
    Optional<Channel> videoChannel =
        profile.phoneItem.flatMap(
            item -> item.channels().stream().filter(Channel::isVideoTechnology).findFirst());
    if (videoChannel.isPresent()) {
      ContactCalls.placeCall(context(), videoChannel.get(), INITIATION_TYPE);
    } else if (profile.hasNumbers()) {
      PreCall.start(
          context(),
          new CallIntentBuilder(preferredNumber(profile), INITIATION_TYPE).setIsVideoCall(true));
    }
  }

  void email(ContactProfile profile) {
    if (profile.emails.size() == 1) {
      sendEmail(profile.emails.get(0));
      return;
    }
    CharSequence[] emails = profile.emails.toArray(new CharSequence[0]);
    new AlertDialog.Builder(context())
        .setTitle(R.string.contact_profile_email)
        .setItems(emails, (dialog, which) -> sendEmail(emails[which].toString()))
        .show();
  }

  void toggleBlocked(ContactProfile profile) {
    String countryIso = GeoUtil.getCurrentCountryIso(context());
    runMutation(
        profile.blocked
            ? Blocking.unblock(context(), profile.numbers, countryIso)
            : Blocking.block(context(), profile.numbers, countryIso),
        onChanged);
  }

  void confirmDelete(ContactProfile profile) {
    new AlertDialog.Builder(context())
        .setMessage(R.string.contact_profile_delete_confirm)
        .setPositiveButton(R.string.contact_profile_delete, (dialog, which) -> delete(profile))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void delete(ContactProfile profile) {
    Context appContext = context().getApplicationContext();
    runMutation(
        DialerExecutorComponent.get(appContext)
            .backgroundExecutor()
            .submit(
                () -> {
                  appContext.getContentResolver().delete(profile.lookupUri, null, null);
                  return null;
                }),
        onDeleted);
  }

  private void sendEmail(String address) {
    DialerUtils.startActivityWithErrorToast(
        context(), new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)));
  }

  private static String preferredNumber(ContactProfile profile) {
    return profile
        .phoneItem
        .map(SpeedDialUiItem::defaultChannel)
        .filter(channel -> !channel.isVideoTechnology())
        .map(Channel::number)
        .orElse(profile.numbers.get(0));
  }

  private void runMutation(ListenableFuture<Void> future, Runnable onSuccess) {
    mutationListener.listen(
        context(),
        future,
        unused -> onSuccess.run(),
        throwable -> {
          LogUtil.e("ContactProfileActions.runMutation", "contact update failed", throwable);
          Toast.makeText(context(), R.string.contact_profile_action_failed, Toast.LENGTH_SHORT)
              .show();
        });
  }

  private Context context() {
    return fragment.requireContext();
  }
}
