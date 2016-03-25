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

package com.evandroid.musica;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.AppBarLayout;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.evandroid.musica.broadcastReceiver.MusicBroadcastReceiver;
import com.evandroid.musica.fragment.LocalLyricsFragment;
import com.evandroid.musica.fragment.LyricsViewFragment;
import com.evandroid.musica.lyrics.Lyrics;
import com.evandroid.musica.tasks.DBContentLister;
import com.evandroid.musica.tasks.Id3Writer;
import com.evandroid.musica.tasks.IdDecoder;
import com.evandroid.musica.utils.DatabaseHelper;
import com.evandroid.musica.utils.NightTimeVerifier;
import com.evandroid.musica.utils.RefreshButtonBehavior;
import com.evandroid.musica.view.LrcView;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MainLyricActivity extends AppCompatActivity implements AppBarLayout.OnOffsetChangedListener {

    private static final String LYRICS_FRAGMENT_TAG = "LyricsViewFragment";
    private static final String SETTINGS_FRAGMENT = "SettingFragment";
    private static final String LOCAL_LYRICS_FRAGMENT_TAG = "LocalLyricsFragment";
    public static final String SEARCH_FRAGMENT_TAG = "SearchFragment";

    public boolean focusOnFragment = true;
    public SQLiteDatabase database;
    public ActionBarDrawerToggle mDrawerToggle;
    private Fragment displayedFragment;
    private MusicBroadcastReceiver receiver;
    private boolean receiverRegistered = false;
    private boolean destroyed = false;

    private static void prepareAnimations(Fragment nextFragment) {
        if (nextFragment != null) {
            Class fragmentClass = ((Object) nextFragment).getClass();
            try {
                fragmentClass.getDeclaredField("showTransitionAnim").setBoolean(nextFragment, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        int[] themes = new int[]{R.style.Theme_QuickLyric, R.style.Theme_QuickLyric_Dark};
        int themeNum = Integer.valueOf(sharedPref.getString("pref_theme", "0"));
        boolean nightMode = sharedPref.getBoolean("pref_night_mode", false);
        if (nightMode && NightTimeVerifier.check(this))
            setTheme(R.style.Theme_QuickLyric_Night);
        else
            setTheme(themes[themeNum]);
        setStatusBarColor(null);
        setNavBarColor(null);
        final FragmentManager fragmentManager = getFragmentManager();
        setContentView(R.layout.nav_drawer_activity);

        database = new DatabaseHelper(getApplicationContext()).getReadableDatabase();
        DatabaseHelper.setDatabase(database);
        if (database != null && DatabaseHelper.getColumnsCount(database) <= 6)
            DatabaseHelper.addMissingColumns(database);

        Intent intent = getIntent();
        String extra = intent.getStringExtra(Intent.EXTRA_TEXT);
        Lyrics receivedLyrics = getBeamedLyrics(intent);
        if (receivedLyrics != null) {
            updateLyricsFragment(0, 0, false, receivedLyrics);
        } else {
            String s = intent.getAction();
            if ("com.evandroid.music.getLyrics".equals(s)) {
                String[] metadata = intent.getStringArrayExtra("TAGS");
                String artist = metadata[0];
                String track = metadata[1];
                updateLyricsFragment(0, artist, track);
            /*} else if (s.equals("android.intent.action.SEND")) {
                new IdDecoder(this, init(fragmentManager, true)).execute(getIdUrl(extra));
            */} else
                init(fragmentManager, false);
        }
    }

    private LyricsViewFragment init(FragmentManager fragmentManager, boolean startEmpty) {
        LyricsViewFragment lyricsViewFragment = (LyricsViewFragment) fragmentManager.findFragmentByTag(LYRICS_FRAGMENT_TAG);
        if (lyricsViewFragment == null || lyricsViewFragment.isDetached())
            lyricsViewFragment = new LyricsViewFragment();
        lyricsViewFragment.startEmpty(startEmpty);
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        if (!lyricsViewFragment.isAdded()) {
            fragmentTransaction.add(R.id.main_fragment_container, lyricsViewFragment, LYRICS_FRAGMENT_TAG);
        }

        Fragment[] activeFragments = getActiveFragments();
        displayedFragment = getDisplayedFragment(activeFragments);

        for (Fragment fragment : activeFragments)
            if (fragment != null) {
                if (fragment != displayedFragment && !fragment.isHidden()) {
                    fragmentTransaction.hide(fragment);
                    fragment.onHiddenChanged(true);
                } else if (fragment == displayedFragment)
                    fragmentTransaction.show(fragment);
            }
        fragmentTransaction.commit();
        return lyricsViewFragment;
    }

    public Fragment getDisplayedFragment(Fragment[] fragments) {
        for (Fragment fragment : fragments) {
            if (fragment == null)
                continue;
            if ((fragment instanceof LyricsViewFragment && ((LyricsViewFragment) fragment).isActiveFragment)
                    || (fragment instanceof LocalLyricsFragment && ((LocalLyricsFragment) fragment).isActiveFragment))
                return fragment;
        }
        return fragments[0];
    }

    public Fragment[] getActiveFragments() {
        FragmentManager fragmentManager = this.getFragmentManager();
        Fragment[] fragments = new Fragment[4];
        fragments[0] = fragmentManager.findFragmentByTag(LYRICS_FRAGMENT_TAG);
        fragments[1] = fragmentManager.findFragmentByTag(SEARCH_FRAGMENT_TAG);
        fragments[2] = fragmentManager.findFragmentByTag(SETTINGS_FRAGMENT);
        fragments[3] = fragmentManager.findFragmentByTag(LOCAL_LYRICS_FRAGMENT_TAG);
        return fragments;
    }

    @SuppressWarnings("ConstantConditions")
    @NonNull
    public ActionBar getSupportActionBar() {
        return super.getSupportActionBar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.activityResumed();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        LyricsViewFragment lyricsViewFragment = (LyricsViewFragment) getFragmentManager()
                .findFragmentByTag(LYRICS_FRAGMENT_TAG);
        if (lyricsViewFragment != null) {
            if ((sharedPref.getBoolean("pref_auto_refresh", false) || lyricsViewFragment.isLRC()) &&
                    (getIntent() == null || getIntent().getAction() == null ||
                            getIntent().getAction().equals(""))) {
                /*// fixme executes twice?
                if (!"Storage".equals(lyricsViewFragment.getSource())
                        && !lyricsViewFragment.searchResultLock)
                    lyricsViewFragment.fetchCurrentLyrics(false);
                lyricsViewFragment.checkPreferencesChanges();*/
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        String extra = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (action != null) {
            switch (action) {
                case NfcAdapter.ACTION_NDEF_DISCOVERED:
                    Lyrics receivedLyrics = getBeamedLyrics(intent);
                    if (receivedLyrics != null)
                        updateLyricsFragment(0, 0, false, receivedLyrics);
                    break;
                case "android.intent.action.SEND":
                    LyricsViewFragment lyricsViewFragment = (LyricsViewFragment) getFragmentManager()
                            .findFragmentByTag(LYRICS_FRAGMENT_TAG);
                    new IdDecoder(this, lyricsViewFragment).execute(getIdUrl(extra));
                    break;
                case "com.evandroid.music.getLyrics":
                    String[] metadata = intent.getStringArrayExtra("TAGS");
                    if (metadata != null) {
                        String artist = metadata[0];
                        String track = metadata[1];
                        LyricsViewFragment lyricsFragment = (LyricsViewFragment) getFragmentManager()
                                .findFragmentByTag(LYRICS_FRAGMENT_TAG);
                        lyricsFragment.fetchLyrics(artist, track);
                    }
                    break;
                case "com.evandroid.music.updateDBList":
                    updateDBList();
                    break;
            }
        }
    }

    @TargetApi(14)
    private Lyrics getBeamedLyrics(Intent intent) {
        Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        // only one message sent during the beam
        if (rawMsgs != null && rawMsgs.length > 0) {
            NdefMessage msg = (NdefMessage) rawMsgs[0];
            // record 0 contains the MIME type, record 1 is the AAR, if present
            NdefRecord[] records = msg.getRecords();
            if (records.length > 0) {
                try {
                    return Lyrics.fromBytes(records[0].getPayload());
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private void updateDBList() {
        LocalLyricsFragment localLyricsFragment =
                (LocalLyricsFragment) getFragmentManager().findFragmentByTag(LOCAL_LYRICS_FRAGMENT_TAG);
        if (localLyricsFragment != null && localLyricsFragment.isActiveFragment)
            new DBContentLister(localLyricsFragment).execute();
    }

    private String getIdUrl(String extra) {
        final Pattern urlPattern = Pattern.compile(
                "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                        + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                        + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        Matcher matcher = urlPattern.matcher(extra);
        if (matcher.find())
            return extra.substring(matcher.start(), matcher.end());
        else
            return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        invalidateOptionsMenu();
        LyricsViewFragment lyricsViewFragment =
                (LyricsViewFragment) getFragmentManager().findFragmentByTag(LYRICS_FRAGMENT_TAG);
        if (requestCode == 77) {
            lyricsViewFragment.checkPreferencesChanges();
        } else if (resultCode == RESULT_OK && requestCode == 55) {
            Lyrics results = (Lyrics) data.getSerializableExtra("lyrics");
            updateLyricsFragment(R.animator.slide_out_end, results.getArtist(), results.getTrack(), results.getURL());
            lyricsViewFragment.searchResultLock = true;
        }
        lyricsViewFragment.updateSearchView(true, null, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.activityPaused();
        if (receiver != null && receiverRegistered) {
            unregisterReceiver(receiver);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        this.destroyed = true;
        if (database != null && database.isOpen())
            database.close();
        super.onDestroy();
    }

    public boolean hasBeenDestroyed() {
        return this.destroyed;
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (!RefreshButtonBehavior.visible)
            findViewById(R.id.refresh_fab).setVisibility(View.GONE);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        if (mDrawerToggle != null)
            mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mDrawerToggle != null)
            mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int i) {
        LyricsViewFragment lyricsViewFragment =
                ((LyricsViewFragment) getFragmentManager().findFragmentByTag(LYRICS_FRAGMENT_TAG));
        if (lyricsViewFragment != null)
            lyricsViewFragment.enablePullToRefresh(i == 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case LocalLyricsFragment.REQUEST_CODE:
                break;
            case Id3Writer.REQUEST_CODE:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.id3_write_permission_error, Toast.LENGTH_LONG).show();
                } else {
                    String message = getString(R.string.id3_write_error) + " " + getString(R.string.permission_denied);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        }
    }

    @TargetApi(21)
    public void setStatusBarColor(Integer color) {
        if (Build.VERSION.SDK_INT >= 20) {
            if (color == null) {
                TypedValue typedValue = new TypedValue();
                Resources.Theme theme = getTheme();
                theme.resolveAttribute(android.R.attr.statusBarColor, typedValue, true);
                color = typedValue.data;
            }
            getWindow().setStatusBarColor(color);
        }
    }

    @TargetApi(21)
    public void setNavBarColor(Integer color) {
        if (Build.VERSION.SDK_INT >= 20) {
            if (color == null) {
                TypedValue typedValue = new TypedValue();
                Resources.Theme theme = getTheme();
                theme.resolveAttribute(android.R.attr.navigationBarColor, typedValue, true);
                color = typedValue.data;
            }
            getWindow().setNavigationBarColor(color);
        }
    }

    public void updateLyricsFragment(int outAnim, String... params) { // Should only be called from SearchFragment or IdDecoder
        String artist = params[0];
        String song = params[1];
        String url = null;
        if (params.length > 2)
            url = params[2];
        LyricsViewFragment lyricsViewFragment = (LyricsViewFragment)
                getFragmentManager().findFragmentByTag(LYRICS_FRAGMENT_TAG);
        if (lyricsViewFragment != null)
            lyricsViewFragment.fetchLyrics(artist, song, url);
        else {
            Lyrics lyrics = new Lyrics(Lyrics.SEARCH_ITEM);
            lyrics.setArtist(artist);
            lyrics.setTitle(song);
            lyrics.setURL(url);
            Bundle lyricsBundle = new Bundle();
            try {
                if (artist != null && song != null)
                    lyricsBundle.putByteArray("lyrics", lyrics.toBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
            lyricsViewFragment = new LyricsViewFragment();
            lyricsViewFragment.setArguments(lyricsBundle);

            FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
            Fragment activeFragment = getDisplayedFragment(getActiveFragments());
            if (activeFragment != null) {
                prepareAnimations(activeFragment);
                fragmentTransaction.hide(activeFragment);
            }
            fragmentTransaction.add(R.id.main_fragment_container, lyricsViewFragment, LYRICS_FRAGMENT_TAG);
            lyricsViewFragment.isActiveFragment = true;
            fragmentTransaction.commit();
        }
    }

    public void updateLyricsFragment(int outAnim, int inAnim, boolean transition, Lyrics lyrics) {
        LyricsViewFragment lyricsViewFragment = (LyricsViewFragment) getFragmentManager().findFragmentByTag(LYRICS_FRAGMENT_TAG);
        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.setCustomAnimations(inAnim, outAnim, inAnim, outAnim);
        Fragment activeFragment = getDisplayedFragment(getActiveFragments());
        if (lyricsViewFragment != null && lyricsViewFragment.getView() != null) {
            SharedPreferences preferences = getSharedPreferences("current_music", Context.MODE_PRIVATE);
            String artist = preferences.getString("artist", null);
            String track = preferences.getString("track", null);
            if (lyrics.isLRC() && !(lyrics.getOriginalArtist().equals(artist) && lyrics.getOriginalTrack().equals(track))) {
                LrcView parser = new LrcView(this, null);
                parser.setOriginalLyrics(lyrics);
                parser.setSourceLrc(lyrics.getText());
                lyrics = parser.getStaticLyrics();
            }
            lyricsViewFragment.update(lyrics, lyricsViewFragment.getView(), true);
            if (transition) {
                fragmentTransaction.hide(activeFragment).show(lyricsViewFragment);
                prepareAnimations(activeFragment);
                prepareAnimations(lyricsViewFragment);
            }
        } else {
            Bundle lyricsBundle = new Bundle();
            try {
                lyricsBundle.putByteArray("lyrics", lyrics.toBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
            lyricsViewFragment = new LyricsViewFragment();
            lyricsViewFragment.setArguments(lyricsBundle);
            if (!(activeFragment instanceof LyricsViewFragment) && activeFragment != null)
                fragmentTransaction.hide(activeFragment).add(R.id.main_fragment_container, lyricsViewFragment, LYRICS_FRAGMENT_TAG);
            else
                fragmentTransaction.replace(R.id.main_fragment_container, lyricsViewFragment, LYRICS_FRAGMENT_TAG);
        }
        fragmentTransaction.commitAllowingStateLoss();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle != null)
            return (mDrawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item));
        else
            return super.onOptionsItemSelected(item);
    }

    public void whyPopUp(View view) {
        LyricsViewFragment lyricsViewFragment = ((LyricsViewFragment) getFragmentManager().findFragmentByTag(LYRICS_FRAGMENT_TAG));
        if (lyricsViewFragment != null && !lyricsViewFragment.isDetached())
            lyricsViewFragment.showWhyPopup();
    }

    @SuppressWarnings("unused")
    public void id3PopUp(View view) {
        Toast.makeText(this, R.string.ignore_id3_toast, Toast.LENGTH_LONG).show();
    }

    @SuppressLint("InlinedApi")
    public void resync(MenuItem item) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.requestAudioFocus(null,
                // Use the music stream.
                AudioManager.STREAM_SYSTEM,
                // Request permanent focus.
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        am.abandonAudioFocus(null);
    }

}
