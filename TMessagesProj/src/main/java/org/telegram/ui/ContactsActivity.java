/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SecretChatHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.ContactsAdapter;
import org.telegram.ui.Adapters.SearchAdapter;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.LetterSectionCell;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StickerEmptyView;

import java.util.ArrayList;

public class ContactsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private ContactsAdapter listViewAdapter;
    private StickerEmptyView emptyView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private SearchAdapter searchListViewAdapter;

    private ActionBarMenuItem sortItem;
    private boolean sortByName;

    private RLottieImageView floatingButton;
    private FrameLayout floatingButtonContainer;
    private AccelerateDecelerateInterpolator floatingInterpolator = new AccelerateDecelerateInterpolator();
    private int prevPosition;
    private int prevTop;
    private boolean scrollUpdated;
    private boolean floatingHidden;

    private boolean hasGps;
    private boolean searchWas;
    private boolean searching;
    private boolean onlyUsers;
    private boolean needPhonebook;
    private boolean destroyAfterSelect;
    private boolean returnAsResult;
    private boolean createSecretChat;
    private boolean creatingChat;
    private boolean allowSelf = true;
    private boolean allowBots = true;
    private boolean needForwardCount = true;
    private boolean needFinishFragment = true;
    private boolean resetDelegate = true;
    private int channelId;
    private int chatId;
    private String selectAlertString = null;
    private SparseArray<TLRPC.User> ignoreUsers;
    private boolean allowUsernameSearch = true;
    private ContactsActivityDelegate delegate;
    private String initialSearchString;

    private AlertDialog permissionDialog;
    private boolean askAboutContacts = true;

    private boolean disableSections;

    private boolean checkPermission = true;

    private AnimatorSet bounceIconAnimator;
    private int animationIndex = -1;

    private final static int search_button = 0;
    private final static int sort_button = 1;
    private String entry;

    public interface ContactsActivityDelegate {
        void didSelectContact(TLRPC.User user, String param, ContactsActivity activity);
    }

    public ContactsActivity(Bundle args) {
        super(args);
        if(args != null){
            entry = args.getString("entry", "");
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.contactsDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.encryptedChatCreated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.closeChats);
        checkPermission = UserConfig.getInstance(currentAccount).syncContacts;
        if (arguments != null) {
            onlyUsers = arguments.getBoolean("onlyUsers", false);
            destroyAfterSelect = arguments.getBoolean("destroyAfterSelect", false);
            returnAsResult = arguments.getBoolean("returnAsResult", false);
            createSecretChat = arguments.getBoolean("createSecretChat", false);
            selectAlertString = arguments.getString("selectAlertString");
            allowUsernameSearch = arguments.getBoolean("allowUsernameSearch", true);
            needForwardCount = arguments.getBoolean("needForwardCount", true);
            allowBots = arguments.getBoolean("allowBots", true);
            allowSelf = arguments.getBoolean("allowSelf", true);
            channelId = arguments.getInt("channelId", 0);
            needFinishFragment = arguments.getBoolean("needFinishFragment", true);
            chatId = arguments.getInt("chat_id", 0);
            disableSections = arguments.getBoolean("disableSections", false);
            resetDelegate = arguments.getBoolean("resetDelegate", false);
        } else {
            needPhonebook = true;
        }

        if (!createSecretChat && !returnAsResult) {
            sortByName = SharedConfig.sortContactsByName;
        }

        getContactsController().checkInviteText();
        getContactsController().reloadContactsStatusesMaybe();


        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.contactsDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.encryptedChatCreated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.closeChats);
        delegate = null;
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        getNotificationCenter().onAnimationFinish(animationIndex);
    }

    @Override
    protected void onTransitionAnimationProgress(boolean isOpen, float progress) {
        super.onTransitionAnimationProgress(isOpen, progress);
        fragmentView.invalidate();
    }

    @Override
    public View createView(Context context) {
        searching = false;
        searchWas = false;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        if (destroyAfterSelect) {
            if (returnAsResult) {
                actionBar.setTitle(LocaleController.getString("SelectContact", R.string.SelectContact));
            } else {
                if (createSecretChat) {
                    actionBar.setTitle(LocaleController.getString("NewSecretChat", R.string.NewSecretChat));
                } else {
                    actionBar.setTitle(LocaleController.getString("NewMessageTitle", R.string.NewMessageTitle));
                }
            }
        } else {
            actionBar.setTitle(LocaleController.getString("Contacts", R.string.Contacts));
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == sort_button) {
                    SharedConfig.toggleSortContactsByName();
                    sortByName = SharedConfig.sortContactsByName;
                    listViewAdapter.setSortType(sortByName ? 1 : 2, false);
                    sortItem.setIcon(sortByName ? R.drawable.contacts_sort_time : R.drawable.contacts_sort_name);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem item = menu.addItem(search_button, R.drawable.ic_ab_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (floatingButtonContainer != null) {
                    floatingButtonContainer.setVisibility(View.GONE);
                }
                if (sortItem != null) {
                    sortItem.setVisibility(View.GONE);
                }
            }

            @Override
            public void onSearchCollapse() {
                searchListViewAdapter.searchDialogs(null);
                searching = false;
                searchWas = false;
                listView.setAdapter(listViewAdapter);
                listView.setSectionsType(1);
                listViewAdapter.notifyDataSetChanged();
                listView.setFastScrollVisible(true);
                listView.setVerticalScrollBarEnabled(false);
                // emptyView.setText(LocaleController.getString("NoContacts", R.string.NoContacts));
                if (floatingButtonContainer != null) {
                    floatingButtonContainer.setVisibility(View.VISIBLE);
                    floatingHidden = true;
                    floatingButtonContainer.setTranslationY(AndroidUtilities.dp(100));
                    hideFloatingButton(false);
                }
                if (sortItem != null) {
                    sortItem.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onTextChanged(EditText editText) {
                if (searchListViewAdapter == null) {
                    return;
                }
                String text = editText.getText().toString();
                if (text.length() != 0) {
                    searchWas = true;
                    if (listView != null) {
                        listView.setAdapter(searchListViewAdapter);
                        listView.setSectionsType(0);
                        searchListViewAdapter.notifyDataSetChanged();
                        listView.setFastScrollVisible(false);
                        listView.setVerticalScrollBarEnabled(true);
                    }
                    emptyView.showProgress(true, true);
                    searchListViewAdapter.searchDialogs(text);
                } else {
                    if (listView != null) {
                        listView.setAdapter(listViewAdapter);
                        listView.setSectionsType(1);
                    }
                }
            }
        });
        item.setSearchFieldHint(LocaleController.getString("Search", R.string.Search));
        item.setContentDescription(LocaleController.getString("Search", R.string.Search));
        if (!createSecretChat && !returnAsResult) {
            sortItem = menu.addItem(sort_button, sortByName ? R.drawable.contacts_sort_time : R.drawable.contacts_sort_name);
            sortItem.setContentDescription(LocaleController.getString("AccDescrContactSorting", R.string.AccDescrContactSorting));
        }

        searchListViewAdapter = new SearchAdapter(context, ignoreUsers, allowUsernameSearch, false, false, allowBots, allowSelf, true, 0) {
            @Override
            protected void onSearchProgressChanged() {
                if (!searchInProgress() && getItemCount() == 0) {
                    emptyView.showProgress(false, true);
                }
                showItemsAnimated();
            }
        };
        int inviteViaLink;
        if (chatId != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
            inviteViaLink = ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_INVITE) ? 1 : 0;
        } else if (channelId != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
            inviteViaLink = ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_INVITE) && TextUtils.isEmpty(chat.username) ? 2 : 0;
        } else {
            inviteViaLink = 0;
        }
        try {
            hasGps = ApplicationLoader.applicationContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        } catch (Throwable e) {
            hasGps = false;
        }
        listViewAdapter = new ContactsAdapter(context, onlyUsers ? 1 : 0, needPhonebook, ignoreUsers, inviteViaLink, hasGps) {
            @Override
            public void notifyDataSetChanged() {
                super.notifyDataSetChanged();
                if (listView != null && listView.getAdapter() == this) {
                    int count = super.getItemCount();
                    if (needPhonebook) {
                        //  emptyView.setVisibility(count == 2 ? View.VISIBLE : View.GONE);
                        listView.setFastScrollVisible(count != 2);
                    } else {
                        //emptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
                        listView.setFastScrollVisible(count != 0);
                    }
                }
            }
        };
        listViewAdapter.setSortType(sortItem != null ? (sortByName ? 1 : 2) : 0, false);
        listViewAdapter.setDisableSections(disableSections);

        fragmentView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (listView.getAdapter() == listViewAdapter) {
                    if (emptyView.getVisibility() == VISIBLE) {
                        emptyView.setTranslationY(AndroidUtilities.dp(74));
                    }
                } else {
                    emptyView.setTranslationY(AndroidUtilities.dp(0));
                }
            }
        };
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        FlickerLoadingView flickerLoadingView = new FlickerLoadingView(context);
        flickerLoadingView.setViewType(FlickerLoadingView.USERS_TYPE);
        flickerLoadingView.showDate(false);

        emptyView = new StickerEmptyView(context, flickerLoadingView, StickerEmptyView.STICKER_TYPE_SEARCH);
        emptyView.addView(flickerLoadingView, 0);
        emptyView.setAnimateLayoutChange(true);
        emptyView.showProgress(true, false);
        emptyView.title.setText(LocaleController.getString("NoResult", R.string.NoResult));
        emptyView.subtitle.setText(LocaleController.getString("SearchEmptyViewFilteredSubtitle2", R.string.SearchEmptyViewFilteredSubtitle2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context) {
            @Override
            public void setPadding(int left, int top, int right, int bottom) {
                super.setPadding(left, top, right, bottom);
                if (emptyView != null) {
                    emptyView.setPadding(left, top, right, bottom);
                }
            }
        };
        listView.setSectionsType(1);
        listView.setVerticalScrollBarEnabled(false);
        listView.setFastScrollEnabled();
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listViewAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setEmptyView(emptyView);
        listView.setAnimateEmptyView(true, 0);

        listView.setOnItemClickListener((view, position) -> {
            if (listView.getAdapter() == searchListViewAdapter) {
                Object object = searchListViewAdapter.getItem(position);
                if (object instanceof TLRPC.User) {
                    TLRPC.User user = (TLRPC.User) object;
                    if (user == null) {
                        return;
                    }
                    if (searchListViewAdapter.isGlobalSearch(position)) {
                        ArrayList<TLRPC.User> users = new ArrayList<>();
                        users.add(user);
                        MessagesController.getInstance(currentAccount).putUsers(users, false);
                        MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, false, true);
                    }
                    if (returnAsResult) {
                        if (ignoreUsers != null && ignoreUsers.indexOfKey(user.id) >= 0) {
                            return;
                        }
                        didSelectResult(user, true, null);
                    } else {
                        if (createSecretChat) {
                            if (user.id == UserConfig.getInstance(currentAccount).getClientUserId()) {
                                return;
                            }
                            creatingChat = true;
                            SecretChatHelper.getInstance(currentAccount).startSecretChat(getParentActivity(), user);
                        } else {
                            Bundle args = new Bundle();
                            args.putInt("user_id", user.id);
                            if (MessagesController.getInstance(currentAccount).checkCanOpenChat(args, ContactsActivity.this)) {
                                if ("home".equals(entry)) {
                                    presentFragment(new ChatActivity(args), false);
                                } else {
                                    presentFragment(new ChatActivity(args), true);
                                }
                            }
                        }
                    }
                } else if (object instanceof String) {
                    String str = (String) object;
                    if (!str.equals("section")) {
                        NewContactActivity activity = new NewContactActivity();
                        activity.setInitialPhoneNumber(str, true);
                        presentFragment(activity);
                    }
                }
            } else {
                int section = listViewAdapter.getSectionForPosition(position);
                int row = listViewAdapter.getPositionInSectionForPosition(position);
                if (row < 0 || section < 0) {
                    return;
                }
                if ((!onlyUsers || inviteViaLink != 0) && section == 0) {
                    if (needPhonebook) {
                        if (row == 0) {
                            presentFragment(new InviteContactsActivity());
                        } else if (row == 1 && hasGps) {
                            if (Build.VERSION.SDK_INT >= 23) {
                                Activity activity = getParentActivity();
                                if (activity != null) {
                                    if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                        presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_NEARBY_LOCATION_ACCESS));
                                        return;
                                    }
                                }
                            }
                            boolean enabled = true;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
                                enabled = lm.isLocationEnabled();
                            } else if (Build.VERSION.SDK_INT >= 19) {
                                try {
                                    int mode = Settings.Secure.getInt(ApplicationLoader.applicationContext.getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
                                    enabled = (mode != Settings.Secure.LOCATION_MODE_OFF);
                                } catch (Throwable e) {
                                    FileLog.e(e);
                                }
                            }
                            if (!enabled) {
                                presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_NEARBY_LOCATION_ENABLED));
                                return;
                            }
                            presentFragment(new PeopleNearbyActivity());
                        }
                    } else if (inviteViaLink != 0) {
                        if (row == 0) {
                            presentFragment(new GroupInviteActivity(chatId != 0 ? chatId : channelId));
                        }
                    } else {
                        if (row == 0) {
                            Bundle args = new Bundle();
                            presentFragment(new GroupCreateActivity(args), false);
                        } else if (row == 1) {
                            Bundle args = new Bundle();
                            args.putBoolean("onlyUsers", true);
                            args.putBoolean("destroyAfterSelect", true);
                            args.putBoolean("createSecretChat", true);
                            args.putBoolean("allowBots", false);
                            args.putBoolean("allowSelf", false);
                            presentFragment(new ContactsActivity(args), false);
                        } else if (row == 2) {
                            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                            if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
                                Bundle args = new Bundle();
                                args.putInt("step", 0);
                                presentFragment(new ChannelCreateActivity(args));
                            } else {
                                presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
                                preferences.edit().putBoolean("channel_intro", true).commit();
                            }
                        }
                    }
                } else {
                    Object item1 = listViewAdapter.getItem(section, row);

                    if (item1 instanceof TLRPC.User) {
                        TLRPC.User user = (TLRPC.User) item1;
                        if (returnAsResult) {
                            if (ignoreUsers != null && ignoreUsers.indexOfKey(user.id) >= 0) {
                                return;
                            }
                            didSelectResult(user, true, null);
                        } else {
                            if (createSecretChat) {
                                creatingChat = true;
                                SecretChatHelper.getInstance(currentAccount).startSecretChat(getParentActivity(), user);
                            } else {
                                Bundle args = new Bundle();
                                args.putInt("user_id", user.id);
                                if (MessagesController.getInstance(currentAccount).checkCanOpenChat(args, ContactsActivity.this)) {
                                    if ("home".equals(entry)) {
                                        presentFragment(new ChatActivity(args), false);
                                    } else {
                                        presentFragment(new ChatActivity(args), true);
                                    }
                                }
                            }
                        }
                    } else if (item1 instanceof ContactsController.Contact) {
                        ContactsController.Contact contact = (ContactsController.Contact) item1;
                        String usePhone = null;
                        if (!contact.phones.isEmpty()) {
                            usePhone = contact.phones.get(0);
                        }
                        if (usePhone == null || getParentActivity() == null) {
                            return;
                        }
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setMessage(LocaleController.getString("InviteUser", R.string.InviteUser));
                        builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                        final String arg1 = usePhone;
                        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialogInterface, i) -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromParts("sms", arg1, null));
                                intent.putExtra("sms_body", ContactsController.getInstance(currentAccount).getInviteText(1));
                                getParentActivity().startActivityForResult(intent, 500);
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        });
                        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                        showDialog(builder.create());
                    }
                }
            }
        });

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {

            private boolean scrollingManually;

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (searching && searchWas) {
                        AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                    }
                    scrollingManually = true;
                } else {
                    scrollingManually = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (floatingButtonContainer != null && floatingButtonContainer.getVisibility() != View.GONE) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();

                    final View topChild = recyclerView.getChildAt(0);
                    int firstViewTop = 0;
                    if (topChild != null) {
                        firstViewTop = topChild.getTop();
                    }
                    boolean goingDown;
                    boolean changed = true;
                    if (prevPosition == firstVisibleItem) {
                        final int topDelta = prevTop - firstViewTop;
                        goingDown = firstViewTop < prevTop;
                        changed = Math.abs(topDelta) > 1;
                    } else {
                        goingDown = firstVisibleItem > prevPosition;
                    }
                    if (changed && scrollUpdated && (goingDown || !goingDown && scrollingManually)) {
                        hideFloatingButton(goingDown);
                    }
                    prevPosition = firstVisibleItem;
                    prevTop = firstViewTop;
                    scrollUpdated = true;
                }
            }
        });

        if (!createSecretChat && !returnAsResult) {
            floatingButtonContainer = new FrameLayout(context);
            frameLayout.addView(floatingButtonContainer, LayoutHelper.createFrame((Build.VERSION.SDK_INT >= 21 ? 56 : 60) + 20, (Build.VERSION.SDK_INT >= 21 ? 56 : 60) + 20, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, LocaleController.isRTL ? 4 : 0, 0, LocaleController.isRTL ? 0 : 4, 0));
            floatingButtonContainer.setOnClickListener(v -> presentFragment(new NewContactActivity()));

            floatingButton = new RLottieImageView(context);
            floatingButton.setScaleType(ImageView.ScaleType.CENTER);
            Drawable drawable = Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground));
            if (Build.VERSION.SDK_INT < 21) {
                Drawable shadowDrawable = context.getResources().getDrawable(R.drawable.floating_shadow).mutate();
                shadowDrawable.setColorFilter(new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.MULTIPLY));
                CombinedDrawable combinedDrawable = new CombinedDrawable(shadowDrawable, drawable, 0, 0);
                combinedDrawable.setIconSize(AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                drawable = combinedDrawable;
            }
            floatingButton.setBackgroundDrawable(drawable);
            floatingButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean configAnimationsEnabled = preferences.getBoolean("view_animations", true);
            floatingButton.setAnimation(configAnimationsEnabled ? R.raw.write_contacts_fab_icon : R.raw.write_contacts_fab_icon_reverse, 52, 52);
            floatingButtonContainer.setContentDescription(LocaleController.getString("CreateNewContact", R.string.CreateNewContact));
            if (Build.VERSION.SDK_INT >= 21) {
                StateListAnimator animator = new StateListAnimator();
                animator.addState(new int[]{android.R.attr.state_pressed}, ObjectAnimator.ofFloat(floatingButton, View.TRANSLATION_Z, AndroidUtilities.dp(2), AndroidUtilities.dp(4)).setDuration(200));
                animator.addState(new int[]{}, ObjectAnimator.ofFloat(floatingButton, View.TRANSLATION_Z, AndroidUtilities.dp(4), AndroidUtilities.dp(2)).setDuration(200));
                floatingButton.setStateListAnimator(animator);
                floatingButton.setOutlineProvider(new ViewOutlineProvider() {
                    @SuppressLint("NewApi")
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                    }
                });
            }
            floatingButtonContainer.addView(floatingButton, LayoutHelper.createFrame((Build.VERSION.SDK_INT >= 21 ? 56 : 60), (Build.VERSION.SDK_INT >= 21 ? 56 : 60), Gravity.LEFT | Gravity.TOP, 10, 6, 10, 0));
        }

        if (initialSearchString != null) {
            actionBar.openSearchField(initialSearchString, false);
            initialSearchString = null;
        }

        return fragmentView;
    }

    private void didSelectResult(final TLRPC.User user, boolean useAlert, String param) {
        if (useAlert && selectAlertString != null) {
            if (getParentActivity() == null) {
                return;
            }
            if (user.bot) {
                if (user.bot_nochats) {
                    try {
                        Toast.makeText(getParentActivity(), LocaleController.getString("BotCantJoinGroups", R.string.BotCantJoinGroups), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    return;
                }
                if (channelId != 0) {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    if (ChatObject.canAddAdmins(chat)) {
                        builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                        builder.setMessage(LocaleController.getString("AddBotAsAdmin", R.string.AddBotAsAdmin));
                        builder.setPositiveButton(LocaleController.getString("MakeAdmin", R.string.MakeAdmin), (dialogInterface, i) -> {
                            if (delegate != null) {
                                delegate.didSelectContact(user, param, this);
                                delegate = null;
                            }
                        });
                        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                    } else {
                        builder.setMessage(LocaleController.getString("CantAddBotAsAdmin", R.string.CantAddBotAsAdmin));
                        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), null);
                    }
                    showDialog(builder.create());
                    return;
                }
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
            String message = LocaleController.formatStringSimple(selectAlertString, UserObject.getUserName(user));
            EditTextBoldCursor editText = null;
            if (!user.bot && needForwardCount) {
                message = String.format("%s\n\n%s", message, LocaleController.getString("AddToTheGroupForwardCount", R.string.AddToTheGroupForwardCount));
                editText = new EditTextBoldCursor(getParentActivity());
                editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                editText.setText("50");
                editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                editText.setGravity(Gravity.CENTER);
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
                editText.setBackgroundDrawable(Theme.createEditTextDrawable(getParentActivity(), true));
                final EditText editTextFinal = editText;
                editText.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        try {
                            String str = s.toString();
                            if (str.length() != 0) {
                                int value = Utilities.parseInt(str);
                                if (value < 0) {
                                    editTextFinal.setText("0");
                                    editTextFinal.setSelection(editTextFinal.length());
                                } else if (value > 300) {
                                    editTextFinal.setText("300");
                                    editTextFinal.setSelection(editTextFinal.length());
                                } else if (!str.equals("" + value)) {
                                    editTextFinal.setText("" + value);
                                    editTextFinal.setSelection(editTextFinal.length());
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }

                });
                builder.setView(editText);
            }
            builder.setMessage(message);
            final EditText finalEditText = editText;
            builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialogInterface, i) -> didSelectResult(user, false, finalEditText != null ? finalEditText.getText().toString() : "0"));
            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
            showDialog(builder.create());
            if (editText != null) {
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) editText.getLayoutParams();
                if (layoutParams != null) {
                    if (layoutParams instanceof FrameLayout.LayoutParams) {
                        ((FrameLayout.LayoutParams) layoutParams).gravity = Gravity.CENTER_HORIZONTAL;
                    }
                    layoutParams.rightMargin = layoutParams.leftMargin = AndroidUtilities.dp(24);
                    layoutParams.height = AndroidUtilities.dp(36);
                    editText.setLayoutParams(layoutParams);
                }
                editText.setSelection(editText.getText().length());
            }
        } else {
            if (delegate != null) {
                delegate.didSelectContact(user, param, this);
                if (resetDelegate) {
                    delegate = null;
                }
            }
            if (needFinishFragment) {
                finishFragment();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        if (listViewAdapter != null) {
            listViewAdapter.notifyDataSetChanged();
        }
        if (checkPermission && Build.VERSION.SDK_INT >= 23) {
            Activity activity = getParentActivity();
            if (activity != null) {
                checkPermission = false;
                if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    if (activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                        AlertDialog.Builder builder = AlertsCreator.createContactsPermissionDialog(activity, param -> {
                            askAboutContacts = param != 0;
                            if (param == 0) {
                                return;
                            }
                            askForPermissons(false);
                        });
                        showDialog(permissionDialog = builder.create());
                    } else {
                        askForPermissons(true);
                    }
                }
            }
        }
    }

    protected RecyclerListView getListView() {
        return listView;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (floatingButtonContainer != null) {
            floatingButtonContainer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    floatingButtonContainer.setTranslationY((floatingHidden ? AndroidUtilities.dp(100) : 0));
                    floatingButtonContainer.setClickable(!floatingHidden);
                    if (floatingButtonContainer != null) {
                        floatingButtonContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                }
            });
        }
    }

    @Override
    protected void onDialogDismiss(Dialog dialog) {
        super.onDialogDismiss(dialog);
        if (permissionDialog != null && dialog == permissionDialog && getParentActivity() != null && askAboutContacts) {
            askForPermissons(false);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void askForPermissons(boolean alert) {
        Activity activity = getParentActivity();
        if (activity == null || !UserConfig.getInstance(currentAccount).syncContacts || activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (alert && askAboutContacts) {
            AlertDialog.Builder builder = AlertsCreator.createContactsPermissionDialog(activity, param -> {
                askAboutContacts = param != 0;
                if (param == 0) {
                    return;
                }
                askForPermissons(false);
            });
            showDialog(builder.create());
            return;
        }
        ArrayList<String> permissons = new ArrayList<>();
        permissons.add(Manifest.permission.READ_CONTACTS);
        permissons.add(Manifest.permission.WRITE_CONTACTS);
        permissons.add(Manifest.permission.GET_ACCOUNTS);
        String[] items = permissons.toArray(new String[0]);
        try {
            activity.requestPermissions(items, 1);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1) {
            for (int a = 0; a < permissions.length; a++) {
                if (grantResults.length <= a) {
                    continue;
                }
                if (Manifest.permission.READ_CONTACTS.equals(permissions[a])) {
                    if (grantResults[a] == PackageManager.PERMISSION_GRANTED) {
                        ContactsController.getInstance(currentAccount).forceImportContacts();
                    } else {
                        MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts", askAboutContacts = false).commit();
                    }
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (actionBar != null) {
            actionBar.closeSearchField();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.contactsDidLoad) {
            if (listViewAdapter != null) {
                if (!sortByName) {
                    listViewAdapter.setSortType(2, true);
                }
                listViewAdapter.notifyDataSetChanged();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & MessagesController.UPDATE_MASK_AVATAR) != 0 || (mask & MessagesController.UPDATE_MASK_NAME) != 0 || (mask & MessagesController.UPDATE_MASK_STATUS) != 0) {
                updateVisibleRows(mask);
            }
            if ((mask & MessagesController.UPDATE_MASK_STATUS) != 0 && !sortByName && listViewAdapter != null) {
                listViewAdapter.sortOnlineContacts();
            }
        } else if (id == NotificationCenter.encryptedChatCreated) {
            if (createSecretChat && creatingChat) {
                TLRPC.EncryptedChat encryptedChat = (TLRPC.EncryptedChat) args[0];
                Bundle args2 = new Bundle();
                args2.putInt("enc_id", encryptedChat.id);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
                if ("home".equals(entry)) {
                    presentFragment(new ChatActivity(args2), false);
                } else {
                    presentFragment(new ChatActivity(args2), true);
                }
            }
        } else if (id == NotificationCenter.closeChats) {
            if (!creatingChat) {
                removeSelfFromStack();
            }
        }
    }

    private void updateVisibleRows(int mask) {
        if (listView != null) {
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = listView.getChildAt(a);
                if (child instanceof UserCell) {
                    ((UserCell) child).update(mask);
                }
            }
        }
    }

    private void hideFloatingButton(boolean hide) {
        if (floatingHidden == hide) {
            return;
        }
        floatingHidden = hide;
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(floatingButtonContainer, View.TRANSLATION_Y, (floatingHidden ? AndroidUtilities.dp(100) : 0)));
        animatorSet.setDuration(300);
        animatorSet.setInterpolator(floatingInterpolator);
        floatingButtonContainer.setClickable(!hide);
        animatorSet.start();
    }

    public void setDelegate(ContactsActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public void setIgnoreUsers(SparseArray<TLRPC.User> users) {
        ignoreUsers = users;
    }

    public void setInitialSearchString(String initialSearchString) {
        this.initialSearchString = initialSearchString;
    }

    private void showItemsAnimated() {
        int from = layoutManager == null ? 0 : layoutManager.findLastVisibleItemPosition();
        listView.invalidate();
        listView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                listView.getViewTreeObserver().removeOnPreDrawListener(this);
                int n = listView.getChildCount();
                AnimatorSet animatorSet = new AnimatorSet();
                for (int i = 0; i < n; i++) {
                    View child = listView.getChildAt(i);
                    if (listView.getChildAdapterPosition(child) <= from) {
                        continue;
                    }
                    child.setAlpha(0);
                    int s = Math.min(listView.getMeasuredHeight(), Math.max(0, child.getTop()));
                    int delay = (int) ((s / (float) listView.getMeasuredHeight()) * 100);
                    ObjectAnimator a = ObjectAnimator.ofFloat(child, View.ALPHA, 0, 1f);
                    a.setStartDelay(delay);
                    a.setDuration(200);
                    animatorSet.playTogether(a);
                }
                animatorSet.start();
                return true;
            }
        });
    }

    @Override
    protected AnimatorSet onCustomTransitionAnimation(boolean isOpen, Runnable callback) {
        ValueAnimator valueAnimator = isOpen ? ValueAnimator.ofFloat(1f, 0) : ValueAnimator.ofFloat(0, 1f);
        ViewGroup parent = (ViewGroup) fragmentView.getParent();
        BaseFragment previousFragment = parentLayout.fragmentsStack.size() > 1 ? parentLayout.fragmentsStack.get(parentLayout.fragmentsStack.size() - 2) : null;
        DialogsActivity dialogsActivity = null;
        if (previousFragment instanceof DialogsActivity) {
            dialogsActivity = (DialogsActivity) previousFragment;
        }
        if (dialogsActivity == null) {
            return null;
        }
        RLottieImageView previousFab = dialogsActivity.getFloatingButton();
        if (previousFab == null || floatingButtonContainer == null || previousFab.getVisibility() != View.VISIBLE || Math.abs(previousFab.getTranslationY()) > AndroidUtilities.dp(4) || Math.abs(floatingButtonContainer.getTranslationY()) > AndroidUtilities.dp(4)) {
            return null;
        }
        previousFab.setVisibility(View.GONE);
        if (isOpen) {
            parent.setAlpha(0f);
        }
        valueAnimator.addUpdateListener(valueAnimator1 -> {
            float v = (float) valueAnimator.getAnimatedValue();
            parent.setTranslationX(AndroidUtilities.dp(48) * v);
            parent.setAlpha(1f - v);
        });
        if (floatingButtonContainer != null) {
            ((ViewGroup) fragmentView).removeView(floatingButtonContainer);
            ((FrameLayout) parent.getParent()).addView(floatingButtonContainer);
        }
        valueAnimator.setDuration(150);
        valueAnimator.setInterpolator(new DecelerateInterpolator(1.5f));

        final int currentAccount = this.currentAccount;

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (floatingButtonContainer != null) {
                    ViewGroup viewParent;
                    if (floatingButtonContainer.getParent() instanceof ViewGroup) {
                        viewParent = (ViewGroup) floatingButtonContainer.getParent();
                        viewParent.removeView(floatingButtonContainer);
                    }
                    ((ViewGroup) fragmentView).addView(floatingButtonContainer);

                    previousFab.setVisibility(View.VISIBLE);
                    if (!isOpen) {
                        previousFab.setAnimation(R.raw.write_contacts_fab_icon_reverse, 52, 52);
                        previousFab.getAnimatedDrawable().setCurrentFrame(floatingButton.getAnimatedDrawable().getCurrentFrame());
                        previousFab.playAnimation();
                    }
                }
                callback.run();
            }
        });
        animatorSet.playTogether(valueAnimator);
        AndroidUtilities.runOnUIThread(() -> {
            animationIndex = getNotificationCenter().setAnimationInProgress(animationIndex, null);
            animatorSet.start();
            if (isOpen) {
                floatingButton.setAnimation(R.raw.write_contacts_fab_icon, 52, 52);
                floatingButton.playAnimation();
            } else {
                floatingButton.setAnimation(R.raw.write_contacts_fab_icon_reverse, 52, 52);
                floatingButton.playAnimation();
            }
            if (bounceIconAnimator != null) {
                bounceIconAnimator.cancel();
            }
            bounceIconAnimator = new AnimatorSet();
            float totalDuration = floatingButton.getAnimatedDrawable().getDuration();
            long delay = 0;
            if (isOpen) {
                for (int i = 0; i < 6; i++) {
                    AnimatorSet set = new AnimatorSet();
                    if (i == 0) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1f, 0.9f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1f, 0.9f)
                        );
                        set.setDuration((long) (6f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                    } else if (i == 1) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 0.9f, 1.06f)
                        );
                        set.setDuration((long) (17f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else if (i == 2) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1.06f, 0.9f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1.06f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1.06f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1.06f, 0.9f)
                        );
                        set.setDuration((long) (10f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else if (i == 3) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 0.9f, 1.03f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 0.9f, 1.03f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 0.9f, 1.03f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 0.9f, 1.03f)
                        );
                        set.setDuration((long) (5f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else if (i == 4) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1.03f, 0.98f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1.03f, 0.98f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1.03f, 0.98f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1.03f, 0.98f)
                        );
                        set.setDuration((long) (5f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 0.98f, 1f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 0.98f, 1f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 0.98f, 1f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 0.98f, 1f)
                        );

                        set.setDuration((long) (4f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_IN);
                    }
                    set.setStartDelay(delay);
                    delay += set.getDuration();
                    bounceIconAnimator.playTogether(set);
                }
            } else {
                for (int i = 0; i < 5; i++) {
                    AnimatorSet set = new AnimatorSet();
                    if (i == 0) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1f, 0.9f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1f, 0.9f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1f, 0.9f)
                        );
                        set.setDuration((long) (7f / 36f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                    } else if (i == 1) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 0.9f, 1.06f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 0.9f, 1.06f)
                        );
                        set.setDuration((long) (8f / 36f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else if (i == 2) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1.06f, 0.92f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1.06f, 0.92f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1.06f, 0.92f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1.06f, 0.92f)
                        );
                        set.setDuration((long) (7f / 36f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else if (i == 3) {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 0.92f, 1.02f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 0.92f, 1.02f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 0.92f, 1.02f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 0.92f, 1.02f)
                        );
                        set.setDuration((long) (9f / 36f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    } else {
                        set.playTogether(
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_X, 1.02f, 1f),
                                ObjectAnimator.ofFloat(floatingButton, View.SCALE_Y, 1.02f, 1f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_X, 1.02f, 1f),
                                ObjectAnimator.ofFloat(previousFab, View.SCALE_Y, 1.02f, 1f)
                        );
                        set.setDuration((long) (5f / 47f * totalDuration));
                        set.setInterpolator(CubicBezierInterpolator.EASE_IN);
                    }
                    set.setStartDelay(delay);
                    delay += set.getDuration();
                    bounceIconAnimator.playTogether(set);
                }
            }
            bounceIconAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    floatingButton.setScaleX(1f);
                    floatingButton.setScaleY(1f);
                    previousFab.setScaleX(1f);
                    previousFab.setScaleY(1f);
                    bounceIconAnimator = null;
                    getNotificationCenter().onAnimationFinish(animationIndex);
                }
            });
            bounceIconAnimator.start();
        }, 50);
        return animatorSet;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (listView != null) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof UserCell) {
                        ((UserCell) child).update(0);
                    } else if (child instanceof ProfileSearchCell) {
                        ((ProfileSearchCell) child).update(0);
                    }
                }
            }
        };

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SECTIONS, new Class[]{LetterSectionCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_FASTSCROLL, null, null, null, null, Theme.key_fastScrollActive));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_FASTSCROLL, null, null, null, null, Theme.key_fastScrollInactive));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_FASTSCROLL, null, null, null, null, Theme.key_fastScrollText));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"statusColor"}, null, null, cellDelegate, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"statusOnlineColor"}, null, null, cellDelegate, Theme.key_windowBackgroundWhiteBlueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, null, Theme.avatarDrawables, null, Theme.key_avatar_text));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundRed));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundOrange));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundViolet));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundGreen));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundCyan));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundBlue));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_avatar_backgroundPink));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayIcon));

        themeDescriptions.add(new ThemeDescription(floatingButton, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_chats_actionIcon));
        themeDescriptions.add(new ThemeDescription(floatingButton, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_chats_actionBackground));
        themeDescriptions.add(new ThemeDescription(floatingButton, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null, null, Theme.key_chats_actionPressedBackground));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{GraySectionCell.class}, new String[]{"textView"}, null, null, null, Theme.key_graySectionText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{GraySectionCell.class}, null, null, null, Theme.key_graySection));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_groupDrawable, Theme.dialogs_broadcastDrawable, Theme.dialogs_botDrawable}, null, Theme.key_chats_nameIcon));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_verifiedCheckDrawable}, null, Theme.key_chats_verifiedCheck));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_verifiedDrawable}, null, Theme.key_chats_verifiedBackground));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, Theme.dialogs_offlinePaint, null, null, Theme.key_windowBackgroundWhiteGrayText3));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, Theme.dialogs_onlinePaint, null, null, Theme.key_windowBackgroundWhiteBlueText3));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, null, new Paint[]{Theme.dialogs_namePaint[0], Theme.dialogs_namePaint[1], Theme.dialogs_searchNamePaint}, null, null, Theme.key_chats_name));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{ProfileSearchCell.class}, null, new Paint[]{Theme.dialogs_nameEncryptedPaint[0], Theme.dialogs_nameEncryptedPaint[1], Theme.dialogs_searchNameEncryptedPaint}, null, null, Theme.key_chats_secretName));

        return themeDescriptions;
    }
}