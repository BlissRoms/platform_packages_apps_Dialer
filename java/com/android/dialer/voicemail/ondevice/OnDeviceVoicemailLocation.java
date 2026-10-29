/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.voicemail.ondevice;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Environment;
import android.os.UserManager;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;
import com.android.dialer.common.LogUtil;

import java.io.File;
import java.io.FileNotFoundException;

public final class OnDeviceVoicemailLocation {

  private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
  private static final String PRIMARY_VOLUME = "primary";
  private static final String DEFAULT_FOLDER = "Voicemails";

  private OnDeviceVoicemailLocation() {}

  @NonNull
  public static SharedPreferences prefs(@NonNull Context context) {
    String prefName = context.getPackageName() + "_preferences";
    return context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(prefName, Context.MODE_MULTI_PROCESS);
  }

  @Nullable
  public static Uri getTreeUri(@NonNull Context context) {
    if (!isUserUnlocked(context)) {
      return null;
    }
    migrateLegacyValue(context);
    String stored = prefs(context).getString(key(context), null);
    if (stored == null) {
      return null;
    }
    Uri uri = Uri.parse(stored);
    for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
      if (permission.getUri().equals(uri) && permission.isWritePermission()) {
        return uri;
      }
    }
    return null;
  }

  public static void setTreeUri(@NonNull Context context, @NonNull Uri uri) {
    context
        .getContentResolver()
        .takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    prefs(context).edit().putString(key(context), uri.toString()).apply();
  }

  public static void migrateLegacyValue(@NonNull Context context) {
    SharedPreferences legacy = PreferenceManager.getDefaultSharedPreferences(context);
    String key = key(context);
    if (!legacy.contains(key)) {
      return;
    }
    SharedPreferences current = prefs(context);
    if (!current.contains(key)) {
      current.edit().putString(key, legacy.getString(key, null)).apply();
    }
    legacy.edit().remove(key).apply();
  }

  @NonNull
  public static String describe(@NonNull Context context) {
    Uri uri = getTreeUri(context);
    if (uri == null) {
      return defaultDirectory().getAbsolutePath();
    }
    if (!EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())) {
      return uri.toString();
    }
    String[] split = DocumentsContract.getTreeDocumentId(uri).split(":", 2);
    if (split.length < 2) {
      return uri.toString();
    }
    if (PRIMARY_VOLUME.equalsIgnoreCase(split[0])) {
      return new File(Environment.getExternalStorageDirectory(), split[1]).getAbsolutePath();
    }
    return split[0] + "/" + split[1];
  }

  @Nullable
  public static Uri createDocument(
      @NonNull Context context, @NonNull Uri treeUri, @NonNull String mimeType, @NonNull String name) {
    ContentResolver resolver = context.getContentResolver();
    Uri parent =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    try {
      return DocumentsContract.createDocument(resolver, parent, mimeType, name);
    } catch (FileNotFoundException | RuntimeException e) {
      LogUtil.e("OnDeviceVoicemailLocation.createDocument", "cannot create in chosen folder", e);
      return null;
    }
  }

  public static void deleteDocument(@NonNull Context context, @NonNull Uri document) {
    try {
      DocumentsContract.deleteDocument(context.getContentResolver(), document);
    } catch (FileNotFoundException | RuntimeException e) {
      LogUtil.w("OnDeviceVoicemailLocation.deleteDocument", "cannot delete %s", document);
    }
  }

  @NonNull
  public static File defaultDirectory() {
    return new File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
        DEFAULT_FOLDER);
  }

  private static String key(Context context) {
    return context.getString(R.string.on_device_voicemail_location_uri_key);
  }

  private static boolean isUserUnlocked(Context context) {
    UserManager userManager = context.getSystemService(UserManager.class);
    return userManager != null && userManager.isUserUnlocked();
  }
}
