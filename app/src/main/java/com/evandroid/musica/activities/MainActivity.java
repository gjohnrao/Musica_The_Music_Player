/*
 * Copyright (C) 2015 Naman Dwivedi
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package com.evandroid.musica.activities;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.StyleRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import com.afollestad.appthemeengine.customizers.ATEActivityThemeCustomizer;
import com.evandroid.musica.R;
import com.evandroid.musica.fragments.AlbumDetailFragment;
import com.evandroid.musica.fragments.ArtistDetailFragment;
import com.evandroid.musica.fragments.MainFragment;
import com.evandroid.musica.fragments.PlaylistFragment;
import com.evandroid.musica.fragments.QueueFragment;
import com.evandroid.musica.permissions.Permission;
import com.evandroid.musica.permissions.PermissionCallback;
import com.evandroid.musica.slidinguppanel.SlidingUpPanelLayout;
import com.evandroid.musica.utils.Constants;
import com.evandroid.musica.utils.Helpers;
import com.evandroid.musica.utils.NavigationUtils;
import com.evandroid.musica.utils.TimberUtils;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;
import com.special.ResideMenu.ResideMenu;
import com.special.ResideMenu.ResideMenuItem;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BaseActivity implements ATEActivityThemeCustomizer, View.OnClickListener {

    private static MainActivity sMainActivity;
    SlidingUpPanelLayout panelLayout;
    String action;
    Map<String, Runnable> navigationMap = new HashMap<>();

    Runnable navigateLibrary = new Runnable() {
        public void run() {
            Fragment fragment = new MainFragment();
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, fragment).commitAllowingStateLoss();
        }
    };

    final PermissionCallback permissionReadStorageCallback = new PermissionCallback() {
        @Override
        public void permissionGranted() {
            loadEverything();
        }
        @Override
        public void permissionRefused() {
            finish();
        }
    };

    private void changeFragment(Fragment targetFragment){
        resideMenu.clearIgnoredViewList();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, targetFragment, "fragment")
                .setTransitionStyle(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
    }

    Runnable navigateAlbum = new Runnable() {
        public void run() {
            long albumID = getIntent().getExtras().getLong(Constants.ALBUM_ID);
            Fragment fragment = AlbumDetailFragment.newInstance(albumID, false, null);
            FragmentManager fragmentManager = getSupportFragmentManager();
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment).commit();
        }
    };

    Runnable navigateArtist = new Runnable() {
        public void run() {
            long artistID = getIntent().getExtras().getLong(Constants.ARTIST_ID);
            Fragment fragment = ArtistDetailFragment.newInstance(artistID, false, null);
            FragmentManager fragmentManager = getSupportFragmentManager();
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment).commit();
        }
    };

//    private DrawerLayout mDrawerLayout;
    private boolean isDarkTheme;

    public static MainActivity getInstance() {
        return sMainActivity;
    }
    public static ResideMenu resideMenu;

    private ResideMenuItem itemLibrary;
    private ResideMenuItem itemPlaylists;
    private ResideMenuItem itemQueue;
    private ResideMenuItem itemNowPlaying;
    private ResideMenuItem itemSettings;
    private ResideMenuItem itemAbout;
    private ResideMenuItem itemHelp;



    @Override
    public void onCreate(Bundle savedInstanceState) {

        sMainActivity = this;
        action = getIntent().getAction();

        isDarkTheme = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("dark_theme", false);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        navigationMap.put(Constants.NAVIGATE_LIBRARY, navigateLibrary);
        navigationMap.put(Constants.NAVIGATE_ALBUM, navigateAlbum);
        navigationMap.put(Constants.NAVIGATE_ARTIST, navigateArtist);

//        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        panelLayout = (SlidingUpPanelLayout) findViewById(R.id.sliding_layout);

        setPanelSlideListeners(panelLayout);


        if (TimberUtils.isMarshmallow()) {
            checkPermissionAndThenLoad();
        } else {
            loadEverything();
        }
        setUpMenu();
        addBackStackListener();
    }


    private void setUpMenu() {
        // attach to current activity;
        resideMenu = new ResideMenu(this);
//        resideMenu.setUse3D(true);
        resideMenu.setBackground(R.drawable.bg);
        resideMenu.attachToActivity(this);
//        resideMenu.setMenuListener(menuListener);
        //valid scale factor is between 0.0f and 1.0f. left menu' width is 150dp.
//        resideMenu.setScaleValue(0.6f);

        // create menu items;


        itemLibrary    = new ResideMenuItem(this, R.drawable.library_music_white,     "Library");
        itemPlaylists  = new ResideMenuItem(this, R.drawable.playlist_play_white,  "Playlists");
        itemQueue = new ResideMenuItem(this, R.drawable.music_note_white, "Playing Queue");
        itemNowPlaying = new ResideMenuItem(this, R.drawable.bookmark_music_white, "Now Playing");
        itemSettings = new ResideMenuItem(this, R.drawable.settings_white, "Settings");
        itemAbout = new ResideMenuItem(this, R.drawable.help_circle_white, "About");
        itemHelp = new ResideMenuItem(this, R.drawable.information_white, "Feedback");

        itemLibrary.setOnClickListener(this);
        itemPlaylists.setOnClickListener(this);
        itemQueue.setOnClickListener(this);
        itemNowPlaying.setOnClickListener(this);
        itemSettings.setOnClickListener(this);
        itemAbout.setOnClickListener(this);
        itemHelp.setOnClickListener(this);

        resideMenu.addMenuItem(itemLibrary, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemPlaylists, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemQueue, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemNowPlaying, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemSettings, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemAbout, ResideMenu.DIRECTION_LEFT);
        resideMenu.addMenuItem(itemHelp, ResideMenu.DIRECTION_LEFT);

        // You can disable a direction by setting ->
         resideMenu.setSwipeDirectionDisable(ResideMenu.DIRECTION_RIGHT);
         resideMenu.setSwipeDirectionDisable(ResideMenu.DIRECTION_LEFT);

    }



    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return resideMenu.dispatchTouchEvent(ev);
    }

    private void loadEverything() {
        Runnable navigation = navigationMap.get(action);
        if (navigation != null) {
            navigation.run();
        } else {
            navigateLibrary.run();
        }
        new initQuickControls().execute("");
    }

    private void checkPermissionAndThenLoad() {
        //check for permission
        if (Permission.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            loadEverything();
        } else {
            if (Permission.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                Snackbar.make(panelLayout, "Musica will need to read external storage to display songs on your device.",
                        Snackbar.LENGTH_INDEFINITE)
                        .setAction("OK", new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                Permission.askForPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE, permissionReadStorageCallback);
                            }
                        }).show();
            } else {
                Permission.askForPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE, permissionReadStorageCallback);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                if (isNavigatingMain()) {
//                    mDrawerLayout.openDrawer(GravityCompat.START);
                    resideMenu.openMenu(ResideMenu.DIRECTION_LEFT); // or ResideMenu.DIRECTION_RIGHT
//                    resideMenu.closeMenu();
                } else super.onBackPressed();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }



    @Override
    public void onBackPressed() {

        if (panelLayout.isPanelExpanded())
            panelLayout.collapsePanel();
        else {
            super.onBackPressed();
        }



    }

    @Override
    public void onMetaChanged() {
        super.onMetaChanged();
        /*setDetailsToHeader();*/
    }

    @Override
    public void onResume() {
        super.onResume();
        sMainActivity = this;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        Permission.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private boolean isNavigatingMain() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        return (currentFragment instanceof MainFragment || currentFragment instanceof QueueFragment
                || currentFragment instanceof PlaylistFragment);
    }

    private void addBackStackListener() {
        getSupportFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                getSupportFragmentManager().findFragmentById(R.id.fragment_container).onResume();
            }
        });
    }


    @StyleRes
    @Override
    public int getActivityTheme() {
        return isDarkTheme ? R.style.AppThemeNormalDark : R.style.AppThemeNormalLight;
    }

    @Override
    public void onClick(View view) {

        if (view == itemLibrary){
            changeFragment(new MainFragment());
        }else if (view == itemPlaylists){
            changeFragment(new  PlaylistFragment());
        }else if (view == itemQueue){
            changeFragment(new  QueueFragment());
        }

        else if (view == itemNowPlaying){
          
                NavigationUtils.navigateToNowplaying(MainActivity.this, false);

        
        }

        else if (view == itemSettings){
           
                NavigationUtils.navigateToSettings(MainActivity.this);
        
        }

        else if (view == itemAbout){
            resideMenu.closeMenu();
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Helpers.showAbout(MainActivity.this);
                }
            }, 350);
        }else if (view == itemHelp){
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri data = Uri.parse("mailto:evan7droid@gmail.com");
            intent.setData(data);
            startActivity(intent);
        }

        resideMenu.closeMenu();

    }
}


