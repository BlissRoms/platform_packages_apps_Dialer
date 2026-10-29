/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.contactprofile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BlockedNumberContract;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;

import androidx.annotation.WorkerThread;

import com.android.dialer.blocking.Blocking;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.android.dialer.common.database.Selection;
import com.android.dialer.contactprofile.ContactProfile.RecentCall;
import com.android.dialer.location.GeoUtil;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.android.dialer.speeddial.loader.UiItemLoaderComponent;
import com.android.dialer.util.PermissionsUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

final class ContactProfileLoader {

  private static final int RECENT_CALL_LIMIT = 3;

  private static final String[] CONTACT_PROJECTION = {
    Contacts._ID,
    Contacts.LOOKUP_KEY,
    Contacts.DISPLAY_NAME_PRIMARY,
    Contacts.PHOTO_ID,
    Contacts.PHOTO_URI,
    Contacts.STARRED,
  };

  private static final String[] CALL_PROJECTION = {
    Calls.NUMBER,
    Calls.TYPE,
    Calls.DATE,
    Calls.DURATION,
    Calls.CACHED_NUMBER_TYPE,
    Calls.CACHED_NUMBER_LABEL,
  };

  private ContactProfileLoader() {}

  static ListenableFuture<Optional<ContactProfile>> load(Context context, Uri contactUri) {
    Context appContext = context.getApplicationContext();
    ListenableFuture<Optional<ContactProfile>> base =
        DialerExecutorComponent.get(appContext)
            .backgroundExecutor()
            .submit(() -> loadWithoutBlockedState(appContext, contactUri));
    return Futures.transformAsync(
        base,
        profile -> {
          if (!profile.isPresent()
              || !profile.get().hasNumbers()
              || !BlockedNumberContract.canCurrentUserBlockNumbers(appContext)) {
            return Futures.immediateFuture(profile);
          }
          ListenableFuture<Optional<ContactProfile>> withBlockedState =
              Futures.transform(
                  Blocking.isBlocked(
                      appContext, profile.get().numbers, GeoUtil.getCurrentCountryIso(appContext)),
                  blocked ->
                      Optional.of(
                          profile.get().withBlockedState(blocked.containsValue(true), true)),
                  MoreExecutors.directExecutor());
          return Futures.catching(
              withBlockedState,
              Exception.class,
              throwable -> {
                LogUtil.e("ContactProfileLoader.load", "blocked state unavailable", throwable);
                return profile;
              },
              MoreExecutors.directExecutor());
        },
        MoreExecutors.directExecutor());
  }

  @WorkerThread
  private static Optional<ContactProfile> loadWithoutBlockedState(Context context, Uri contactUri) {
    ContentResolver resolver = context.getContentResolver();
    try (Cursor cursor = resolver.query(contactUri, CONTACT_PROJECTION, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) {
        return Optional.empty();
      }
      long contactId = cursor.getLong(0);
      String lookupKey = cursor.getString(1);
      String photoUri = cursor.getString(4);
      ImmutableList<String> numbers = loadNumbers(resolver, contactId);
      Optional<SpeedDialUiItem> phoneItem =
          UiItemLoaderComponent.get(context).speedDialUiItemMutator().loadContactSync(contactId);
      return Optional.of(
          new ContactProfile(
              contactId,
              lookupKey,
              Contacts.getLookupUri(contactId, lookupKey),
              phoneItem.map(SpeedDialUiItem::name).orElse(cursor.getString(2)),
              cursor.getLong(3),
              TextUtils.isEmpty(photoUri) ? null : Uri.parse(photoUri),
              cursor.getInt(5) == 1,
              phoneItem,
              numbers,
              loadEmails(resolver, contactId),
              loadRecentCalls(context, resolver, contactId, numbers),
              false,
              false));
    }
  }

  private static ImmutableList<String> loadNumbers(ContentResolver resolver, long contactId) {
    Set<String> numbers = new LinkedHashSet<>();
    try (Cursor cursor =
        resolver.query(
            Phone.CONTENT_URI,
            new String[] {Phone.NUMBER},
            Phone.CONTACT_ID + " = ?",
            new String[] {Long.toString(contactId)},
            Phone.IS_SUPER_PRIMARY + " DESC")) {
      while (cursor != null && cursor.moveToNext()) {
        if (!TextUtils.isEmpty(cursor.getString(0))) {
          numbers.add(cursor.getString(0));
        }
      }
    }
    return ImmutableList.copyOf(numbers);
  }

  private static ImmutableList<String> loadNormalizedNumbers(
      ContentResolver resolver, long contactId) {
    Set<String> numbers = new LinkedHashSet<>();
    try (Cursor cursor =
        resolver.query(
            Phone.CONTENT_URI,
            new String[] {Phone.NORMALIZED_NUMBER},
            Phone.CONTACT_ID + " = ?",
            new String[] {Long.toString(contactId)},
            null)) {
      while (cursor != null && cursor.moveToNext()) {
        if (!TextUtils.isEmpty(cursor.getString(0))) {
          numbers.add(cursor.getString(0));
        }
      }
    }
    return ImmutableList.copyOf(numbers);
  }

  private static ImmutableList<String> loadEmails(ContentResolver resolver, long contactId) {
    Set<String> emails = new LinkedHashSet<>();
    try (Cursor cursor =
        resolver.query(
            Email.CONTENT_URI,
            new String[] {Email.ADDRESS},
            Email.CONTACT_ID + " = ?",
            new String[] {Long.toString(contactId)},
            Email.IS_SUPER_PRIMARY + " DESC")) {
      while (cursor != null && cursor.moveToNext()) {
        if (!TextUtils.isEmpty(cursor.getString(0))) {
          emails.add(cursor.getString(0));
        }
      }
    }
    return ImmutableList.copyOf(emails);
  }

  private static ImmutableList<RecentCall> loadRecentCalls(
      Context context, ContentResolver resolver, long contactId, ImmutableList<String> numbers) {
    if (numbers.isEmpty() || !PermissionsUtil.hasCallLogReadPermissions(context)) {
      return ImmutableList.of();
    }
    Selection.Builder selection =
        Selection.builder().or(Selection.column(Calls.NUMBER).in(numbers));
    ImmutableList<String> normalizedNumbers = loadNormalizedNumbers(resolver, contactId);
    if (!normalizedNumbers.isEmpty()) {
      selection.or(Selection.column(Calls.CACHED_NORMALIZED_NUMBER).in(normalizedNumbers));
    }
    Selection built = selection.build();
    Uri uri =
        Calls.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(Calls.LIMIT_PARAM_KEY, Integer.toString(RECENT_CALL_LIMIT))
            .build();
    ImmutableList.Builder<RecentCall> calls = ImmutableList.builder();
    try (Cursor cursor =
        resolver.query(
            uri,
            CALL_PROJECTION,
            built.getSelection(),
            built.getSelectionArgs(),
            Calls.DATE + " DESC")) {
      while (cursor != null && cursor.moveToNext()) {
        calls.add(
            new RecentCall(
                cursor.getString(0),
                cursor.getInt(1),
                cursor.getLong(2),
                cursor.getLong(3),
                numberLabel(context, cursor.getInt(4), cursor.getString(5))));
      }
    }
    return calls.build();
  }

  private static CharSequence numberLabel(Context context, int type, String label) {
    if (type == Phone.TYPE_CUSTOM && TextUtils.isEmpty(label)) {
      return "";
    }
    return Phone.getTypeLabel(context.getResources(), type, label);
  }
}
