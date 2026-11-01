/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.dialer.app.calllog;

import android.app.Activity;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.provider.BlockedNumberContract;
import android.provider.CallLog;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.contacts.common.ContactsUtils;
import com.android.dialer.theme.base.CardPosition;
import com.android.dialer.R;
import com.android.dialer.app.calllog.CallLogFragment.CallLogFragmentListener;
import com.android.dialer.app.calllog.CallLogGroupBuilder.GroupCreator;
import com.android.dialer.app.calllog.calllogcache.CallLogCache;
import com.android.dialer.app.contactinfo.ContactInfoCache;
import com.android.dialer.app.voicemail.VoicemailPlaybackPresenter;
import com.android.dialer.app.voicemail.VoicemailPlaybackPresenter.OnVoicemailDeletedListener;
import com.android.dialer.blocking.FilteredNumberAsyncQueryHandler;
import com.android.dialer.calldetails.CallDetailsEntries;
import com.android.dialer.calldetails.CallDetailsEntries.CallDetailsEntry;
import com.android.dialer.calllogutils.CallbackActionHelper.CallbackAction;
import com.android.dialer.calllogutils.PhoneCallDetails;
import com.android.dialer.common.Assert;
import com.android.dialer.common.FragmentUtils.FragmentUtilListener;
import com.android.dialer.common.LogUtil;
import com.android.dialer.contacts.ContactsComponent;
import com.android.dialer.logging.ContactSource;
import com.android.dialer.main.MainActivityPeer;
import com.android.dialer.phonenumbercache.CallLogQuery;
import com.android.dialer.phonenumbercache.ContactInfo;
import com.android.dialer.phonenumbercache.ContactInfoHelper;
import com.android.dialer.phonenumberutil.PhoneNumberHelper;
import com.android.dialer.telecom.TelecomUtil;
import com.android.dialer.util.PermissionsUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adapter class to fill in data for the Call Log. */
public class CallLogAdapter extends GroupingListAdapter
    implements GroupCreator, OnVoicemailDeletedListener {

  // Types of activities the call log adapter is used for
  public static final int ACTIVITY_TYPE_CALL_LOG = 1;
  public static final int ACTIVITY_TYPE_DIALTACTS = 2;
  private static final int NO_EXPANDED_LIST_ITEM = -1;
  public static final int ALERT_POSITION = 0;
  private static final int VIEW_TYPE_ALERT = 1;
  private static final int VIEW_TYPE_CALLLOG = 2;

  private static final String KEY_EXPANDED_POSITION = "expanded_position";
  private static final String KEY_EXPANDED_ROW_ID = "expanded_row_id";
  private static final String KEY_ACTION_MODE = "action_mode_selected_items";
  private static final String KEY_SELECTION_MODE = "selection_mode";
  private static final String KEY_ROW_ID = "row_id";
  private static final String KEY_CALL_IDS = "call_ids";
  private static final String KEY_VOICEMAIL_URI = "voicemail_uri";

  public static final String LOAD_DATA_TASK_IDENTIFIER = "load_data";

  protected final Activity activity;
  protected final VoicemailPlaybackPresenter voicemailPlaybackPresenter;
  /** Cache for repeated requests to Telecom/Telephony. */
  protected final CallLogCache callLogCache;

  private final CallFetcher callFetcher;
  private final OnActionModeStateChangedListener actionModeStateChangedListener;
  private final MultiSelectRemoveView multiSelectRemoveView;
  @NonNull
  private final FilteredNumberAsyncQueryHandler filteredNumberAsyncQueryHandler;
  private final int activityType;

  /** Instance of helper class for managing views. */
  private final CallLogListItemHelper callLogListItemHelper;
  /** Helper to group call log entries. */
  private final CallLogGroupBuilder callLogGroupBuilder;

  private final ContactInfoCache contactInfoCache;
  // Tracks the position of the currently expanded list item.
  private int currentlyExpandedPosition = RecyclerView.NO_POSITION;
  // Tracks the rowId of the currently expanded list item, so the position can be updated if there
  // are any changes to the call log entries, such as additions or removals.
  private long currentlyExpandedRowId = NO_EXPANDED_LIST_ITEM;

  private final CallLogAlertManager callLogAlertManager;

  private boolean isSelectionMode;
  private final Map<Long, SelectedEntry> selectedItems = new LinkedHashMap<>();

  private static final class SelectedEntry {
    private final long[] callIds;
    @Nullable private final String voicemailUri;

    SelectedEntry(long[] callIds, @Nullable String voicemailUri) {
      this.callIds = callIds.clone();
      this.voicemailUri = voicemailUri;
    }
  }

  public boolean isSelectionMode() {
    return isSelectionMode;
  }

  private void startSelection() {
    isSelectionMode = true;
    collapseExpandedCard();
    announceforAccessibility(
        activity.getCurrentFocus(),
        activity.getString(R.string.description_entering_bulk_action_mode));
    multiSelectRemoveView.showSelectionHeader(true);
    if (actionModeStateChangedListener != null) {
      actionModeStateChangedListener.onActionModeStateChanged(this::finishSelection, true);
    }
    notifyDataSetChanged();
  }

  public void finishSelection() {
    if (!isSelectionMode) {
      return;
    }
    isSelectionMode = false;
    selectedItems.clear();
    announceforAccessibility(
        activity.getCurrentFocus(),
        activity.getString(R.string.description_leaving_bulk_action_mode));
    multiSelectRemoveView.showSelectionHeader(false);
    if (actionModeStateChangedListener != null) {
      actionModeStateChangedListener.onActionModeStateChanged(null, false);
    }
    notifyDataSetChanged();
  }

  public void deleteSelection() {
    if (selectedItems.isEmpty()) {
      return;
    }
    List<SelectedEntry> entriesToDelete = new ArrayList<>(selectedItems.values());
    new AlertDialog.Builder(activity)
        .setTitle(getDeleteSelectionTitle(entriesToDelete))
        .setPositiveButton(
            R.string.voicemailMultiSelectDeleteConfirm,
            (dialog, button) -> {
              deleteEntries(entriesToDelete);
              finishSelection();
            })
        .setNegativeButton(R.string.voicemailMultiSelectDeleteCancel, null)
        .show();
  }

  private CharSequence getDeleteSelectionTitle(List<SelectedEntry> entries) {
    boolean areAllVoicemails = true;
    for (SelectedEntry entry : entries) {
      areAllVoicemails &= entry.voicemailUri != null;
    }
    Resources resources = activity.getResources();
    return areAllVoicemails
        ? resources.getQuantityText(
            R.plurals.delete_voicemails_confirmation_dialog_title, entries.size())
        : resources.getQuantityString(
            R.plurals.call_log_delete_selected_title, entries.size(), entries.size());
  }

  private void deleteEntries(List<SelectedEntry> entries) {
    List<Long> callIds = new ArrayList<>();
    for (SelectedEntry entry : entries) {
      if (entry.voicemailUri != null) {
        CallLogAsyncTaskUtil.deleteVoicemail(activity, Uri.parse(entry.voicemailUri), null);
      } else {
        for (long callId : entry.callIds) {
          callIds.add(callId);
        }
      }
    }
    if (!callIds.isEmpty()) {
      CallLogListItemViewHolder.deleteCalls(
          activity, callIds.stream().mapToLong(Long::longValue).toArray());
    }
  }

  private final View.OnLongClickListener longPressListener =
      v -> {
        CallLogListItemViewHolder viewHolder = (CallLogListItemViewHolder) v.getTag();
        if (viewHolder == null) {
          return false;
        }
        if (!isSelectionMode) {
          startSelection();
        }
        toggleSelection(viewHolder);
        return true;
      };

  /** The OnClickListener used to expand or collapse the action buttons of a call log entry. */
  private final View.OnClickListener expandCollapseListener =
      new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          CallLogListItemViewHolder viewHolder = (CallLogListItemViewHolder) v.getTag();
          if (viewHolder == null) {
            return;
          }
          if (isSelectionMode) {
            toggleSelection(viewHolder);
            return;
          }

          if (voicemailPlaybackPresenter != null) {
            // Always reset the voicemail playback state on expand or collapse.
            voicemailPlaybackPresenter.resetAll();
          }

          if (viewHolder.rowId == currentlyExpandedRowId) {
            // Hide actions, if the clicked item is the expanded item.
            viewHolder.showActions(false);

            currentlyExpandedPosition = RecyclerView.NO_POSITION;
            currentlyExpandedRowId = NO_EXPANDED_LIST_ITEM;
          } else {
            if (viewHolder.callType == CallLog.Calls.MISSED_TYPE) {
              CallLogAsyncTaskUtil.markCallAsRead(activity, viewHolder.callIds);
              if (activityType == ACTIVITY_TYPE_DIALTACTS) {
                Assert.checkState(
                    v.getContext() instanceof MainActivityPeer.PeerSupplier,
                    "%s is not a PeerSupplier",
                    v.getContext().getClass());
                // This is really bad, but we must do this to prevent a dependency cycle, enforce
                // best practices in new code, and avoid refactoring DialtactsActivity.
                ((FragmentUtilListener) ((MainActivityPeer.PeerSupplier) v.getContext()).getPeer())
                    .getImpl(CallLogFragmentListener.class)
                    .updateTabUnreadCounts();
              }
            }
            expandViewHolderActions(viewHolder);
          }
        }
      };

  @Nullable
  public RecyclerView.OnScrollListener getOnScrollListener() {
    return null;
  }

  private void toggleSelection(CallLogListItemViewHolder viewHolder) {
    boolean isSelected = !selectedItems.containsKey(viewHolder.rowId);
    if (isSelected) {
      selectedItems.put(
          viewHolder.rowId, new SelectedEntry(viewHolder.callIds, viewHolder.voicemailUri));
    } else {
      selectedItems.remove(viewHolder.rowId);
    }
    announceforAccessibility(
        activity.getCurrentFocus(),
        activity.getString(
            isSelected
                ? R.string.description_selecting_bulk_action_mode
                : R.string.description_unselecting_bulk_action_mode,
            viewHolder.nameOrNumber));
    showSelectionState(viewHolder);
    notifySelectionChanged();
  }

  private void showSelectionState(CallLogListItemViewHolder views) {
    boolean isSelected = isSelectionMode && selectedItems.containsKey(views.rowId);
    views.checkBoxView.setVisibility(isSelected ? View.VISIBLE : View.GONE);
    views.quickContactView.setVisibility(isSelected ? View.GONE : View.VISIBLE);
  }

  private void notifySelectionChanged() {
    if (isSelectionMode) {
      int selectedCount = selectedItems.size();
      multiSelectRemoveView.onSelectionChanged(
          selectedCount, selectedCount > 0 && selectedCount == getVisibleGroups().size());
    }
  }

  private void announceforAccessibility(View view, String announcement) {
    if (view != null) {
      view.announceForAccessibility(announcement);
    }
  }

  /**
   * A list of {@link CallLogQuery#ID} that will be hidden. The hide might be temporary so instead
   * if removing an item, it will be shown as an invisible view. This simplifies the calculation of
   * item position.
   */
  @NonNull private final Set<Long> hiddenRowIds = new ArraySet<>();
  /**
   * Holds a list of URIs that are pending deletion or undo. If the activity ends before the undo
   * timeout, all of the pending URIs will be deleted.
   *
   * <p>TODO(twyen): move this and OnVoicemailDeletedListener to somewhere like {@link
   * VisualVoicemailCallLogFragment}. The CallLogAdapter does not need to know about what to do with
   * hidden item or what to hide.
   */
  @NonNull private final Set<Uri> hiddenItemUris = new ArraySet<>();

  private final CallLogListItemViewHolder.OnClickListener blockReportSpamListener;

  /**
   * Map, keyed by call ID, used to track the callback action for a call. Calls associated with the
   * same callback action will be put into the same primary call group in {@link
   * com.android.dialer.app.calllog.CallLogGroupBuilder}. This information is used to set the
   * callback icon and trigger the corresponding action.
   */
  private final Map<Long, Integer> callbackActions = new ArrayMap<>();

  /**
   * Map, keyed by call ID, used to track the day group for a call. As call log entries are put into
   * the primary call groups in {@link com.android.dialer.app.calllog.CallLogGroupBuilder}, they are
   * also assigned a secondary "day group". This map tracks the day group assigned to all calls in
   * the call log. This information is used to trigger the display of a day group header above the
   * call log entry at the start of a day group. Note: Multiple calls are grouped into a single
   * primary "call group" in the call log, and the cursor used to bind rows includes all of these
   * calls. When determining if a day group change has occurred it is necessary to look at the last
   * entry in the call log to determine its day group. This map provides a means of determining the
   * previous day group without having to reverse the cursor to the start of the previous day call
   * log entry.
   */
  private final Map<Long, Integer> dayGroups = new ArrayMap<>();

  private boolean loading = true;

  public CallLogAdapter(
      Activity activity,
      ViewGroup alertContainer,
      CallFetcher callFetcher,
      MultiSelectRemoveView multiSelectRemoveView,
      OnActionModeStateChangedListener actionModeStateChangedListener,
      CallLogCache callLogCache,
      ContactInfoCache contactInfoCache,
      VoicemailPlaybackPresenter voicemailPlaybackPresenter,
      @NonNull FilteredNumberAsyncQueryHandler filteredNumberAsyncQueryHandler,
      int activityType) {
    super();

    this.activity = activity;
    this.callFetcher = callFetcher;
    this.actionModeStateChangedListener = actionModeStateChangedListener;
    this.multiSelectRemoveView = multiSelectRemoveView;
    this.voicemailPlaybackPresenter = voicemailPlaybackPresenter;
    if (this.voicemailPlaybackPresenter != null) {
      this.voicemailPlaybackPresenter.setOnVoicemailDeletedListener(this);
    }

    this.activityType = activityType;

    this.contactInfoCache = contactInfoCache;

    if (!PermissionsUtil.hasContactsReadPermissions(activity)) {
      this.contactInfoCache.disableRequestProcessing();
    }

    Resources resources = this.activity.getResources();

    this.callLogCache = callLogCache;

    PhoneCallDetailsHelper phoneCallDetailsHelper =
        new PhoneCallDetailsHelper(this.activity, resources, this.callLogCache);
    callLogListItemHelper =
        new CallLogListItemHelper(phoneCallDetailsHelper, resources, this.callLogCache);
    callLogGroupBuilder = new CallLogGroupBuilder(this);
    this.filteredNumberAsyncQueryHandler = Assert.isNotNull(filteredNumberAsyncQueryHandler);

    blockReportSpamListener =
        new BlockReportSpamListener(
            this.activity,
            this.activity.findViewById(R.id.call_log_fragment_root),
            ((AppCompatActivity) this.activity).getSupportFragmentManager(),
            this,
            this.filteredNumberAsyncQueryHandler);
    setHasStableIds(true);

    callLogAlertManager =
        new CallLogAlertManager(this, LayoutInflater.from(this.activity), alertContainer);
  }

  private void expandViewHolderActions(CallLogListItemViewHolder viewHolder) {
    int lastExpandedPosition = currentlyExpandedPosition;
    // Show the actions for the clicked list item.
    viewHolder.showActions(true);
    currentlyExpandedPosition = viewHolder.getBindingAdapterPosition();
    currentlyExpandedRowId = viewHolder.rowId;

    // If another item is expanded, notify it that it has changed. Its actions will be
    // hidden when it is re-binded because we change mCurrentlyExpandedRowId above.
    if (lastExpandedPosition != RecyclerView.NO_POSITION) {
      notifyItemChanged(lastExpandedPosition);
    }
  }

  public void onSaveInstanceState(Bundle outState) {
    outState.putInt(KEY_EXPANDED_POSITION, currentlyExpandedPosition);
    outState.putLong(KEY_EXPANDED_ROW_ID, currentlyExpandedRowId);

    outState.putBoolean(KEY_SELECTION_MODE, isSelectionMode);
    ArrayList<Bundle> savedEntries = new ArrayList<>();
    for (Map.Entry<Long, SelectedEntry> item : selectedItems.entrySet()) {
      Bundle savedEntry = new Bundle();
      savedEntry.putLong(KEY_ROW_ID, item.getKey());
      savedEntry.putLongArray(KEY_CALL_IDS, item.getValue().callIds);
      savedEntry.putString(KEY_VOICEMAIL_URI, item.getValue().voicemailUri);
      savedEntries.add(savedEntry);
    }
    outState.putParcelableArrayList(KEY_ACTION_MODE, savedEntries);
  }

  public void onRestoreInstanceState(Bundle savedInstanceState) {
    if (savedInstanceState != null) {
      currentlyExpandedPosition =
          savedInstanceState.getInt(KEY_EXPANDED_POSITION, RecyclerView.NO_POSITION);
      currentlyExpandedRowId =
          savedInstanceState.getLong(KEY_EXPANDED_ROW_ID, NO_EXPANDED_LIST_ITEM);
      if (savedInstanceState.getBoolean(KEY_SELECTION_MODE, false)) {
        restoreSelection(
            savedInstanceState.getParcelableArrayList(KEY_ACTION_MODE, Bundle.class));
      }
    }
  }

  private void restoreSelection(@Nullable List<Bundle> savedEntries) {
    if (savedEntries != null) {
      for (Bundle savedEntry : savedEntries) {
        long[] callIds = savedEntry.getLongArray(KEY_CALL_IDS);
        if (callIds != null) {
          selectedItems.put(
              savedEntry.getLong(KEY_ROW_ID),
              new SelectedEntry(callIds, savedEntry.getString(KEY_VOICEMAIL_URI)));
        }
      }
    }
    startSelection();
    notifySelectionChanged();
  }

  /** Requery on background thread when {@link Cursor} changes. */
  @Override
  protected void onContentChanged() {
    callFetcher.fetchCalls();
  }

  public void setLoading(boolean loading) {
    this.loading = loading;
  }

  public boolean isEmpty() {
    if (loading) {
      // We don't want the empty state to show when loading.
      return false;
    } else {
      return getItemCount() == 0;
    }
  }

  public void onResume() {
    if (PermissionsUtil.hasPermission(activity, android.Manifest.permission.READ_CONTACTS)) {
      contactInfoCache.start();
    }
    notifyDataSetChanged();
  }

  public void onPause() {
    pauseCache();
    for (Uri uri : hiddenItemUris) {
      CallLogAsyncTaskUtil.deleteVoicemail(activity, uri, null);
    }
  }

  public void onStop() {
  }

  public CallLogAlertManager getAlertManager() {
    return callLogAlertManager;
  }

  private void pauseCache() {
    contactInfoCache.stop();
    callLogCache.reset();
  }

  @Override
  protected void addGroups(Cursor cursor) {
    callLogGroupBuilder.addGroups(cursor);
  }

  @Override
  public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    if (viewType == VIEW_TYPE_ALERT) {
      return callLogAlertManager.createViewHolder(parent);
    }
    return createCallLogEntryViewHolder(parent);
  }

  /**
   * Creates a new call log entry {@link ViewHolder}.
   *
   * @param parent the parent view.
   * @return The {@link ViewHolder}.
   */
  private RecyclerView.ViewHolder createCallLogEntryViewHolder(ViewGroup parent) {
    LayoutInflater inflater = LayoutInflater.from(activity);
    View view = inflater.inflate(R.layout.call_log_list_item, parent, false);
    CallLogListItemViewHolder viewHolder =
        CallLogListItemViewHolder.create(
            view,
            activity,
            blockReportSpamListener,
            expandCollapseListener,
            longPressListener,
            this::isSelectionMode,
            callLogCache,
            callLogListItemHelper,
            voicemailPlaybackPresenter);

    viewHolder.callLogEntryView.setTag(viewHolder);

    viewHolder.primaryActionView.setTag(viewHolder);
    viewHolder.quickContactView.setTag(viewHolder);

    return viewHolder;
  }

  /**
   * Binds the views in the entry to the data in the call log. TODO: This gets called 20-30 times
   * when Dialer starts up for a single call log entry and should not. It invokes cross-process
   * methods and the repeat execution can get costly.
   *
   * @param viewHolder The view corresponding to this entry.
   * @param position The position of the entry.
   */
  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
    Trace.beginSection("onBindViewHolder: " + position);
    switch (getItemViewType(position)) {
      case VIEW_TYPE_ALERT:
        // Do nothing
        break;
      default:
        bindCallLogListViewHolder(viewHolder, position);
        break;
    }
    Trace.endSection();
  }

  @Override
  public void onViewRecycled(ViewHolder viewHolder) {
    if (viewHolder instanceof CallLogListItemViewHolder) {
      CallLogListItemViewHolder views = (CallLogListItemViewHolder) viewHolder;
      if (views.asyncTask != null) {
        views.asyncTask.cancel();
      }
    }
  }

  @Override
  public void onViewAttachedToWindow(ViewHolder viewHolder) {
    if (viewHolder instanceof CallLogListItemViewHolder) {
      ((CallLogListItemViewHolder) viewHolder).isAttachedToWindow = true;
    }
  }

  @Override
  public void onViewDetachedFromWindow(ViewHolder viewHolder) {
    if (viewHolder instanceof CallLogListItemViewHolder) {
      ((CallLogListItemViewHolder) viewHolder).isAttachedToWindow = false;
    }
  }

  /**
   * Binds the view holder for the call log list item view.
   *
   * @param viewHolder The call log list item view holder.
   * @param position The position of the list item.
   */
  private void bindCallLogListViewHolder(final ViewHolder viewHolder, final int position) {
    Cursor c = (Cursor) getItem(position);
    if (c == null) {
      return;
    }
    CallLogListItemViewHolder views = (CallLogListItemViewHolder) viewHolder;
    views.isLoaded = false;
    int groupSize = getGroupSize(position);
    CallDetailsEntries callDetailsEntries = createCallDetailsEntries(c, groupSize);
    PhoneCallDetails details = createPhoneCallDetails(c, groupSize, views);
    showSelectionState(views);
    if (isHiddenRow(views.number, c.getLong(CallLogQuery.ID))) {
      views.callLogEntryView.setVisibility(View.GONE);
      views.dayGroupHeader.setVisibility(View.GONE);
      return;
    } else {
      views.callLogEntryView.setVisibility(View.VISIBLE);
      // dayGroupHeader will be restored after loadAndRender() if it is needed.
    }
    if (currentlyExpandedRowId == views.rowId) {
      views.inflateActionViewStub();
    }
    loadAndRender(views, views.rowId, details, callDetailsEntries);
  }

  private boolean isHiddenRow(@Nullable String number, long rowId) {
    return hiddenRowIds.contains(rowId);
  }

  private void loadAndRender(
      final CallLogListItemViewHolder viewHolder,
      final long rowId,
      final PhoneCallDetails details,
      final CallDetailsEntries callDetailsEntries) {
    LogUtil.d("CallLogAdapter.loadAndRender", "position: %d", viewHolder.getBindingAdapterPosition());
    // Reset block and spam information since this view could be reused which may contain
    // outdated data.
    viewHolder.isSpam = false;
    viewHolder.isBlocked = false;

    viewHolder.setDetailedPhoneDetails(callDetailsEntries);
    final LoadDataTask loadDataTask = new LoadDataTask(viewHolder, details, rowId);

    viewHolder.asyncTask = loadDataTask;
    loadDataTask.execute();
  }

  public interface LoadDataTaskInterface {
     void execute();
     void cancel();
  }

  private class LoadDataTask implements LoadDataTaskInterface {

    private boolean mIsCancelled = false;
    private final CallLogListItemViewHolder mViewHolder;
    private final PhoneCallDetails mDetails;
    private final long mRowId;

    private final ExecutorService mExecutor;
    private final Handler mHandler;

    public LoadDataTask(CallLogListItemViewHolder viewHolder, PhoneCallDetails details,
                        long rowId) {
      mExecutor = Executors.newSingleThreadExecutor();
      mHandler = new Handler(Looper.getMainLooper());
      mViewHolder = viewHolder;
      mDetails = details;
      mRowId = rowId;
    }

    public void execute() {
      mExecutor.execute(() -> {
        final boolean success;
        mViewHolder.isBlocked = BlockedNumberContract.canCurrentUserBlockNumbers(activity) &&
                BlockedNumberContract.isBlocked(activity, mViewHolder.number);
        mDetails.isBlocked = mViewHolder.isBlocked;
        if (mIsCancelled) {
          success = false;
        } else {
          success = !mIsCancelled && loadData(mViewHolder, mRowId, mDetails);
        }

        mHandler.post(() -> {
          mViewHolder.isLoaded = true;
          if (success) {
            mViewHolder.callbackAction = getCallbackAction(mViewHolder.rowId);
            int currentDayGroup = getDayGroup(mViewHolder.rowId);
            mViewHolder.applyCardPosition(
                CardPosition.of(
                    currentDayGroup == mDetails.previousGroup,
                    currentDayGroup == mDetails.nextGroup));
            if (currentDayGroup != mDetails.previousGroup) {
              mViewHolder.dayGroupHeaderVisibility = View.VISIBLE;
              mViewHolder.dayGroupHeaderText = getGroupDescription(currentDayGroup);
            } else {
              mViewHolder.dayGroupHeaderVisibility = View.GONE;
            }
            render(mViewHolder, mDetails, mRowId);
          }
        });
      });
    }

    public void cancel() {
      mIsCancelled = true;
    }
  }

  /**
   * Initialize PhoneCallDetails by reading all data from cursor. This method must be run on main
   * thread since cursor is not thread safe.
   */
  @MainThread
  private PhoneCallDetails createPhoneCallDetails(
      Cursor cursor, int count, final CallLogListItemViewHolder views) {
    Assert.isMainThread();
    final String number = cursor.getString(CallLogQuery.NUMBER);
    final String postDialDigits = cursor.getString(CallLogQuery.POST_DIAL_DIGITS);
    final String viaNumber = cursor.getString(CallLogQuery.VIA_NUMBER);
    final int numberPresentation = cursor.getInt(CallLogQuery.NUMBER_PRESENTATION);
    final ContactInfo cachedContactInfo = ContactInfoHelper.getContactInfo(cursor);
    final PhoneCallDetails details =
        new PhoneCallDetails(number, numberPresentation, postDialDigits);
    details.viaNumber = viaNumber;
    details.countryIso = cursor.getString(CallLogQuery.COUNTRY_ISO);
    details.date = cursor.getLong(CallLogQuery.DATE);
    details.duration = cursor.getLong(CallLogQuery.DURATION);
    details.features = getCallFeatures(cursor, count);
    details.geocode = cursor.getString(CallLogQuery.GEOCODED_LOCATION);
    details.callTypes = getCallTypes(cursor, count);

    details.accountComponentName = cursor.getString(CallLogQuery.ACCOUNT_COMPONENT_NAME);
    details.accountId = cursor.getString(CallLogQuery.ACCOUNT_ID);
    details.cachedContactInfo = cachedContactInfo;

    if (!cursor.isNull(CallLogQuery.DATA_USAGE)) {
      details.dataUsage = cursor.getLong(CallLogQuery.DATA_USAGE);
    }

    views.rowId = cursor.getLong(CallLogQuery.ID);
    // Stash away the Ids of the calls so that we can support deleting a row in the call log.
    views.callIds = getCallIds(cursor, count);
    details.previousGroup = getPreviousDayGroup(cursor);
    details.nextGroup = getNextDayGroup(cursor, count);

    // Store values used when the actions ViewStub is inflated on expansion.
    views.number = number;
    views.countryIso = details.countryIso;
    views.postDialDigits = details.postDialDigits;
    views.numberPresentation = numberPresentation;

    if (details.callTypes[0] == CallLog.Calls.VOICEMAIL_TYPE
        || details.callTypes[0] == CallLog.Calls.MISSED_TYPE) {
      details.isRead = cursor.getInt(CallLogQuery.IS_READ) == 1;
    }
    views.callType = cursor.getInt(CallLogQuery.CALL_TYPE);
    views.voicemailUri = cursor.getString(CallLogQuery.VOICEMAIL_URI);
    details.voicemailUri = views.voicemailUri;

    return details;
  }

  @MainThread
  private CallDetailsEntries createCallDetailsEntries(Cursor cursor, int count) {
    Assert.isMainThread();
    int position = cursor.getPosition();
    CallDetailsEntries.Builder entries = CallDetailsEntries.newBuilder();
    for (int i = 0; i < count; i++) {
      CallDetailsEntry.Builder entry =
          CallDetailsEntry.newBuilder()
              .setCallId(cursor.getLong(CallLogQuery.ID))
              .setCallType(cursor.getInt(CallLogQuery.CALL_TYPE))
              .setDataUsage(cursor.getLong(CallLogQuery.DATA_USAGE))
              .setDate(cursor.getLong(CallLogQuery.DATE))
              .setDuration(cursor.getLong(CallLogQuery.DURATION))
              .setFeatures(cursor.getInt(CallLogQuery.FEATURES))

              .setCallMappingId(String.valueOf(cursor.getLong(CallLogQuery.DATE)));


      String phoneAccountComponentName = cursor.getString(CallLogQuery.ACCOUNT_COMPONENT_NAME);

      entries.addEntries(entry.build());
      cursor.moveToNext();
    }
    cursor.moveToPosition(position);
    return entries.build();
  }

  /**
   * Load data for call log. Any expensive operation should be put here to avoid blocking main
   * thread. Do NOT put any cursor operation here since it's not thread safe.
   */
  @WorkerThread
  private boolean loadData(CallLogListItemViewHolder views, long rowId, PhoneCallDetails details) {
    Assert.isWorkerThread();
    if (rowId != views.rowId) {
      LogUtil.i(
          "CallLogAdapter.loadData",
          "rowId of viewHolder changed after load task is issued, aborting load");
      return false;
    }

    final PhoneAccountHandle accountHandle =
        TelecomUtil.composePhoneAccountHandle(details.accountComponentName, details.accountId);

    final boolean isVoicemailNumber = callLogCache.isVoicemailNumber(accountHandle, details.number);

    // Note: Binding of the action buttons is done as required in configureActionViews when the
    // user expands the actions ViewStub.

    ContactInfo info = ContactInfo.EMPTY;
    if (PhoneNumberHelper.canPlaceCallsTo(details.number, details.numberPresentation)
        && !isVoicemailNumber) {
      // Lookup contacts with this number
      // Only do remote lookup in first 5 rows.
      int position = views.getBindingAdapterPosition();
      info =
          contactInfoCache.getValue(
              details.number + details.postDialDigits,
              details.countryIso,
              details.cachedContactInfo,
              position < 5L);
    }
    CharSequence formattedNumber =
        info.formattedNumber == null
            ? null
            : PhoneNumberUtils.createTtsSpannable(info.formattedNumber);
    details.updateDisplayNumber(activity, formattedNumber, isVoicemailNumber);

    views.displayNumber = details.displayNumber;
    views.accountHandle = accountHandle;
    details.accountHandle = accountHandle;

    if (!TextUtils.isEmpty(info.name) || !TextUtils.isEmpty(info.nameAlternative)) {
      details.contactUri = info.lookupUri;
      details.namePrimary = info.name;
      details.nameAlternative = info.nameAlternative;
      details.nameDisplayOrder =
          ContactsComponent.get(activity).contactDisplayPreferences().getDisplayOrder();
      details.numberType = info.type;
      details.numberLabel = info.label;
      details.photoUri = info.photoUri;
      details.sourceType = info.sourceType;
      details.objectId = info.objectId;
      details.contactUserType = info.userType;
    }
    LogUtil.d(
        "CallLogAdapter.loadData",
        "position:%d, update geo info: %s, cequint caller id geo: %s, photo uri: %s <- %s",
        views.getBindingAdapterPosition(),
        details.geocode,
        info.geoDescription,
        details.photoUri,
        info.photoUri);
    if (!TextUtils.isEmpty(info.geoDescription)) {
      details.geocode = info.geoDescription;
    }

    views.info = info;
    views.numberType = getNumberType(activity.getResources(), details);

    callLogListItemHelper.updatePhoneCallDetails(details);
    return true;
  }

  private static String getNumberType(Resources res, PhoneCallDetails details) {
    // Label doesn't make much sense if the information is coming from CNAP or Cequint Caller ID.
    if (details.sourceType == ContactSource.Type.SOURCE_TYPE_CNAP
        || details.sourceType == ContactSource.Type.SOURCE_TYPE_CEQUINT_CALLER_ID) {
      return "";
    }
    // Returns empty label instead of "custom" if the custom label is empty.
    if (details.numberType == Phone.TYPE_CUSTOM && TextUtils.isEmpty(details.numberLabel)) {
      return "";
    }
    return (String) Phone.getTypeLabel(res, details.numberType, details.numberLabel);
  }

  /**
   * Render item view given position. This is running on UI thread so DO NOT put any expensive
   * operation into it.
   */
  @MainThread
  private void render(CallLogListItemViewHolder views, PhoneCallDetails details, long rowId) {
    Assert.isMainThread();
    if (rowId != views.rowId) {
      LogUtil.i(
          "CallLogAdapter.render",
          "rowId of viewHolder changed after load task is issued, aborting render");
      return;
    }

    // Default case: an item in the call log.
    views.primaryActionView.setVisibility(View.VISIBLE);
    views.workIconView.setVisibility(
        details.contactUserType == ContactsUtils.USER_TYPE_WORK ? View.VISIBLE : View.GONE);

    showSelectionState(views);
    callLogListItemHelper.setPhoneCallDetails(views, details);
    if (currentlyExpandedRowId == views.rowId) {
      // In case ViewHolders were added/removed, update the expanded position if the rowIds
      // match so that we can restore the correct expanded state on rebind.
      currentlyExpandedPosition = views.getBindingAdapterPosition();
      views.showActions(true);
    } else {
      views.showActions(false);
    }
    views.dayGroupHeader.setVisibility(views.dayGroupHeaderVisibility);
    views.dayGroupHeader.setText(views.dayGroupHeaderText);
  }

  @Override
  public int getItemCount() {
    return super.getItemCount() + (callLogAlertManager.isEmpty() ? 0 : 1);
  }

  @Override
  public int getItemViewType(int position) {
    if (position == ALERT_POSITION && !callLogAlertManager.isEmpty()) {
      return VIEW_TYPE_ALERT;
    }
    return VIEW_TYPE_CALLLOG;
  }

  /**
   * Retrieves an item at the specified position, taking into account the presence of a promo card.
   *
   * @param position The position to retrieve.
   * @return The item at that position.
   */
  @Override
  public Object getItem(int position) {
    return super.getItem(position - (callLogAlertManager.isEmpty() ? 0 : 1));
  }

  @Override
  public long getItemId(int position) {
    Cursor cursor = (Cursor) getItem(position);
    if (cursor != null) {
      return cursor.getLong(CallLogQuery.ID);
    } else {
      return 0;
    }
  }

  @Override
  public int getGroupSize(int position) {
    return super.getGroupSize(position - (callLogAlertManager.isEmpty() ? 0 : 1));
  }

  protected boolean isCallLogActivity() {
    return activityType == ACTIVITY_TYPE_CALL_LOG;
  }

  /**
   * In order to implement the "undo" function, when a voicemail is "deleted" i.e. when the user
   * clicks the delete button, the deleted item is temporarily hidden from the list. If a user
   * clicks delete on a second item before the first item's undo option has expired, the first item
   * is immediately deleted so that only one item can be "undoed" at a time.
   */
  @Override
  public void onVoicemailDeleted(CallLogListItemViewHolder viewHolder, Uri uri) {
    hiddenRowIds.add(viewHolder.rowId);
    // Save the new hidden item uri in case the activity is suspend before the undo has timed out.
    hiddenItemUris.add(uri);

    collapseExpandedCard();
    notifyNeighbourhoodChanged(viewHolder.getBindingAdapterPosition());
  }

  private void collapseExpandedCard() {
    currentlyExpandedRowId = NO_EXPANDED_LIST_ITEM;
    currentlyExpandedPosition = RecyclerView.NO_POSITION;
  }

  /** When the list is changing all stored position is no longer valid. */
  public void invalidatePositions() {
    currentlyExpandedPosition = RecyclerView.NO_POSITION;
  }

  /** When the user clicks "undo", the hidden item is unhidden. */
  @Override
  public void onVoicemailDeleteUndo(long rowId, int adapterPosition, Uri uri) {
    hiddenItemUris.remove(uri);
    hiddenRowIds.remove(rowId);
    notifyNeighbourhoodChanged(adapterPosition);
  }

  private void notifyNeighbourhoodChanged(int position) {
    int first = Math.max(0, position - 1);
    int last = Math.min(getItemCount() - 1, position + 1);
    if (last >= first) {
      notifyItemRangeChanged(first, last - first + 1);
    }
  }

  /** This callback signifies that a database deletion has completed. */
  @Override
  public void onVoicemailDeletedInDatabase(long rowId, Uri uri) {
    hiddenItemUris.remove(uri);
  }

  /**
   * Retrieves the day group of the previous call in the call log. Used to determine if the day
   * group has changed and to trigger display of the day group text.
   *
   * @param cursor The call log cursor.
   * @return The previous day group, or DAY_GROUP_NONE if this is the first call.
   */
  private int getPreviousDayGroup(Cursor cursor) {
    // We want to restore the position in the cursor at the end.
    int startingPosition = cursor.getPosition();
    moveToPreviousNonHiddenRow(cursor);
    if (cursor.isBeforeFirst()) {
      cursor.moveToPosition(startingPosition);
      return CallLogGroupBuilder.DAY_GROUP_NONE;
    }
    int result = getDayGroup(cursor.getLong(CallLogQuery.ID));
    cursor.moveToPosition(startingPosition);
    return result;
  }

  private int getNextDayGroup(Cursor cursor, int groupSize) {
    int startingPosition = cursor.getPosition();
    cursor.moveToPosition(startingPosition + groupSize - 1);
    moveToNextNonHiddenRow(cursor);
    if (cursor.isAfterLast()) {
      cursor.moveToPosition(startingPosition);
      return CallLogGroupBuilder.DAY_GROUP_NONE;
    }
    int result = getDayGroup(cursor.getLong(CallLogQuery.ID));
    cursor.moveToPosition(startingPosition);
    return result;
  }

  private void moveToNextNonHiddenRow(Cursor cursor) {
    while (cursor.moveToNext() && hiddenRowIds.contains(cursor.getLong(CallLogQuery.ID))) {}
  }

  private void moveToPreviousNonHiddenRow(Cursor cursor) {
    while (cursor.moveToPrevious() && hiddenRowIds.contains(cursor.getLong(CallLogQuery.ID))) {}
  }

  /**
   * Given a call ID, look up its callback action. Callback action data are populated in {@link
   * com.android.dialer.app.calllog.CallLogGroupBuilder}.
   *
   * @param callId The call ID to retrieve the callback action.
   * @return The callback action for the call.
   */
  @MainThread
  private int getCallbackAction(long callId) {
    Integer result = callbackActions.get(callId);
    if (result != null) {
      return result;
    }
    return CallbackAction.NONE;
  }

  /**
   * Given a call ID, look up the day group the call belongs to. Day group data are populated in
   * {@link com.android.dialer.app.calllog.CallLogGroupBuilder}.
   *
   * @param callId The call ID to retrieve the day group.
   * @return The day group for the call.
   */
  @MainThread
  private int getDayGroup(long callId) {
    Integer result = dayGroups.get(callId);
    if (result != null) {
      return result;
    }
    return CallLogGroupBuilder.DAY_GROUP_NONE;
  }

  /**
   * Returns the call types for the given number of items in the cursor.
   *
   * <p>It uses the next {@code count} rows in the cursor to extract the types.
   *
   * <p>It position in the cursor is unchanged by this function.
   */
  private static int[] getCallTypes(Cursor cursor, int count) {
    int position = cursor.getPosition();
    int[] callTypes = new int[count];
    for (int index = 0; index < count; ++index) {
      callTypes[index] = cursor.getInt(CallLogQuery.CALL_TYPE);
      cursor.moveToNext();
    }
    cursor.moveToPosition(position);
    return callTypes;
  }

  /**
   * Determine the features which were enabled for any of the calls that make up a call log entry.
   *
   * @param cursor The cursor.
   * @param count The number of calls for the current call log entry.
   * @return The features.
   */
  private int getCallFeatures(Cursor cursor, int count) {
    int features = 0;
    int position = cursor.getPosition();
    for (int index = 0; index < count; ++index) {
      features |= cursor.getInt(CallLogQuery.FEATURES);
      cursor.moveToNext();
    }
    cursor.moveToPosition(position);
    return features;
  }

  /**
   * Stores the callback action associated with a call in the call log.
   *
   * @param rowId The row ID of the current call.
   * @param callbackAction The current call's callback action.
   */
  @Override
  @MainThread
  public void setCallbackAction(long rowId, @CallbackAction int callbackAction) {
    callbackActions.put(rowId, callbackAction);
  }

  /**
   * Stores the day group associated with a call in the call log.
   *
   * @param rowId The row ID of the current call.
   * @param dayGroup The day group the call belongs in.
   */
  @Override
  @MainThread
  public void setDayGroup(long rowId, int dayGroup) {
    dayGroups.put(rowId, dayGroup);
  }

  /** Clears the day group associations on re-bind of the call log. */
  @Override
  @MainThread
  public void clearDayGroups() {
    dayGroups.clear();
  }

  /**
   * Retrieves the call Ids represented by the current call log row.
   *
   * @param cursor Call log cursor to retrieve call Ids from.
   * @param groupSize Number of calls associated with the current call log row.
   * @return Array of call Ids.
   */
  private long[] getCallIds(final Cursor cursor, final int groupSize) {
    // We want to restore the position in the cursor at the end.
    int startingPosition = cursor.getPosition();
    long[] ids = new long[groupSize];
    // Copy the ids of the rows in the group.
    for (int index = 0; index < groupSize; ++index) {
      ids[index] = cursor.getLong(CallLogQuery.ID);
      cursor.moveToNext();
    }
    cursor.moveToPosition(startingPosition);
    return ids;
  }

  /**
   * Determines the description for a day group.
   *
   * @param group The day group to retrieve the description for.
   * @return The day group description.
   */
  private CharSequence getGroupDescription(int group) {
    if (group == CallLogGroupBuilder.DAY_GROUP_TODAY) {
      return activity.getResources().getString(R.string.call_log_header_today);
    } else if (group == CallLogGroupBuilder.DAY_GROUP_YESTERDAY) {
      return activity.getResources().getString(R.string.call_log_header_yesterday);
    } else if (group == CallLogGroupBuilder.DAY_GROUP_THIS_WEEK) {
      return activity.getResources().getString(R.string.call_log_header_this_week);
    } else {
      return activity.getResources().getString(R.string.call_log_header_other);
    }
  }

  public void setAllSelected(boolean isSelected) {
    selectedItems.clear();
    if (isSelected) {
      selectedItems.putAll(getVisibleGroups());
    }
    notifySelectionChanged();
    notifyDataSetChanged();
  }

  @Override
  public void changeCursor(Cursor cursor) {
    super.changeCursor(cursor);
    if (cursor != null && !selectedItems.isEmpty()) {
      reconcileSelection();
    }
    notifySelectionChanged();
  }

  private void reconcileSelection() {
    Set<Long> selectedCallIds = new ArraySet<>();
    for (SelectedEntry entry : selectedItems.values()) {
      for (long callId : entry.callIds) {
        selectedCallIds.add(callId);
      }
    }
    Map<Long, SelectedEntry> visibleGroups = getVisibleGroups();
    selectedItems.clear();
    for (Map.Entry<Long, SelectedEntry> group : visibleGroups.entrySet()) {
      for (long callId : group.getValue().callIds) {
        if (selectedCallIds.contains(callId)) {
          selectedItems.put(group.getKey(), group.getValue());
          break;
        }
      }
    }
  }

  private Map<Long, SelectedEntry> getVisibleGroups() {
    Map<Long, SelectedEntry> groups = new LinkedHashMap<>();
    for (int position = 0; position < getItemCount(); position++) {
      Cursor cursor = (Cursor) getItem(position);
      if (cursor == null) {
        continue;
      }
      long rowId = cursor.getLong(CallLogQuery.ID);
      if (!hiddenRowIds.contains(rowId)) {
        groups.put(
            rowId,
            new SelectedEntry(
                getCallIds(cursor, getGroupSize(position)),
                cursor.getString(CallLogQuery.VOICEMAIL_URI)));
      }
    }
    return groups;
  }

  /** Interface used to initiate a refresh of the content. */
  public interface CallFetcher {

    void fetchCalls();
  }

  /** Interface used to allow single tap multi select for contact photos. */
  public interface OnActionModeStateChangedListener {

    void onActionModeStateChanged(@Nullable Runnable finishSelection, boolean isEnabled);

    boolean isActionModeStateEnabled();
  }

  /** Interface used to hide the fragments. */
  public interface MultiSelectRemoveView {

    void showSelectionHeader(boolean show);

    void onSelectionChanged(int selectedCount, boolean isAllSelected);
  }
}
