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

import static android.provider.Settings.ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY;
import static android.provider.Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY;
import static android.provider.Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI;

import android.animation.LayoutTransition;
import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.FeatureFlagUtils;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.window.embedding.SplitRule;

import com.android.internal.util.UserIcons;

import com.android.settings.R;
import android.provider.Settings;
import com.android.settings.SettingsActivity;
import com.android.settings.SettingsApplication;
import com.android.settings.accounts.AvatarViewMixin;
import com.android.settings.activityembedding.ActivityEmbeddingRulesController;
import com.android.settings.activityembedding.ActivityEmbeddingUtils;
import com.android.settings.core.CategoryMixin;
import com.android.settings.core.FeatureFlags;
import com.android.settings.homepage.contextualcards.ContextualCardsFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.Utils;
import com.android.settingslib.core.lifecycle.HideNonSystemOverlayMixin;

import com.android.settingslib.drawable.CircleFramedDrawable;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import java.net.URISyntaxException;
import java.util.Set;
import java.util.*;
import java.lang.*;

import com.android.settingslib.drawable.CircleFramedDrawable;

/** Settings homepage activity */
public class SettingsHomepageActivity extends FragmentActivity implements
        CategoryMixin.CategoryHandler {

    private static final String TAG = "SettingsHomepageActivity";

    // Additional extra of Settings#ACTION_SETTINGS_LARGE_SCREEN_DEEP_LINK.
    // Put true value to the intent when startActivity for a deep link intent from this Activity.
    public static final String EXTRA_IS_FROM_SETTINGS_HOMEPAGE = "is_from_settings_homepage";

    // Additional extra of Settings#ACTION_SETTINGS_LARGE_SCREEN_DEEP_LINK.
    // Set & get Uri of the Intent separately to prevent failure of Intent#ParseUri.
    public static final String EXTRA_SETTINGS_LARGE_SCREEN_DEEP_LINK_INTENT_DATA =
            "settings_large_screen_deep_link_intent_data";

    static final int DEFAULT_HIGHLIGHT_MENU_KEY = R.string.menu_key_network;
    private static final long HOMEPAGE_LOADING_TIMEOUT_MS = 300;

    private TopLevelSettings mMainFragment;
    private View mHomepageView;
    private View mSuggestionView;
    private View mTwoPaneSuggestionView;
    private boolean mIsTwoPaneLastTime;
    private CategoryMixin mCategoryMixin;
    private Set<HomepageLoadedListener> mLoadedListeners;
    private boolean mIsEmbeddingActivityEnabled;
    private boolean mIsTwoPane;
    // A regular layout shows icons on homepage, whereas a simplified layout doesn't.
    private boolean mIsRegularLayout = true;
    CollapsingToolbarLayout collapsing_toolbar;

    /** A listener receiving homepage loaded events. */
    public interface HomepageLoadedListener {
        /** Called when the homepage is loaded. */
        void onHomepageLoaded();
    }

    private interface FragmentBuilder<T extends Fragment>  {
        T build();
    }

    /**
     * Try to add a {@link HomepageLoadedListener}. If homepage is already loaded, the listener
     * will not be notified.
     *
     * @return Whether the listener is added.
     */
    public boolean addHomepageLoadedListener(HomepageLoadedListener listener) {
        if (mHomepageView == null) {
            return false;
        } else {
            if (!mLoadedListeners.contains(listener)) {
                mLoadedListeners.add(listener);
            }
            return true;
        }
    }

    /**
     * Shows the homepage and shows/hides the suggestion together. Only allows to be executed once
     * to avoid the flicker caused by the suggestion suddenly appearing/disappearing.
     */
    public void showHomepageWithSuggestion(boolean showSuggestion) {
        if (mHomepageView == null) {
            return;
        }
        Log.i(TAG, "showHomepageWithSuggestion: " + showSuggestion);
        final View homepageView = mHomepageView;
        mSuggestionView.setVisibility(showSuggestion ? View.VISIBLE : View.GONE);
        mTwoPaneSuggestionView.setVisibility(showSuggestion ? View.VISIBLE : View.GONE);
        mHomepageView = null;

        mLoadedListeners.forEach(listener -> listener.onHomepageLoaded());
        mLoadedListeners.clear();
        homepageView.setVisibility(View.VISIBLE);
    }

    /** Returns the main content fragment */
    public TopLevelSettings getMainFragment() {
        return mMainFragment;
    }

    @Override
    public CategoryMixin getCategoryMixin() {
        return mCategoryMixin;
    }

    Context context;
    ImageView avatarView;
    UserManager mUserManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getApplicationContext();

        final boolean useStockLayout = getuseStockLayout();
        final boolean isUserCardDisabled = getUserCardState();
        final boolean messagesEnabled = getMessagesSettings();

        setContentView(useStockLayout  ? R.layout.settings_homepage_container_stock
                                       : R.layout.settings_homepage_container);


        updateHomepageBackground();
        mLoadedListeners = new ArraySet<>();
        final String highlightMenuKey = getHighlightMenuKey();
        if (useStockLayout) {
        
        mIsEmbeddingActivityEnabled = ActivityEmbeddingUtils.isEmbeddingActivityEnabled(this);
        if (mIsEmbeddingActivityEnabled) {
            final UserManager um = getSystemService(UserManager.class);
            final UserInfo userInfo = um.getUserInfo(getUser().getIdentifier());
            if (userInfo.isManagedProfile()) {
                final Intent intent = new Intent(getIntent())
                        .setClass(this, DeepLinkHomepageActivityInternal.class)
                        .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                        .putExtra(EXTRA_USER_HANDLE, getUser());
                intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAsUser(intent, um.getPrimaryUser().getUserHandle());
                finish();
                return;
            }
        }

        avatarView = findViewById(R.id.account_avatar);

        if (avatarView != null && useStockLayout && isUserCardDisabled) {
          avatarView.setImageDrawable(getCircularUserIcon(getApplicationContext()));
          avatarView.setVisibility(View.VISIBLE);
          avatarView.setOnClickListener(new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                  Intent intent = new Intent(Intent.ACTION_MAIN);
                  intent.setComponent(new ComponentName("com.android.settings","com.android.settings.Settings$UserSettingsActivity"));
                  startActivity(intent);
              }
          });
        }

	if (messagesEnabled) {
	final TextView textView = findViewById(R.id.homepage_title);
        switch (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            case 5: case 6: case 7: case 8: case 9: case 10:
       	// Generate random welcome massage as title header
        	String[] morningMsg = getResources().getStringArray(R.array.dashboard_morning);
        	Random genMorningMsg = new Random();
        	int morning = genMorningMsg.nextInt(morningMsg.length-1);
        	textView.setText(morningMsg[morning]);
                break;

            case 18: case 19: case 20: 
        	String[] msgearlyNight = getResources().getStringArray(R.array.dashboard_early_night);
        	Random genmsgeNight = new Random();
        	int eNight = genmsgeNight.nextInt(msgearlyNight.length-1);
        	textView.setText(msgearlyNight[eNight]);
                break;
                
            case 21: case 22: case 23: case 0: 
        	String[] msgNight = getResources().getStringArray(R.array.dashboard_night);
        	Random genmsgNight = new Random();
        	int night = genmsgNight.nextInt(msgNight.length-1);
        	textView.setText(msgNight[night]);
                break;

             case 16: case 17:
        	String[] msgNoon = getResources().getStringArray(R.array.dashboard_noon);
        	Random genmsgNoon = new Random();
        	int noon = genmsgNoon.nextInt(msgNoon.length-1);
        	textView.setText(msgNoon[noon]);
                break;

            case 1: case 2: case 3: case 4:
        	String[] msgMN = getResources().getStringArray(R.array.dashboard_midnight);
        	Random genmsgMN = new Random();
        	int mn = genmsgMN.nextInt(msgMN.length-1);
        	textView.setText(msgMN[mn]);
                break;
                
            case 11: case 12: case 13: case 14: case 15:
        	String[] msgRD = getResources().getStringArray(R.array.dashboard_random);
        	Random genmsgRD = new Random();
        	int randomm = genmsgRD.nextInt(msgRD.length-1);
        	textView.setText(msgRD[randomm]);
                break;

            default:
                break;
           }
        } else {
           final TextView textView = findViewById(R.id.homepage_title);
           textView.setText(getResources().getString(R.string.top_level_settings_title));
        }

        setupEdgeToEdge();
        mSplitController = SplitController.getInstance();
        mIsTwoPane = mSplitController.isActivityEmbedded(this);

        updateAppBarMinHeight();
        initHomepageContainer();
        updateHomepageAppBar();
        initSearchBarView();
        // Only allow features on high ram devices.
        if (!getSystemService(ActivityManager.class).isLowRamDevice()) {
            final boolean scrollNeeded = mIsEmbeddingActivityEnabled
                    && !TextUtils.equals(getString(DEFAULT_HIGHLIGHT_MENU_KEY), highlightMenuKey);
            showSuggestionFragment(scrollNeeded);
            if (FeatureFlagUtils.isEnabled(this, FeatureFlags.CONTEXTUAL_HOME)) {
                showFragment(() -> new ContextualCardsFragment(), R.id.contextual_cards_content);
            }
        }
        } else {

        final View root = findViewById(R.id.settings_homepage_container);
	LinearLayout commonCon = root.findViewById(R.id.common_con);
        final Toolbar toolbar = root.findViewById(R.id.search_action_bar);

        FeatureFactory.getFactory(this).getSearchFeatureProvider()
                .initSearchToolbar(this /* activity */, toolbar, SettingsEnums.SETTINGS_HOMEPAGE);

	if (messagesEnabled) {
	collapsing_toolbar =  root.findViewById(R.id.collapsing_toolbar);
        switch (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            case 5: case 6: case 7: case 8: case 9: case 10:
       	// Generate random welcome massage as title header
        	String[] morningMsg = getResources().getStringArray(R.array.dashboard_morning);
        	Random genMorningMsg = new Random();
        	int morning = genMorningMsg.nextInt(morningMsg.length-1);
        	collapsing_toolbar.setTitle(morningMsg[morning]);
                break;

            case 18: case 19: case 20: 
        	String[] msgearlyNight = getResources().getStringArray(R.array.dashboard_early_night);
        	Random genmsgeNight = new Random();
        	int eNight = genmsgeNight.nextInt(msgearlyNight.length-1);
        	collapsing_toolbar.setTitle(msgearlyNight[eNight]);
                break;
                
            case 21: case 22: case 23: case 0: 
        	String[] msgNight = getResources().getStringArray(R.array.dashboard_night);
        	Random genmsgNight = new Random();
        	int night = genmsgNight.nextInt(msgNight.length-1);
        	collapsing_toolbar.setTitle(msgNight[night]);
                break;

             case 16: case 17:
        	String[] msgNoon = getResources().getStringArray(R.array.dashboard_noon);
        	Random genmsgNoon = new Random();
        	int noon = genmsgNoon.nextInt(msgNoon.length-1);
        	collapsing_toolbar.setTitle(msgNoon[noon]);
                break;

            case 1: case 2: case 3: case 4:
        	String[] msgMN = getResources().getStringArray(R.array.dashboard_midnight);
        	Random genmsgMN = new Random();
        	int mn = genmsgMN.nextInt(msgMN.length-1);
        	collapsing_toolbar.setTitle(msgMN[mn]);
                break;
                
            case 11: case 12: case 13: case 14: case 15:
        	String[] msgRD = getResources().getStringArray(R.array.dashboard_random);
        	Random genmsgRD = new Random();
        	int randomm = genmsgRD.nextInt(msgRD.length-1);
        	collapsing_toolbar.setTitle(msgRD[randomm]);
                break;

            default:
                break;
           }
        } else {
           collapsing_toolbar =  root.findViewById(R.id.collapsing_toolbar);
           collapsing_toolbar.setTitle(getResources().getString(R.string.top_level_settings_title));
        }
        
        }
        getLifecycle().addObserver(new HideNonSystemOverlayMixin(this));
        mCategoryMixin = new CategoryMixin(this);
        getLifecycle().addObserver(mCategoryMixin);

        mMainFragment = showFragment(() -> {
            final TopLevelSettings fragment = new TopLevelSettings();
            fragment.getArguments().putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY,
                    highlightMenuKey);
            return fragment;
        }, R.id.main_content);

        ((FrameLayout) findViewById(R.id.main_content))
                .getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);

        // Launch the intent from deep link for large screen devices.
        launchDeepLinkIntentToRight();
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
        Context context = getApplicationContext();
        final boolean useStockLayout = Settings.System.getIntForUser(context.getContentResolver(),
                "use_stock_layout", 0, UserHandle.USER_CURRENT) != 0;

        if (useStockLayout) {
        final boolean newTwoPaneState = mSplitController.isActivityEmbedded(this);
        if (mIsTwoPane != newTwoPaneState) {
            mIsTwoPane = newTwoPaneState;
            updateHomepageAppBar();
            updateHomepageBackground();
        }
        updateSplitLayout();
        }
    }

    private void updateSplitLayout() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }

        if (mIsTwoPane) {
            if (mIsRegularLayout == ActivityEmbeddingUtils.isRegularHomepageLayout(this)) {
                // Layout unchanged
                return;
            }
        } else if (mIsRegularLayout) {
            // One pane mode with the regular layout, not needed to change
            return;
        }
        mIsRegularLayout = !mIsRegularLayout;

        // Update search title padding
        View searchTitle = findViewById(R.id.search_bar_title);
        if (searchTitle != null) {
            int paddingStart = getResources().getDimensionPixelSize(
                    mIsRegularLayout
                            ? R.dimen.search_bar_title_padding_start_regular_two_pane
                            : R.dimen.search_bar_title_padding_start);
            searchTitle.setPaddingRelative(paddingStart, 0, 0, 0);
        }
        // Notify fragments
        getSupportFragmentManager().getFragments().forEach(fragment -> {
            if (fragment instanceof SplitLayoutListener) {
                ((SplitLayoutListener) fragment).onSplitLayoutChanged(mIsRegularLayout);
            }
        });
    }

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    // Apply the insets paddings to the view.
                    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                    // Return CONSUMED if you don't want the window insets to keep being
                    // passed down to descendant views.
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    private void initSearchBarView() {
        final Toolbar toolbar = findViewById(R.id.search_action_bar);
        FeatureFactory.getFactory(this).getSearchFeatureProvider()
                .initSearchToolbar(this /* activity */, toolbar, SettingsEnums.SETTINGS_HOMEPAGE);

        if (mIsEmbeddingActivityEnabled) {
            final Toolbar toolbarTwoPaneVersion = findViewById(R.id.search_action_bar_two_pane);
            FeatureFactory.getFactory(this).getSearchFeatureProvider()
                    .initSearchToolbar(this /* activity */, toolbarTwoPaneVersion,
                            SettingsEnums.SETTINGS_HOMEPAGE);
        }
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

    private void showSuggestionFragment(boolean scrollNeeded) {
        final Class<? extends Fragment> fragmentClass = FeatureFactory.getFactory(this)
                .getSuggestionFeatureProvider(this).getContextualSuggestionFragment();
        if (fragmentClass == null) {
            return;
        }

        mSuggestionView = findViewById(R.id.suggestion_content);
        mTwoPaneSuggestionView = findViewById(R.id.two_pane_suggestion_content);
        mHomepageView = findViewById(R.id.settings_homepage_container);
        // Hide the homepage for preparing the suggestion. If scrolling is needed, the list views
        // should be initialized in the invisible homepage view to prevent a scroll flicker.
        mHomepageView.setVisibility(scrollNeeded ? View.INVISIBLE : View.GONE);
        // Schedule a timer to show the homepage and hide the suggestion on timeout.
        mHomepageView.postDelayed(() -> showHomepageWithSuggestion(false),
                HOMEPAGE_LOADING_TIMEOUT_MS);
        final FragmentBuilder<?> fragmentBuilder = () -> {
            try {
                return fragmentClass.getConstructor().newInstance();
            } catch (Exception e) {
                Log.w(TAG, "Cannot show fragment", e);
            }
            return null;
        };
        showFragment(fragmentBuilder, R.id.suggestion_content);
        if (mIsEmbeddingActivityEnabled) {
            showFragment(fragmentBuilder, R.id.two_pane_suggestion_content);
        }
    }

    private <T extends Fragment> T showFragment(FragmentBuilder<T> fragmentBuilder, int id) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        T showFragment = (T) fragmentManager.findFragmentById(id);

        if (showFragment == null) {
            showFragment = fragmentBuilder.build();
            fragmentTransaction.add(id, showFragment);
        } else {
            fragmentTransaction.show(showFragment);
        }
        fragmentTransaction.commit();
        return showFragment;
    }

    private void launchDeepLinkIntentToRight() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }

        final Intent intent = getIntent();
        if (intent == null || !TextUtils.equals(intent.getAction(),
                ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)) {
            return;
        }

        if (!(this instanceof DeepLinkHomepageActivity
                || this instanceof SliceDeepLinkHomepageActivity)) {
            Log.e(TAG, "Not a deep link component");
            finish();
            return;
        }

        final String intentUriString = intent.getStringExtra(
                EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI);
        if (TextUtils.isEmpty(intentUriString)) {
            Log.e(TAG, "No EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI to deep link");
            finish();
            return;
        }

        final Intent targetIntent;
        try {
            targetIntent = Intent.parseUri(intentUriString, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Failed to parse deep link intent: " + e);
            finish();
            return;
        }

        final ComponentName targetComponentName = targetIntent.resolveActivity(getPackageManager());
        if (targetComponentName == null) {
            Log.e(TAG, "No valid target for the deep link intent: " + targetIntent);
            finish();
            return;
        }
        targetIntent.setComponent(targetComponentName);

        // To prevent launchDeepLinkIntentToRight again for configuration change.
        intent.setAction(null);

        targetIntent.setFlags(targetIntent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        // Sender of intent may want to send intent extra data to the destination of targetIntent.
        targetIntent.replaceExtras(intent);

        targetIntent.putExtra(EXTRA_IS_FROM_SETTINGS_HOMEPAGE, true);
        targetIntent.putExtra(SettingsActivity.EXTRA_IS_FROM_SLICE, false);

        targetIntent.setData(intent.getParcelableExtra(
                SettingsHomepageActivity.EXTRA_SETTINGS_LARGE_SCREEN_DEEP_LINK_INTENT_DATA));

        // Set 2-pane pair rule for the deep link page.
        ActivityEmbeddingRulesController.registerTwoPanePairRule(this,
                new ComponentName(getApplicationContext(), getClass()),
                targetComponentName,
                targetIntent.getAction(),
                SplitRule.FINISH_ALWAYS,
                SplitRule.FINISH_ALWAYS,
                true /* clearTop */);
        ActivityEmbeddingRulesController.registerTwoPanePairRule(this,
                new ComponentName(getApplicationContext(), Settings.class),
                targetComponentName,
                targetIntent.getAction(),
                SplitRule.FINISH_ALWAYS,
                SplitRule.FINISH_ALWAYS,
                true /* clearTop */);
        startActivity(targetIntent);
    }

    private String getHighlightMenuKey() {
        final Intent intent = getIntent();
        if (intent != null && TextUtils.equals(intent.getAction(),
                ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)) {
            final String menuKey = intent.getStringExtra(
                    EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY);
            if (!TextUtils.isEmpty(menuKey)) {
                return menuKey;
            }
        }
        return getString(DEFAULT_HIGHLIGHT_MENU_KEY);
    }

    private void reloadHighlightMenuKey() {
        mMainFragment.getArguments().putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY,
                getHighlightMenuKey());
        mMainFragment.reloadHighlightMenuKey();
    }

    private void initHomepageContainer() {
        final View view = findViewById(R.id.homepage_container);
        // Prevent inner RecyclerView gets focus and invokes scrolling.
        view.setFocusableInTouchMode(true);
        view.requestFocus();
    }

    private void updateHomepageAppBar() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }
        if (ActivityEmbeddingUtils.isTwoPaneResolution(this)) {
            findViewById(R.id.homepage_app_bar_regular_phone_view).setVisibility(View.GONE);
            findViewById(R.id.homepage_app_bar_two_pane_view).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.homepage_app_bar_regular_phone_view).setVisibility(View.VISIBLE);
            findViewById(R.id.homepage_app_bar_two_pane_view).setVisibility(View.GONE);
        }
    }

    private int getSearchBoxHeight() {
        final int searchBarHeight = getResources().getDimensionPixelSize(R.dimen.search_bar_height);
        final int searchBarMargin = getResources().getDimensionPixelSize(R.dimen.search_bar_margin);
        return searchBarHeight + searchBarMargin * 2;
    }

    private static class SuggestionFragCreator implements FragmentCreator {

        private final Class<? extends Fragment> mClass;
        private final boolean mIsTwoPaneLayout;

        SuggestionFragCreator(Class<? extends Fragment> clazz, boolean isTwoPaneLayout) {
            mClass = clazz;
            mIsTwoPaneLayout = isTwoPaneLayout;
        }

        @Override
        public Fragment create() {
            try {
                Fragment fragment = mClass.getConstructor().newInstance();
                return fragment;
            } catch (Exception e) {
                Log.w(TAG, "Cannot show fragment", e);
            }
            return null;
        }

        @Override
        public void init(Fragment fragment) {
            if (fragment instanceof SplitLayoutListener) {
                ((SplitLayoutListener) fragment).setSplitLayoutSupported(mIsTwoPaneLayout);
            }
        }
    }

    private Drawable getCircularUserIcon(Context context) {
        Bitmap bitmapUserIcon = mUserManager.getUserIcon(UserHandle.myUserId());

        if (bitmapUserIcon == null) {
            // get default user icon.
            final Drawable defaultUserIcon = UserIcons.getDefaultUserIcon(
                    context.getResources(), UserHandle.myUserId(), false);
            bitmapUserIcon = UserIcons.convertToBitmap(defaultUserIcon);
        }
        Drawable drawableUserIcon = new CircleFramedDrawable(bitmapUserIcon,
                (int) context.getResources().getDimension(R.dimen.circle_avatar_size));

        return drawableUserIcon;
    }

    @Override
    public void onResume() {
        super.onResume();
        final boolean useStockLayout = getuseStockLayout();
        final boolean isUserCardDisabled = getUserCardState();
	
        if (useStockLayout && isUserCardDisabled) {
            avatarView.setImageDrawable(getCircularUserIcon(getApplicationContext()));
        }
    }

    private boolean getUserCardState() { 
        final Context context = getApplicationContext();
        return Settings.System.getIntForUser(context.getContentResolver(),
                "hide_user_card", 0,
                UserHandle.USER_CURRENT) != 0;
   }
                
    private boolean getuseStockLayout() { 
        final Context context = getApplicationContext();
        return Settings.System.getIntForUser(context.getContentResolver(),
                "use_stock_layout", 0,
                UserHandle.USER_CURRENT) != 0;
   }
   
    private boolean getMessagesSettings() {
        final Context context = getApplicationContext();
        return Settings.System.getIntForUser(context.getContentResolver(),
                "settings_contextual_messages", 0,
                UserHandle.USER_CURRENT) != 0;
   }
}
