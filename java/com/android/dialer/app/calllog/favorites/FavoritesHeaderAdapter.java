/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.app.calllog.favorites;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.R;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.google.common.collect.ImmutableList;

public final class FavoritesHeaderAdapter
    extends RecyclerView.Adapter<FavoritesHeaderAdapter.RowViewHolder> {

  public interface Listener {
    void onFavoriteClicked(SpeedDialUiItem speedDialUiItem);

    void onFavoriteLongClicked(View anchor, SpeedDialUiItem speedDialUiItem);

    void onAddFavoriteClicked();
  }

  private final FavoritesCarouselAdapter carouselAdapter;
  private boolean shown;

  public FavoritesHeaderAdapter(Listener listener) {
    carouselAdapter = new FavoritesCarouselAdapter(listener);
    setHasStableIds(true);
  }

  @Override
  public long getItemId(int position) {
    return 0;
  }

  public void setFavorites(ImmutableList<SpeedDialUiItem> favorites) {
    boolean wasVisible = isVisible();
    carouselAdapter.setItems(favorites);
    updateRow(wasVisible);
  }

  public void setShown(boolean shown) {
    boolean wasVisible = isVisible();
    this.shown = shown;
    updateRow(wasVisible);
  }

  @NonNull
  @Override
  public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View row =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.recents_favorites_row, parent, false);
    return new RowViewHolder(row, carouselAdapter);
  }

  @Override
  public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {}

  @Override
  public int getItemCount() {
    return isVisible() ? 1 : 0;
  }

  private boolean isVisible() {
    return shown && carouselAdapter.hasFavorites();
  }

  private void updateRow(boolean wasVisible) {
    boolean visible = isVisible();
    if (visible && !wasVisible) {
      notifyItemInserted(0);
    } else if (!visible && wasVisible) {
      notifyItemRemoved(0);
    }
  }

  static final class RowViewHolder extends RecyclerView.ViewHolder {

    RowViewHolder(View row, FavoritesCarouselAdapter carouselAdapter) {
      super(row);
      RecyclerView carousel = row.findViewById(R.id.favorites_carousel);
      carousel.setLayoutManager(
          new LinearLayoutManager(row.getContext(), LinearLayoutManager.HORIZONTAL, false));
      carousel.setAdapter(carouselAdapter);
    }
  }
}
