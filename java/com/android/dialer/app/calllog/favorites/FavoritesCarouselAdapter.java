/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.app.calllog.favorites;

import android.provider.ContactsContract.Contacts;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.QuickContactBadge;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.R;
import com.android.dialer.glidephotomanager.GlidePhotoManagerComponent;
import com.android.dialer.glidephotomanager.PhotoInfo;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.google.common.collect.ImmutableList;

final class FavoritesCarouselAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

  private static final int TYPE_FAVORITE = 0;
  private static final int TYPE_ADD = 1;

  private final FavoritesHeaderAdapter.Listener listener;
  private ImmutableList<SpeedDialUiItem> items = ImmutableList.of();

  FavoritesCarouselAdapter(FavoritesHeaderAdapter.Listener listener) {
    this.listener = listener;
  }

  void setItems(ImmutableList<SpeedDialUiItem> items) {
    this.items =
        items.stream().filter(SpeedDialUiItem::isStarred).collect(ImmutableList.toImmutableList());
    notifyDataSetChanged();
  }

  boolean hasFavorites() {
    return !items.isEmpty();
  }

  @Override
  public int getItemViewType(int position) {
    return position < items.size() ? TYPE_FAVORITE : TYPE_ADD;
  }

  @Override
  public int getItemCount() {
    return items.size() + 1;
  }

  @NonNull
  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    if (viewType == TYPE_ADD) {
      View view = inflater.inflate(R.layout.recents_favorite_add, parent, false);
      view.setOnClickListener(v -> listener.onAddFavoriteClicked());
      return new RecyclerView.ViewHolder(view) {};
    }
    return new FavoriteViewHolder(inflater.inflate(R.layout.recents_favorite_item, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    if (holder instanceof FavoriteViewHolder) {
      ((FavoriteViewHolder) holder).bind(items.get(position));
    }
  }

  private final class FavoriteViewHolder extends RecyclerView.ViewHolder {

    private final QuickContactBadge photo;
    private final TextView name;

    FavoriteViewHolder(View view) {
      super(view);
      photo = view.findViewById(R.id.favorite_photo);
      photo.setClickable(false);
      name = view.findViewById(R.id.favorite_name);
    }

    void bind(SpeedDialUiItem item) {
      name.setText(firstName(item.name()));
      itemView.setContentDescription(item.name());
      itemView.setOnClickListener(v -> listener.onFavoriteClicked(item));
      itemView.setOnLongClickListener(
          v -> {
            listener.onFavoriteLongClicked(photo, item);
            return true;
          });
      GlidePhotoManagerComponent.get(itemView.getContext())
          .glidePhotoManager()
          .loadQuickContactBadge(
              photo,
              PhotoInfo.newBuilder()
                  .setPhotoId(item.photoId())
                  .setPhotoUri(item.photoUri())
                  .setName(item.name())
                  .setLookupUri(
                      Contacts.getLookupUri(item.contactId(), item.lookupKey()).toString())
                  .build());
    }

    private String firstName(String fullName) {
      String trimmed = fullName.trim();
      int space = trimmed.indexOf(' ');
      return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
  }
}
