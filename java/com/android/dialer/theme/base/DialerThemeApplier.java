/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import com.android.dialer.R;

import java.util.Map;
import java.util.WeakHashMap;

public final class DialerThemeApplier implements Application.ActivityLifecycleCallbacks {

  private final Map<Activity, AccentSource> appliedAccents = new WeakHashMap<>();

  private DialerThemeApplier() {}

  public static void install(Application application) {
    ThemePreferences preferences = new ThemePreferences(application);
    applyOverlays(application.getTheme(), preferences.getAccentSource());
    application.registerActivityLifecycleCallbacks(new DialerThemeApplier());
    applyNightMode(application, preferences.getThemeMode());
  }

  public static void applyOverlays(Resources.Theme theme, AccentSource accentSource) {
    theme.applyStyle(R.style.ThemeOverlay_Dialer_Brand, true);
    if (accentSource == AccentSource.WALLPAPER) {
      theme.applyStyle(R.style.ThemeOverlay_Dialer_Wallpaper, true);
    }
  }

  public static void applyNightMode(Context context, ThemeMode mode) {
    UiModeManager uiModeManager = context.getSystemService(UiModeManager.class);
    if (uiModeManager != null) {
      uiModeManager.setApplicationNightMode(toUiNightMode(mode));
    }
  }

  public static void onAccentChanged(Context context) {
    Context applicationContext = context.getApplicationContext();
    applyOverlays(
        applicationContext.getTheme(), new ThemePreferences(applicationContext).getAccentSource());
  }

  @Override
  public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
    AccentSource accentSource = new ThemePreferences(activity).getAccentSource();
    applyOverlays(activity.getTheme(), accentSource);
    appliedAccents.put(activity, accentSource);
  }

  @Override
  public void onActivityResumed(Activity activity) {
    AccentSource current = new ThemePreferences(activity).getAccentSource();
    if (AccentSource.isStale(appliedAccents.get(activity), current)) {
      activity.recreate();
    }
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    appliedAccents.remove(activity);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  private static int toUiNightMode(ThemeMode mode) {
    switch (mode) {
      case LIGHT:
        return UiModeManager.MODE_NIGHT_NO;
      case DARK:
        return UiModeManager.MODE_NIGHT_YES;
      case AUTOMATIC:
      default:
        return UiModeManager.MODE_NIGHT_AUTO;
    }
  }
}
