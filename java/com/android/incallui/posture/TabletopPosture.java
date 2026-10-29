/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.incallui.posture;

import android.app.Activity;
import android.graphics.Rect;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

public final class TabletopPosture {

  public interface Listener {
    void onTabletopHingeChanged(@Nullable Rect hingeBounds);
  }

  private final Activity activity;
  private final WindowInfoTrackerCallbackAdapter tracker;
  private final Consumer<WindowLayoutInfo> layoutInfoConsumer;

  public TabletopPosture(Activity activity, Listener listener) {
    this.activity = activity;
    tracker = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
    layoutInfoConsumer = info -> listener.onTabletopHingeChanged(findTabletopHinge(info));
  }

  public void start() {
    tracker.addWindowLayoutInfoListener(
        activity, ContextCompat.getMainExecutor(activity), layoutInfoConsumer);
  }

  public void stop() {
    tracker.removeWindowLayoutInfoListener(layoutInfoConsumer);
  }

  @Nullable
  private static Rect findTabletopHinge(WindowLayoutInfo info) {
    for (DisplayFeature feature : info.getDisplayFeatures()) {
      if (!(feature instanceof FoldingFeature)) {
        continue;
      }
      FoldingFeature fold = (FoldingFeature) feature;
      if (fold.getState() == FoldingFeature.State.HALF_OPENED
          && fold.getOrientation() == FoldingFeature.Orientation.HORIZONTAL) {
        return fold.getBounds();
      }
    }
    return null;
  }
}
