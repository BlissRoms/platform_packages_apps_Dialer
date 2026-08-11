/*
 * Copyright (C) 2018 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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
 * limitations under the License
 */

package com.android.dialer.main.impl.bottomnav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.main.impl.MainActivity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/** Dialer Bottom Nav Bar for {@link MainActivity}. */
public final class BottomNavBar extends LinearLayout {

  /** Index for each tab in the bottom nav. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    TabIndex.NONE,
    TabIndex.CALL_LOG,
    TabIndex.CONTACTS,
    TabIndex.VOICEMAIL,
  })
  public @interface TabIndex {
    int NONE = -1;
    // Index 0 (formerly SPEED_DIAL) intentionally unused to keep persisted tab indices stable.
    int CALL_LOG = 1;
    int CONTACTS = 2;
    int VOICEMAIL = 3;
  }

  private final List<OnBottomNavTabSelectedListener> listeners = new ArrayList<>();

  private BottomNavItem callLog;
  private BottomNavItem contacts;
  private BottomNavItem voicemail;
  private @TabIndex int selectedTab;

  private static final float BLUR_RADIUS_PX = 40f;
  @Nullable private View blurSource;
  @Nullable private RenderNode blurNode;
  @Nullable private ViewTreeObserver.OnPreDrawListener blurPreDrawListener;
  private final Path blurClipPath = new Path();
  private final RectF blurClipRect = new RectF();
  private final Paint blurTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int[] blurSelfLoc = new int[2];
  private final int[] blurSourceLoc = new int[2];

  public BottomNavBar(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    blurTintPaint.setColor(context.getColor(R.color.recents_nav_glass_tint));
  }

  public void setBlurSource(@Nullable View source) {
    if (blurSource == source) {
      return;
    }
    detachBlurListener();
    blurSource = source;
    if (source == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return;
    }
    if (blurNode == null) {
      blurNode = new RenderNode("bottomNavBlur");
      blurNode.setRenderEffect(
          RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP));
    }
    blurPreDrawListener =
        () -> {
          invalidate();
          return true;
        };
    source.getViewTreeObserver().addOnPreDrawListener(blurPreDrawListener);
    invalidate();
  }

  private void detachBlurListener() {
    if (blurSource != null && blurPreDrawListener != null) {
      ViewTreeObserver observer = blurSource.getViewTreeObserver();
      if (observer.isAlive()) {
        observer.removeOnPreDrawListener(blurPreDrawListener);
      }
    }
    blurPreDrawListener = null;
  }

  @Override
  protected void onDetachedFromWindow() {
    detachBlurListener();
    super.onDetachedFromWindow();
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    drawBlurBackdrop(canvas);
    super.dispatchDraw(canvas);
  }

  private void drawBlurBackdrop(Canvas canvas) {
    RenderNode node = blurNode;
    View source = blurSource;
    int w = getWidth();
    int h = getHeight();
    if (node == null
        || source == null
        || w == 0
        || h == 0
        || !canvas.isHardwareAccelerated()
        || !source.isLaidOut()) {
      return;
    }

    getLocationInWindow(blurSelfLoc);
    source.getLocationInWindow(blurSourceLoc);
    int dx = blurSelfLoc[0] - blurSourceLoc[0];
    int dy = blurSelfLoc[1] - blurSourceLoc[1];

    node.setPosition(0, 0, w, h);
    RecordingCanvas recording = node.beginRecording();
    recording.drawColor(getContext().getColor(R.color.recents_screen_bg));
    recording.translate(-dx, -dy);
    source.draw(recording);
    node.endRecording();

    float radius = h / 2f;
    blurClipRect.set(0f, 0f, w, h);
    blurClipPath.reset();
    blurClipPath.addRoundRect(blurClipRect, radius, radius, Path.Direction.CW);

    int save = canvas.save();
    canvas.clipPath(blurClipPath);
    canvas.drawRenderNode(node);
    canvas.drawRoundRect(blurClipRect, radius, radius, blurTintPaint);
    canvas.restoreToCount(save);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    callLog = findViewById(R.id.call_log_tab);
    contacts = findViewById(R.id.contacts_tab);
    voicemail = findViewById(R.id.voicemail_tab);

    callLog.setup(R.string.bottom_nav_calls, R.drawable.quantum_ic_access_time_vd_theme_24,
            R.drawable.quantum_ic_clock_filled_vd_theme_24);
    contacts.setup(R.string.tab_all_contacts, R.drawable.quantum_ic_people_outline_vd_theme_24,
            R.drawable.quantum_ic_people_vd_theme_24);
    voicemail.setup(R.string.tab_title_voicemail, R.drawable.quantum_ic_voicemail_vd_theme_24,
            R.drawable.quantum_ic_voicemail_vd_theme_24);

    callLog.setOnClickListener(v -> selectTab(TabIndex.CALL_LOG));
    contacts.setOnClickListener(v -> selectTab(TabIndex.CONTACTS));
    voicemail.setOnClickListener(v -> selectTab(TabIndex.VOICEMAIL));
  }

  private void setSelected(View view) {
    callLog.setSelected(view == callLog);
    contacts.setSelected(view == contacts);
    voicemail.setSelected(view == voicemail);
  }

  /**
   * Select tab for uesr and non-user click.
   *
   * @param tab {@link TabIndex}
   */
  public void selectTab(@TabIndex int tab) {
    if (tab == TabIndex.CALL_LOG) {
      selectedTab = TabIndex.CALL_LOG;
      setSelected(callLog);
    } else if (tab == TabIndex.CONTACTS) {
      selectedTab = TabIndex.CONTACTS;
      setSelected(contacts);
    } else if (tab == TabIndex.VOICEMAIL) {
      selectedTab = TabIndex.VOICEMAIL;
      setSelected(voicemail);
    } else {
      throw new IllegalStateException("Invalid tab: " + tab);
    }

    updateListeners(selectedTab);
  }

  /**
   * Displays or hides the voicemail tab.
   *
   * <p>In the event that the voicemail tab was earlier visible but is now no longer visible, we
   * move to the call log tab.
   *
   * @param showTab whether to hide or show the voicemail
   */
  public void showVoicemail(boolean showTab) {
    LogUtil.i("OldMainActivityPeer.showVoicemail", "showing Tab:%b", showTab);
    int voicemailpreviousVisibility = voicemail.getVisibility();
    voicemail.setVisibility(showTab ? View.VISIBLE : View.GONE);
    int voicemailcurrentVisibility = voicemail.getVisibility();

    if (voicemailpreviousVisibility != voicemailcurrentVisibility
        && voicemailpreviousVisibility == View.VISIBLE
        && getSelectedTab() == TabIndex.VOICEMAIL) {
      LogUtil.i("OldMainActivityPeer.showVoicemail", "hid VM tab and moved to call log tab");
      selectTab(TabIndex.CALL_LOG);
    }
  }

  public void setNotificationCount(@TabIndex int tab, int count) {
    if (tab == TabIndex.CALL_LOG) {
      callLog.setNotificationCount(count);
    } else if (tab == TabIndex.CONTACTS) {
      contacts.setNotificationCount(count);
    } else if (tab == TabIndex.VOICEMAIL) {
      voicemail.setNotificationCount(count);
    } else {
      throw new IllegalStateException("Invalid tab: " + tab);
    }
  }

  public void addOnTabSelectedListener(OnBottomNavTabSelectedListener listener) {
    listeners.add(listener);
  }

  private void updateListeners(@TabIndex int tabIndex) {
    for (OnBottomNavTabSelectedListener listener : listeners) {
      switch (tabIndex) {
        case TabIndex.CALL_LOG:
          listener.onCallLogSelected();
          break;
        case TabIndex.CONTACTS:
          listener.onContactsSelected();
          break;
        case TabIndex.VOICEMAIL:
          listener.onVoicemailSelected();
          break;
        default:
          throw Assert.createIllegalStateFailException("Invalid tab: " + tabIndex);
      }
    }
  }

  @TabIndex
  public int getSelectedTab() {
    return selectedTab;
  }

  /** Listener for bottom nav tab's on click events. */
  public interface OnBottomNavTabSelectedListener {

    /** Call Log tab was clicked. */
    void onCallLogSelected();

    /** Contacts tab was clicked. */
    void onContactsSelected();

    /** Voicemail tab was clicked. */
    void onVoicemailSelected();
  }
}
