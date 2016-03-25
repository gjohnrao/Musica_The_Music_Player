/*
 * *
 *  * This file is part of QuickLyric
 *  * Created by geecko
 *  *
 *  * QuickLyric is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * QuickLyric is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  * You should have received a copy of the GNU General Public License
 *  * along with QuickLyric.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.evandroid.musica.fragment;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.widget.NestedScrollView;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.text.Html;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.evandroid.musica.MainLyricActivity;
import com.evandroid.musica.R;
import com.evandroid.musica.SettingsActivity;
import com.evandroid.musica.broadcastReceiver.MusicBroadcastReceiver;
import com.evandroid.musica.lyrics.Lyrics;
import com.evandroid.musica.tasks.DownloadThread;
import com.evandroid.musica.tasks.Id3Reader;
import com.evandroid.musica.tasks.ParseTask;
import com.evandroid.musica.tasks.PresenceChecker;
import com.evandroid.musica.tasks.WriteToDatabaseTask;
import com.evandroid.musica.utils.CustomSelectionCallback;
import com.evandroid.musica.utils.DatabaseHelper;
import com.evandroid.musica.utils.LyricsTextFactory;
import com.evandroid.musica.utils.NightTimeVerifier;
import com.evandroid.musica.utils.OnlineAccessVerifier;
import com.evandroid.musica.utils.UpdateChecker;
import com.evandroid.musica.view.LrcView;
import com.evandroid.musica.view.RefreshIcon;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH;

public class LyricsViewFragment extends Fragment implements Lyrics.Callback, SwipeRefreshLayout.OnRefreshListener {

    private static BroadcastReceiver broadcastReceiver;
    public boolean lyricsPresentInDB;
    public boolean isActiveFragment = false;
    public boolean showTransitionAnim = true;
    private Lyrics mLyrics;
    private String mSearchQuery;
    private boolean mSearchFocused;
    private NestedScrollView mScrollView;
    private boolean startEmtpy = false;
    public boolean searchResultLock;
    private SwipeRefreshLayout mRefreshLayout;
    private Thread mLrcThread;
    private boolean mExpandedSearchView;
    public boolean updateChecked = false;

    public LyricsViewFragment() {
    }

    public static void sendIntent(Context context, Intent intent) {
        if (broadcastReceiver != null)
            broadcastReceiver.onReceive(context, intent);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mLyrics != null)
            try {
                outState.putByteArray("lyrics", mLyrics.toBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }

        outState.putBoolean("refreshFabEnabled", getActivity().findViewById(R.id.refresh_fab).isEnabled());

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setRetainInstance(true);
        setHasOptionsMenu(true);
        View layout = inflater.inflate(R.layout.lyrics_view, container, false);
        if (savedInstanceState != null)
            try {
                Lyrics l = Lyrics.fromBytes(savedInstanceState.getByteArray("lyrics"));
                if (l != null)
                    this.mLyrics = l;
                mSearchQuery = savedInstanceState.getString("searchQuery");
                mSearchFocused = savedInstanceState.getBoolean("searchFocused");
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        else {
            Bundle args = getArguments();
            if (args != null)
                try {
                    Lyrics lyrics = Lyrics.fromBytes(args.getByteArray("lyrics"));
                    this.mLyrics = lyrics;
                    if (lyrics != null && lyrics.getText() == null && lyrics.getArtist() != null) {
                        String artist = lyrics.getArtist();
                        String track = lyrics.getTrack();
                        String url = lyrics.getURL();
                        fetchLyrics(artist, track, url);
                        mRefreshLayout = (SwipeRefreshLayout) layout.findViewById(R.id.refresh_layout);
                        startRefreshAnimation();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
        }
        if (layout != null) {
            Bundle args = savedInstanceState != null ? savedInstanceState : getArguments();

            boolean screenOn = PreferenceManager
                    .getDefaultSharedPreferences(getActivity()).getBoolean("pref_force_screen_on", false);

            TextSwitcher textSwitcher = (TextSwitcher) layout.findViewById(R.id.switcher);
            textSwitcher.setFactory(new LyricsTextFactory(layout.getContext()));
            ActionMode.Callback callback = new CustomSelectionCallback(getActivity());
            ((TextView) textSwitcher.getChildAt(0)).setCustomSelectionActionModeCallback(callback);
            ((TextView) textSwitcher.getChildAt(1)).setCustomSelectionActionModeCallback(callback);
            textSwitcher.setKeepScreenOn(screenOn);
            layout.findViewById(R.id.lrc_view).setKeepScreenOn(screenOn);


            TextView id3TV = (TextView) layout.findViewById(R.id.id3_tv);
            SpannableString text = new SpannableString(id3TV.getText());
            text.setSpan(new UnderlineSpan(), 1, text.length() - 1, 0);
            id3TV.setText(text);

            final RefreshIcon refreshFab = (RefreshIcon) getActivity().findViewById(R.id.refresh_fab);
            refreshFab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mRefreshLayout.isRefreshing())
                        fetchCurrentLyrics(true);
                }
            });

            FloatingActionButton fab = (FloatingActionButton) getActivity().findViewById(R.id.fab);
            fab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent settingsIntent = new Intent(getActivity(), SettingsActivity.class);
                    startActivity(settingsIntent);
                }
            });



            if (args != null)
                refreshFab.setEnabled(args.getBoolean("refreshFabEnabled", true));

            mScrollView = (NestedScrollView) layout.findViewById(R.id.scrollview);
            mRefreshLayout = (SwipeRefreshLayout) layout.findViewById(R.id.refresh_layout);
            TypedValue primaryColor = new TypedValue();
            getActivity().getTheme().resolveAttribute(R.attr.colorPrimary, primaryColor, true);
            mRefreshLayout.setColorSchemeResources(primaryColor.resourceId, R.color.accent);
            float offset = getResources().getDisplayMetrics().density * 64;
            mRefreshLayout.setProgressViewEndTarget(true, (int) offset);
            mRefreshLayout.setOnRefreshListener(this);


            if (mLyrics == null) {
                if (!startEmtpy)
                    fetchCurrentLyrics(false);
            } else if (mLyrics.getFlag() == Lyrics.SEARCH_ITEM) {
                mRefreshLayout = (SwipeRefreshLayout) layout.findViewById(R.id.refresh_layout);
                startRefreshAnimation();
                if (mLyrics.getArtist() != null)
                    fetchLyrics(mLyrics.getArtist(), mLyrics.getTrack());
            } else //Rotation, resume
                update(mLyrics, layout, false);
        }
        if (broadcastReceiver == null)
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    searchResultLock = false;
                    String artist = intent.getStringExtra("artist");
                    String track = intent.getStringExtra("track");
                    if (artist != null && track != null && mRefreshLayout.isEnabled()) {
                        startRefreshAnimation();
                        new ParseTask(LyricsViewFragment.this, false, true).execute(mLyrics);
                    }
                }
            };
        return layout;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (this.isHidden())
            return;

        this.isActiveFragment = true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            this.onViewCreated(getView(), null);
            if (mLyrics != null && mLyrics.getFlag() == Lyrics.POSITIVE_RESULT && lyricsPresentInDB)
                new PresenceChecker().execute(this, new String[]{mLyrics.getArtist(), mLyrics.getTrack(),
                        mLyrics.getOriginalArtist(), mLyrics.getOriginalTrack()});
        } else
            this.isActiveFragment = false;
    }

    public void startRefreshAnimation() {
        if (mRefreshLayout == null)
            if (getActivity() != null && getView() != null)
                mRefreshLayout = (SwipeRefreshLayout) getActivity().findViewById(R.id.refresh_layout);
        if (mRefreshLayout != null)
            mRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    if (!mRefreshLayout.isRefreshing())
                        mRefreshLayout.setRefreshing(true);
                }
            });
    }

    public void stopRefreshAnimation() {
        if (mRefreshLayout == null)
            if (getActivity() != null && getView() != null)
                mRefreshLayout = (SwipeRefreshLayout) getActivity().findViewById(R.id.refresh_layout);
        if (mRefreshLayout != null)
            mRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    mRefreshLayout.setRefreshing(false);
                }
            });
    }

    public void fetchLyrics(String... params) {
        String artist = params[0];
        String title = params[1];
        String url = null;
        if (params.length > 2)
            url = params[2];
        startRefreshAnimation();

        Lyrics lyrics = null;
        if (artist != null && title != null) {
            lyrics = DatabaseHelper.get(((MainLyricActivity) getActivity()).database, new String[]{artist, title});
            if (lyrics == null)
                lyrics = DatabaseHelper.get(((MainLyricActivity) getActivity()).database, DownloadThread.correctTags(artist, title));

            if (lyrics == null && url == null &&
                    (getActivity().getSharedPreferences("slides", Context.MODE_PRIVATE).getBoolean("seen", false))
                    && (mLyrics == null || mLyrics.getFlag() != Lyrics.POSITIVE_RESULT ||
                    !("Storage".equals(mLyrics.getSource())
                            && mLyrics.getArtist().equalsIgnoreCase(artist)
                            && mLyrics.getTrack().equalsIgnoreCase(title))
            ))
                lyrics = Id3Reader.getLyrics(getActivity(), artist, title);
        } else if (url == null) {
            showFirstStart();
            return;
        }
        if (lyrics != null)
            onLyricsDownloaded(lyrics);
        else if (OnlineAccessVerifier.check(getActivity())) {
            Set<String> providersSet = PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .getStringSet("pref_providers", Collections.<String>emptySet());
            DownloadThread.refreshProviders(providersSet);


            if (url == null)
                new DownloadThread(this, artist, title).start();
            else
                new DownloadThread(this, url, artist, title).start();

            new UpdateChecker.UpdateCheckTask(this).execute();
        } else {
            lyrics = new Lyrics(Lyrics.ERROR);
            lyrics.setArtist(artist);
            lyrics.setTitle(title);
            onLyricsDownloaded(lyrics);
        }
    }

    public void fetchCurrentLyrics(boolean showMsg) {
        searchResultLock = false;
        if (mLyrics != null && mLyrics.getArtist() != null && mLyrics.getTrack() != null)
            new ParseTask(this, showMsg, false).execute(mLyrics);
        else
            new ParseTask(this, showMsg, false).execute((Object) null);
    }

    @TargetApi(16)
    private void beamLyrics(final Lyrics lyrics, Activity activity) {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            if (lyrics.getText() != null) {
                nfcAdapter.setNdefPushMessageCallback(new NfcAdapter.CreateNdefMessageCallback() {
                    @Override
                    public NdefMessage createNdefMessage(NfcEvent event) {
                        try {
                            byte[] payload = lyrics.toBytes(); // whatever data you want to send
                            NdefRecord record = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, "application/lyrics".getBytes(), new byte[0], payload);
                            return new NdefMessage(new NdefRecord[]{
                                    record, // your data
                                    NdefRecord.createApplicationRecord("com.geecko.QuickLyric"), // the "application record"
                            });
                        } catch (IOException e) {
                            return null;
                        }
                    }
                }, activity);
            }
        }
    }

    @Override
    public void onLyricsDownloaded(Lyrics lyrics) {
        if (getActivity() != null && !((MainLyricActivity) getActivity()).hasBeenDestroyed() && getView() != null)
            update(lyrics, getView(), true);
        else
            mLyrics = lyrics;
    }

    public void update(Lyrics lyrics, View layout, boolean animation) {

        TextSwitcher textSwitcher = ((TextSwitcher) layout.findViewById(R.id.switcher));
        LrcView lrcView = (LrcView) layout.findViewById(R.id.lrc_view);
        View v = getActivity().findViewById(R.id.tracks_msg);
        if (v != null)
            ((ViewGroup) v.getParent()).removeView(v);

        TextView id3TV = (TextView) layout.findViewById(R.id.id3_tv);
        RelativeLayout bugLayout = (RelativeLayout) layout.findViewById(R.id.error_msg);
        this.mLyrics = lyrics;
        if (SDK_INT >= ICE_CREAM_SANDWICH)
            beamLyrics(lyrics, this.getActivity());
        new PresenceChecker().execute(this, new String[]{lyrics.getArtist(), lyrics.getTrack(),
                lyrics.getOriginalArtist(), lyrics.getOriginalTrack()});

        if (isActiveFragment)
            ((RefreshIcon) getActivity().findViewById(R.id.refresh_fab)).show();
        EditText newLyrics = (EditText) getActivity().findViewById(R.id.edit_lyrics);
        if (newLyrics != null)
            newLyrics.setText("");

        if (lyrics.getFlag() == Lyrics.POSITIVE_RESULT) {
            if (!lyrics.isLRC()) {
                textSwitcher.setVisibility(View.VISIBLE);
                lrcView.setVisibility(View.GONE);
                if (animation)
                    textSwitcher.setText(Html.fromHtml(lyrics.getText()));
                else
                    textSwitcher.setCurrentText(Html.fromHtml(lyrics.getText()));
            } else {
                textSwitcher.setVisibility(View.GONE);
                lrcView.setVisibility(View.VISIBLE);
                lrcView.setOriginalLyrics(lyrics);
                lrcView.setSourceLrc(lyrics.getText());
                updateLRC();
            }

            bugLayout.setVisibility(View.INVISIBLE);
            if ("Storage".equals(lyrics.getSource()))
                id3TV.setVisibility(View.VISIBLE);
            else
                id3TV.setVisibility(View.GONE);
            mScrollView.post(new Runnable() {
                @Override
                public void run() {
                    mScrollView.scrollTo(0, 0); //only useful when coming from localLyricsFragment
                    mScrollView.smoothScrollTo(0, 0);
                }
            });
        } else {
            textSwitcher.setText("");
            textSwitcher.setVisibility(View.INVISIBLE);
            lrcView.setVisibility(View.INVISIBLE);
            bugLayout.setVisibility(View.VISIBLE);
            int message;
            int whyVisibility;
            if (lyrics.getFlag() == Lyrics.ERROR || !OnlineAccessVerifier.check(getActivity())) {
                message = R.string.connection_error;
                whyVisibility = TextView.GONE;
            } else {
                message = R.string.no_results;
                whyVisibility = TextView.VISIBLE;
                updateSearchView(false, lyrics.getTrack(), false);
            }
            TextView whyTextView = ((TextView) bugLayout.findViewById(R.id.bugtext_why));
            ((TextView) bugLayout.findViewById(R.id.bugtext)).setText(message);
            whyTextView.setVisibility(whyVisibility);
            whyTextView.setPaintFlags(whyTextView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            id3TV.setVisibility(View.GONE);
        }
        stopRefreshAnimation();
        getActivity().getIntent().setAction("");
        getActivity().invalidateOptionsMenu();
    }

    private void showFirstStart() {
        stopRefreshAnimation();
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        ViewGroup parent = (ViewGroup) ((ViewGroup) getActivity().findViewById(R.id.scrollview)).getChildAt(0);
        if (parent.findViewById(R.id.tracks_msg) == null)
            inflater.inflate(R.layout.no_tracks, parent);

        TypedValue typedValue = new TypedValue();
        getActivity().getTheme().resolveAttribute(R.attr.firstLaunchCoverDrawable, typedValue, true);
        ((TextSwitcher) getActivity().findViewById(R.id.switcher)).setText("");
        getActivity().findViewById(R.id.error_msg).setVisibility(View.INVISIBLE);

    }

    public void checkPreferencesChanges() {
        boolean screenOn = PreferenceManager
                .getDefaultSharedPreferences(getActivity()).getBoolean("pref_force_screen_on", false);
        boolean dyslexic = PreferenceManager
                .getDefaultSharedPreferences(getActivity()).getBoolean("pref_opendyslexic", false);

        TextSwitcher switcher = (TextSwitcher) getActivity().findViewById(R.id.switcher);
        View lrcView = getActivity().findViewById(R.id.lrc_view);

        if (switcher != null) {
            switcher.setKeepScreenOn(screenOn);
            if (switcher.getCurrentView() != null)
                ((TextView) switcher.getCurrentView()).setTypeface(
                        LyricsTextFactory.FontCache.get(dyslexic ? "dyslexic" : "light", getActivity())
                );
            View nextView = switcher.getNextView();
            if (nextView != null) {
                ((TextView) nextView).setTypeface(
                        LyricsTextFactory.FontCache.get(dyslexic ? "dyslexic" : "light", getActivity())
                );
            }
        }
        if (lrcView != null)
            lrcView.setKeepScreenOn(screenOn);
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        TypedValue outValue = new TypedValue();
        getActivity().getTheme().resolveAttribute(R.attr.themeName, outValue, false);
        if ("Night".equals(outValue.string) != NightTimeVerifier.check(getActivity()) ||
                "Dark".equals(outValue.string) == sharedPrefs.getString("pref_theme", "0").equals("0")) {
            getActivity().finish();
            Intent intent = new Intent(getActivity(), MainLyricActivity.class);
            intent.setAction("android.intent.action.MAIN");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
    }

    public void showWhyPopup() {
        String title = mLyrics.getTrack();
        String artist = mLyrics.getArtist();
        new AlertDialog.Builder(getActivity()).setTitle(getString(R.string.why_popup_title))
                .setMessage(String.format(String.valueOf(Html.fromHtml(getString(R.string.why_popup_text))),
                        title, artist))
                .show();
    }

    public void enablePullToRefresh(boolean enabled) {
        mRefreshLayout.setEnabled(enabled && !isInEditMode());
    }

    public boolean isInEditMode() {
        return getActivity().findViewById(R.id.edit_lyrics).getVisibility() == View.VISIBLE;
    }

    @Override
    public void onRefresh() {
        fetchCurrentLyrics(true);
    }

    public String getSource() {
        return mLyrics.getSource();
    }

    public boolean isLRC() {
        return mLyrics != null && mLyrics.isLRC();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.save_action:
                if (mLyrics != null && mLyrics.getFlag() == Lyrics.POSITIVE_RESULT)
                    new WriteToDatabaseTask().execute(this, item, this.mLyrics);
                break;
            case R.id.convert_action:
                LrcView lrcView = (LrcView) getActivity().findViewById(R.id.lrc_view);
                if (lrcView != null && lrcView.dictionnary != null) {
                    update(lrcView.getStaticLyrics(), getView(), true);
                }

            case R.id.settings:
                Intent settingsIntent = new Intent(getActivity(), SettingsActivity.class);
                startActivity(settingsIntent);

        }
        return false;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.lyrics, menu);
        MenuItem saveMenuItem = menu.findItem(R.id.save_action);
        if (saveMenuItem != null) {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
            if (mLyrics != null
                    && mLyrics.getFlag() == Lyrics.POSITIVE_RESULT
                    && sharedPref.getBoolean("pref_auto_save", true)) {
                String[] metadata = new String[]{
                        mLyrics.getArtist(),
                        mLyrics.getTrack(),
                        mLyrics.getOriginalArtist(),
                        mLyrics.getOriginalTrack()};
                if (!DatabaseHelper.presenceCheck(((MainLyricActivity) getActivity()).database, metadata)) {
                    lyricsPresentInDB = true;
                    new WriteToDatabaseTask().execute(this, saveMenuItem, mLyrics);
                }
                saveMenuItem.setVisible(false);
            } else {
                saveMenuItem.setIcon(lyricsPresentInDB ? R.drawable.ic_trash : R.drawable.ic_save);
                saveMenuItem.setTitle(lyricsPresentInDB ? R.string.remove_action : R.string.save_action);
            }
        }
        MenuItem resyncMenuItem = menu.findItem(R.id.resync_action);
        MenuItem convertMenuItem = menu.findItem(R.id.convert_action);
        if (mLyrics != null) {
            resyncMenuItem.setVisible(mLyrics.isLRC());
            convertMenuItem.setVisible(mLyrics.isLRC());
        }
    }

    @Override
    public void onDestroy() {
        broadcastReceiver = null;
        super.onDestroy();
    }

    public void updateLRC() {
        if (mLrcThread == null || !mLrcThread.isAlive()) {
            mLrcThread = new Thread(lrcUpdater);
            mLrcThread.start();
        }
    }

    public void startEmpty(boolean startEmpty) {
        this.startEmtpy = startEmpty;
    }

    private Runnable lrcUpdater = new Runnable() {
        @Override
        public void run() {
            boolean ran = false;
            final LrcView lrcView = ((LrcView) LyricsViewFragment.this.getActivity().findViewById(R.id.lrc_view));
            if (lrcView == null || getActivity() == null || lrcView.dictionnary != null)
                return;
            SharedPreferences preferences = getActivity().getSharedPreferences("current_music", Context.MODE_PRIVATE);
            long position = preferences.getLong("position", 0);
            if (position == -1) {
                final Lyrics staticLyrics = lrcView.getStaticLyrics();
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        update(staticLyrics, getView(), true);
                    }
                });
                return;
            } else
                lrcView.changeCurrent(position);

            MusicBroadcastReceiver.forceAutoUpdate(true);
            while (preferences.getString("track", "").equalsIgnoreCase(mLyrics.getOriginalTrack()) &&
                    preferences.getString("artist", "").equalsIgnoreCase(mLyrics.getOriginalArtist()) &&
                    preferences.getBoolean("playing", true)) {
                ran = true;
                position = preferences.getLong("position", 0);
                long startTime = preferences.getLong("startTime", System.currentTimeMillis());
                long distance = System.currentTimeMillis() - startTime;
                if (preferences.getBoolean("playing", true))
                    position += distance;
                final long finalPosition = position;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lrcView.changeCurrent(finalPosition);
                    }
                });
                // String time = String.valueOf((position / 1000) % 60) + " sec";
                // Log.d("geecko", time);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            MusicBroadcastReceiver.forceAutoUpdate(true);
            if (preferences.getBoolean("playing", true) && ran && mLyrics.isLRC())
                fetchCurrentLyrics(false);
        }
    };

    public void updateSearchView(boolean collapsed, String query, boolean focused) {
        this.mExpandedSearchView = !collapsed;
        if (query != null)
            this.mSearchQuery = query;
        this.mSearchFocused = focused;
        getActivity().invalidateOptionsMenu();
    }
}
