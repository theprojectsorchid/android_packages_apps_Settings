/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.homepage;

import android.animation.LayoutTransition;
import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.os.Bundle;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.settings.R;
import com.android.settings.accounts.AvatarViewMixin;
import com.android.settings.Settings;
import com.android.settings.SettingsActivity;
import com.android.settings.SettingsApplication;
import com.android.settings.activityembedding.ActivityEmbeddingRulesController;
import com.android.settings.activityembedding.ActivityEmbeddingUtils;
import com.android.settings.core.CategoryMixin;
import com.android.settings.core.FeatureFlags;
import com.android.settings.homepage.contextualcards.ContextualCardsFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.core.lifecycle.HideNonSystemOverlayMixin;

import com.google.android.material.appbar.CollapsingToolbarLayout;

import java.net.URISyntaxException;
import java.util.Set;

/** Settings homepage activity */
public class SettingsHomepageActivity extends FragmentActivity implements
        CategoryMixin.CategoryHandler {

    private static final String TAG = "SettingsHomepageActivity";

    private static final long HOMEPAGE_LOADING_TIMEOUT_MS = 300;

    private View mHomepageView;
    private CategoryMixin mCategoryMixin;
    private Set<HomepageLoadedListener> mLoadedListeners;
    private boolean mIsEmbeddingActivityEnabled;
    CollapsingToolbarLayout collapsing_toolbar;

    @Override
    public CategoryMixin getCategoryMixin() {
        return mCategoryMixin;
    }

    /** Returns the main content fragment */
    public TopLevelSettings getMainFragment() {
        return mMainFragment;
    }

    @Override
    public CategoryMixin getCategoryMixin() {
        return mCategoryMixin;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_homepage_container);
        mIsEmbeddingActivityEnabled = ActivityEmbeddingUtils.isEmbeddingActivityEnabled(this);

        updateHomepageBackground();
        mLoadedListeners = new ArraySet<>();

        final View root = findViewById(R.id.settings_homepage_container);
	LinearLayout commonCon = root.findViewById(R.id.common_con);
        final Toolbar toolbar = root.findViewById(R.id.search_action_bar);
	collapsing_toolbar =  root.findViewById(R.id.collapsing_toolbar);

        FeatureFactory.getFactory(this).getSearchFeatureProvider()
                .initSearchToolbar(this /* activity */, toolbar, SettingsEnums.SETTINGS_HOMEPAGE);

        getLifecycle().addObserver(new HideNonSystemOverlayMixin(this));
        collapsing_toolbar.setTitle("Settings");
        mCategoryMixin = new CategoryMixin(this);
        getLifecycle().addObserver(mCategoryMixin);

	final String highlightMenuKey = getHighlightMenuKey();
        mMainFragment = showFragment(() -> {
            final TopLevelSettings fragment = new TopLevelSettings();
            fragment.getArguments().putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY,
                    highlightMenuKey);
            return fragment;
        }, R.id.main_content);

        ((FrameLayout) findViewById(R.id.main_content))
                .getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);
    }

    @Override
    protected void onStart() {
        ((SettingsApplication) getApplication()).setHomeActivity(this);
        super.onStart();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // When it's large screen 2-pane and Settings app is in the background, receiving an Intent
        // will not recreate this activity. Update the intent for this case.
        setIntent(intent);
        reloadHighlightMenuKey();
        if (isFinishing()) {
            return;
        }
        // Launch the intent from deep link for large screen devices.
        launchDeepLinkIntentToRight();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private void updateHomepageBackground() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }

        final Window window = getWindow();
        final int color = ActivityEmbeddingUtils.isTwoPaneResolution(this)
                ? Utils.getColorAttrDefaultColor(this, com.android.internal.R.attr.colorSurface)
                : Utils.getColorAttrDefaultColor(this, android.R.attr.colorBackground);

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // Update status bar color
        window.setStatusBarColor(color);
        // Update content background.
        findViewById(R.id.settings_homepage_container).setBackgroundColor(color);
    }

    private <T extends Fragment> T showFragment(FragmentBuilder<T> fragmentBuilder, int id) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        final Fragment showFragment = fragmentManager.findFragmentById(id);

        if (showFragment == null) {
            fragmentTransaction.add(id, fragment);
        } else {
            fragmentTransaction.show(showFragment);
        }
        fragmentTransaction.commit();
    }

    private void initHomepageContainer() {
        final View view = findViewById(R.id.homepage_container);
        // Prevent inner RecyclerView gets focus and invokes scrolling.
        view.setFocusableInTouchMode(true);
        view.requestFocus();
    }

}
