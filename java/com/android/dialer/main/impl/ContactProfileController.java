/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.main.impl;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;
import com.android.dialer.contactprofile.ContactProfileFragment;
import com.android.dialer.contactsfragment.ContactsFragment;
import com.android.dialer.contactsfragment.ContactsFragment.OnContactSelectedListener;
import com.android.dialer.contactsfragment.ContactsFragment.OnContactsFragmentHiddenChangedListener;
import com.android.dialer.main.impl.MainSearchController.OnSearchShowListener;
import com.android.dialer.util.TransactionSafeActivity;

final class ContactProfileController
    implements OnContactSelectedListener,
        OnContactsFragmentHiddenChangedListener,
        OnSearchShowListener,
        ContactProfileFragment.Host {

  private static final String FRAGMENT_TAG = "contact_profile_fragment";

  private final TransactionSafeActivity activity;
  private final MainSearchController searchController;

  ContactProfileController(
      TransactionSafeActivity activity, MainSearchController searchController) {
    this.activity = activity;
    this.searchController = searchController;
  }

  @Override
  public void onContactSelected(ImageView photo, Uri contactUri, long contactId) {
    if (!activity.isSafeToCommitTransactions()) {
      return;
    }
    if (isSidePane() && searchController.isInSearch()) {
      searchController.closeDialpad();
    }
    activity
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(
            R.id.contact_profile_container,
            ContactProfileFragment.newInstance(contactUri),
            FRAGMENT_TAG)
        .commit();
    setContainerVisible(true);
    publishSelection(contactUri);
  }

  @Override
  public void onContactProfileClosed() {
    hide();
  }

  @Override
  public void onContactsFragmentHiddenChanged(boolean hidden) {
    if (hidden) {
      hide();
    }
  }

  @Override
  public void onSearchOpen() {
    if (isSidePane()) {
      hide();
    }
  }

  @Override
  public void onSearchClose() {}

  boolean onBackPressed() {
    if (findFragment() == null) {
      return false;
    }
    hide();
    return true;
  }

  void hide() {
    ContactProfileFragment fragment = findFragment();
    if (fragment != null) {
      if (!activity.isSafeToCommitTransactions()) {
        return;
      }
      activity.getSupportFragmentManager().beginTransaction().remove(fragment).commit();
    }
    setContainerVisible(false);
    publishSelection(null);
  }

  void restoreVisibility() {
    ContactProfileFragment fragment = findFragment();
    setContainerVisible(fragment != null);
    publishSelection(fragment == null ? null : fragment.getContactUri());
  }

  @Nullable
  private ContactProfileFragment findFragment() {
    Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
    return fragment instanceof ContactProfileFragment ? (ContactProfileFragment) fragment : null;
  }

  private void publishSelection(@Nullable Uri contactUri) {
    Bundle result = new Bundle();
    result.putParcelable(
        ContactsFragment.SELECTED_CONTACT_URI, isSidePane() ? contactUri : null);
    activity
        .getSupportFragmentManager()
        .setFragmentResult(ContactsFragment.SELECTED_CONTACT_REQUEST, result);
  }

  private boolean isSidePane() {
    return activity.getResources().getBoolean(R.bool.dialpad_side_pane);
  }

  private void setContainerVisible(boolean visible) {
    activity
        .findViewById(R.id.contact_profile_container)
        .setVisibility(visible ? View.VISIBLE : View.GONE);
  }
}
