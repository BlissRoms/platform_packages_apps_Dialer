/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.contactprofile;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

final class ContactProfile {

  final long contactId;
  final String lookupKey;
  final Uri lookupUri;
  final String name;
  final long photoId;
  @Nullable final Uri photoUri;
  final boolean starred;
  final Optional<SpeedDialUiItem> phoneItem;
  final ImmutableList<String> numbers;
  final ImmutableList<String> emails;
  final ImmutableList<RecentCall> recentCalls;
  final boolean blocked;
  final boolean canBlock;

  ContactProfile(
      long contactId,
      String lookupKey,
      Uri lookupUri,
      String name,
      long photoId,
      @Nullable Uri photoUri,
      boolean starred,
      Optional<SpeedDialUiItem> phoneItem,
      ImmutableList<String> numbers,
      ImmutableList<String> emails,
      ImmutableList<RecentCall> recentCalls,
      boolean blocked,
      boolean canBlock) {
    this.contactId = contactId;
    this.lookupKey = lookupKey;
    this.lookupUri = lookupUri;
    this.name = name;
    this.photoId = photoId;
    this.photoUri = photoUri;
    this.starred = starred;
    this.phoneItem = phoneItem;
    this.numbers = numbers;
    this.emails = emails;
    this.recentCalls = recentCalls;
    this.blocked = blocked;
    this.canBlock = canBlock;
  }

  ContactProfile withBlockedState(boolean isBlocked, boolean isBlockingSupported) {
    return new ContactProfile(
        contactId,
        lookupKey,
        lookupUri,
        name,
        photoId,
        photoUri,
        starred,
        phoneItem,
        numbers,
        emails,
        recentCalls,
        isBlocked,
        isBlockingSupported);
  }

  boolean hasNumbers() {
    return !numbers.isEmpty();
  }

  static final class RecentCall {
    final String number;
    final int callType;
    final long date;
    final long durationSeconds;
    final CharSequence numberLabel;

    RecentCall(
        String number, int callType, long date, long durationSeconds, CharSequence numberLabel) {
      this.number = number;
      this.callType = callType;
      this.date = date;
      this.durationSeconds = durationSeconds;
      this.numberLabel = numberLabel;
    }
  }
}
