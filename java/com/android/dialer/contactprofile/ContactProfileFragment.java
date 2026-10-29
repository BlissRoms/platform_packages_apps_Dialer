/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.contactprofile;

import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog.Calls;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.QuickContactBadge;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;
import com.android.dialer.calllogutils.CallLogDurations;
import com.android.dialer.common.FragmentUtils;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.android.dialer.common.concurrent.SupportUiListener;
import com.android.dialer.contactphoto.ContactPhotoManager;
import com.android.dialer.contactprofile.ContactProfile.RecentCall;
import com.android.dialer.lettertile.LetterTileDrawable;
import com.android.dialer.theme.base.CardPosition;
import com.android.dialer.util.CallUtil;
import com.android.dialer.util.DialerUtils;

import java.util.Optional;
import java.util.function.Consumer;

public final class ContactProfileFragment extends Fragment {

  private static final String ARG_CONTACT_URI = "contact_uri";
  private static final long RELOAD_DELAY_MILLIS = 300;
  private static final long CONTENT_FADE_IN_MILLIS = 150;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable reloadRunnable = this::reload;
  private final ContentObserver contactObserver =
      new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
          handler.removeCallbacks(reloadRunnable);
          handler.postDelayed(reloadRunnable, RELOAD_DELAY_MILLIS);
        }
      };

  private SupportUiListener<Optional<ContactProfile>> loaderListener;
  private ContactProfileActions actions;
  @Nullable private ContactProfile profile;

  private View content;
  private ImageView banner;
  private QuickContactBadge photo;
  private TextView name;
  private ImageButton star;
  private View callButton;
  private View messageButton;
  private View videoButton;
  private View emailButton;
  private TextView recentLabel;
  private LinearLayout recentList;
  private TextView blockRow;
  private TextView deleteRow;

  public interface Host {
    void onContactProfileClosed();
  }

  public static ContactProfileFragment newInstance(Uri contactUri) {
    Bundle args = new Bundle();
    args.putParcelable(ARG_CONTACT_URI, contactUri);
    ContactProfileFragment fragment = new ContactProfileFragment();
    fragment.setArguments(args);
    return fragment;
  }

  public Uri getContactUri() {
    return requireArguments().getParcelable(ARG_CONTACT_URI, Uri.class);
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    View view = inflater.inflate(R.layout.contact_profile_fragment, container, false);
    content = view.findViewById(R.id.contact_profile_content);
    banner = view.findViewById(R.id.contact_profile_banner);
    photo = view.findViewById(R.id.contact_profile_photo);
    photo.setClickable(false);
    name = view.findViewById(R.id.contact_profile_name);
    star = view.findViewById(R.id.contact_profile_star);
    callButton = view.findViewById(R.id.contact_profile_call);
    messageButton = view.findViewById(R.id.contact_profile_message);
    videoButton = view.findViewById(R.id.contact_profile_video);
    emailButton = view.findViewById(R.id.contact_profile_email);
    recentLabel = view.findViewById(R.id.contact_profile_recent_label);
    recentList = view.findViewById(R.id.contact_profile_recent_list);
    blockRow = view.findViewById(R.id.contact_profile_block);
    deleteRow = view.findViewById(R.id.contact_profile_delete);

    loaderListener =
        DialerExecutorComponent.get(requireContext())
            .createUiListener(getChildFragmentManager(), "contact_profile_loader");
    actions = new ContactProfileActions(this, this::reload, this::close);

    view.findViewById(R.id.contact_profile_back).setOnClickListener(v -> close());
    star.setOnClickListener(v -> withProfile(actions::toggleStar));
    view.findViewById(R.id.contact_profile_edit)
        .setOnClickListener(v -> withProfile(actions::edit));
    view.findViewById(R.id.contact_profile_share)
        .setOnClickListener(v -> withProfile(actions::share));
    callButton.setOnClickListener(v -> withProfile(actions::call));
    messageButton.setOnClickListener(v -> withProfile(actions::message));
    videoButton.setOnClickListener(v -> withProfile(actions::videoCall));
    emailButton.setOnClickListener(v -> withProfile(actions::email));
    view.findViewById(R.id.contact_profile_open)
        .setOnClickListener(v -> withProfile(actions::openInContacts));
    blockRow.setOnClickListener(v -> withProfile(actions::toggleBlocked));
    deleteRow.setOnClickListener(v -> withProfile(actions::confirmDelete));
    return view;
  }

  @Override
  public void onStart() {
    super.onStart();
    requireContext()
        .getContentResolver()
        .registerContentObserver(getContactUri(), true, contactObserver);
    reload();
  }

  @Override
  public void onStop() {
    requireContext().getContentResolver().unregisterContentObserver(contactObserver);
    handler.removeCallbacks(reloadRunnable);
    super.onStop();
  }

  private void reload() {
    if (loaderListener == null || getContext() == null) {
      return;
    }
    loaderListener.listen(
        requireContext(),
        ContactProfileLoader.load(requireContext(), getContactUri()),
        loaded -> {
          if (loaded.isPresent()) {
            bind(loaded.get());
          } else {
            close();
          }
        },
        throwable -> LogUtil.e("ContactProfileFragment.reload", "failed to load", throwable));
  }

  private void withProfile(Consumer<ContactProfile> action) {
    if (profile != null) {
      action.accept(profile);
    }
  }

  private void close() {
    Host host = FragmentUtils.getParent(this, Host.class);
    if (host != null) {
      host.onContactProfileClosed();
    }
  }

  private void bind(ContactProfile contactProfile) {
    profile = contactProfile;
    if (content.getVisibility() != View.VISIBLE) {
      content.setAlpha(0f);
      content.setVisibility(View.VISIBLE);
      content.animate().alpha(1f).setDuration(CONTENT_FADE_IN_MILLIS);
    }
    ContactPhotoManager.getInstance(requireContext())
        .loadDialerThumbnailOrPhoto(
            photo,
            contactProfile.lookupUri,
            contactProfile.photoId,
            contactProfile.photoUri,
            contactProfile.name,
            LetterTileDrawable.TYPE_DEFAULT);
    if (banner != null) {
      ContactProfileBanner.bind(banner, contactProfile.photoUri);
    }
    name.setText(contactProfile.name);
    bindStar(contactProfile.starred);

    boolean hasNumbers = contactProfile.hasNumbers();
    callButton.setVisibility(hasNumbers ? View.VISIBLE : View.GONE);
    messageButton.setVisibility(hasNumbers ? View.VISIBLE : View.GONE);
    videoButton.setVisibility(
        hasNumbers && CallUtil.isVideoEnabled(requireContext()) ? View.VISIBLE : View.GONE);
    emailButton.setVisibility(contactProfile.emails.isEmpty() ? View.GONE : View.VISIBLE);
    bindFirstVisibleActionMargin();

    bindRecentCalls(contactProfile);

    boolean showBlock = hasNumbers && contactProfile.canBlock;
    blockRow.setVisibility(showBlock ? View.VISIBLE : View.GONE);
    blockRow.setText(
        contactProfile.blocked ? R.string.contact_profile_unblock : R.string.contact_profile_block);
  }

  private void bindStar(boolean starred) {
    star.setImageResource(
        starred
            ? R.drawable.quantum_ic_star_vd_theme_24
            : R.drawable.quantum_ic_star_outline_vd_theme_24);
    star.setContentDescription(
        getString(starred ? R.string.contact_profile_unstar : R.string.contact_profile_star));
  }

  private void bindFirstVisibleActionMargin() {
    boolean first = true;
    int gap = getResources().getDimensionPixelSize(R.dimen.contact_profile_action_gap);
    for (View button : new View[] {callButton, messageButton, videoButton, emailButton}) {
      if (button.getVisibility() != View.VISIBLE) {
        continue;
      }
      MarginLayoutParams params = (MarginLayoutParams) button.getLayoutParams();
      params.setMarginStart(first ? 0 : gap);
      button.setLayoutParams(params);
      first = false;
    }
  }

  private void bindRecentCalls(ContactProfile contactProfile) {
    recentList.removeAllViews();
    boolean hasCalls = !contactProfile.recentCalls.isEmpty();
    recentLabel.setVisibility(hasCalls ? View.VISIBLE : View.GONE);
    recentList.setVisibility(hasCalls ? View.VISIBLE : View.GONE);
    int count = contactProfile.recentCalls.size();
    int gap = getResources().getDimensionPixelSize(R.dimen.contact_profile_row_gap);
    LayoutInflater inflater = LayoutInflater.from(requireContext());
    for (int i = 0; i < count; i++) {
      RecentCall call = contactProfile.recentCalls.get(i);
      View row = inflater.inflate(R.layout.contact_profile_call_row, recentList, false);
      CardPosition position = CardPosition.of(i > 0, i < count - 1);
      row.setBackgroundResource(CardPosition.background(position));
      MarginLayoutParams params = (MarginLayoutParams) row.getLayoutParams();
      params.bottomMargin = position.hasRowBelow() ? gap : 0;
      row.setLayoutParams(params);
      bindRecentCall(row, call);
      row.setOnClickListener(v -> actions.callNumber(call.number));
      recentList.addView(row);
    }
  }

  private void bindRecentCall(View row, RecentCall call) {
    TextView title = row.findViewById(R.id.contact_profile_call_title);
    title.setText(
        call.durationSeconds > 0
            ? getString(
                R.string.contact_profile_call_time,
                CallLogDurations.formatDuration(requireContext(), call.durationSeconds))
            : getString(callTypeLabel(call.callType)));
    ImageView type = row.findViewById(R.id.contact_profile_call_type);
    type.setImageResource(callTypeIcon(call.callType));
    type.setImageTintList(
        ColorStateList.valueOf(
            DialerUtils.resolveColor(
                requireContext(),
                call.callType == Calls.MISSED_TYPE || call.callType == Calls.REJECTED_TYPE
                    ? R.attr.dialerColorDestructiveText
                    : R.attr.dialerColorAccentText)));
    TextView meta = row.findViewById(R.id.contact_profile_call_meta);
    CharSequence time =
        DateUtils.getRelativeTimeSpanString(
            call.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    meta.setText(
        call.numberLabel.length() == 0
            ? time
            : getString(R.string.contact_profile_call_meta, call.numberLabel, time));
  }

  @StringRes
  private static int callTypeLabel(int callType) {
    switch (callType) {
      case Calls.OUTGOING_TYPE:
        return R.string.type_outgoing;
      case Calls.MISSED_TYPE:
        return R.string.type_missed;
      case Calls.REJECTED_TYPE:
        return R.string.type_rejected;
      case Calls.INCOMING_TYPE:
      default:
        return R.string.type_incoming;
    }
  }

  @DrawableRes
  private static int callTypeIcon(int callType) {
    switch (callType) {
      case Calls.OUTGOING_TYPE:
        return R.drawable.quantum_ic_call_made_vd_theme_24;
      case Calls.MISSED_TYPE:
      case Calls.REJECTED_TYPE:
        return R.drawable.quantum_ic_call_missed_vd_theme_24;
      case Calls.INCOMING_TYPE:
      default:
        return R.drawable.quantum_ic_call_received_vd_theme_24;
    }
  }
}
