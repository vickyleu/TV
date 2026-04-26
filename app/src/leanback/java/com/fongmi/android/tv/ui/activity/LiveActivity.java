package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.ActivityLiveBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigCallback;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.impl.LiveCallback;
import com.fongmi.android.tv.impl.PassCallback;
import com.fongmi.android.tv.model.LiveViewModel;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.route.RouteReporter;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.ChannelAdapter;
import com.fongmi.android.tv.ui.adapter.EpgDataAdapter;
import com.fongmi.android.tv.ui.adapter.GroupAdapter;
import com.fongmi.android.tv.ui.base.PlaybackActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
import com.fongmi.android.tv.ui.custom.CustomLiveListView;
import com.fongmi.android.tv.ui.custom.CustomSeekView;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.PassDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class LiveActivity extends PlaybackActivity implements GroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, EpgDataAdapter.OnClickListener, CustomKeyDownLive.Listener, CustomLiveListView.Callback, TrackDialog.Listener, PassCallback, ConfigCallback, LiveCallback {

    private static final long LIVE_BUFFER_FAILOVER_TIMEOUT = 7000;

    private ActivityLiveBinding mBinding;
    private ChannelAdapter mChannelAdapter;
    private EpgDataAdapter mEpgDataAdapter;
    private GroupAdapter mGroupAdapter;
    private GroupAdapter mSubGroupAdapter;
    private Observer<Result> mObserveUrl;
    private CustomKeyDownLive mKeyDown;
    private Observer<Epg> mObserveEpg;
    private LiveViewModel mViewModel;
    private List<Group> mHides;
    private List<Group> mLeafGroups;
    private Map<String, List<Group>> mGroupTree;
    private Map<String, Integer> mGroupWidthCache;
    private String mPlaybackKey;
    private String mFailoverKey;
    private Channel mChannel;
    private View mOldGroupView;
    private View mOldSubGroupView;
    private Group mRegion;
    private Group mGroup;
    private Runnable mR0;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Runnable mR5;
    private Clock mClock;
    private View mFocus2;
    private int mChannelFailoverCount;
    private int mFailoverCount;
    private int count;
    private boolean mHierarchy;

    public static void start(Context context) {
        context.startActivity(new Intent(context, LiveActivity.class).putExtra("empty", LiveConfig.isEmpty()));
    }

    private boolean isEmpty() {
        return getIntent().getBooleanExtra("empty", true);
    }

    private Group getKeep() {
        return mGroupAdapter.get(0);
    }

    private Live getHome() {
        return LiveConfig.get().getHome();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLiveBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected String getPlaybackKey() {
        return mPlaybackKey;
    }

    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }

    @Override
    protected CustomSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mBinding.control.action.speed.setText(player().getSpeedText());
        mBinding.control.action.decode.setText(player().getDecodeText());
        checkLive();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownLive.create(this);
        mObserveEpg = this::setEpg;
        mObserveUrl = this::start;
        mHides = new ArrayList<>();
        mLeafGroups = new ArrayList<>();
        mGroupTree = new LinkedHashMap<>();
        mGroupWidthCache = new LinkedHashMap<>();
        mR0 = this::setActivated;
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::hideInfo;
        mR4 = this::hideUI;
        mR5 = this::onBufferTimeout;
        setRecyclerView();
        setVideoView();
        setViewModel();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.group.setListener(this);
        mBinding.subgroup.setListener(this);
        mBinding.channel.setListener(this);
        mBinding.epgData.setListener(this);
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.text.setUpListener(this::onSubtitleClick);
        mBinding.control.action.text.setDownListener(this::onSubtitleClick);
        mBinding.control.action.home.setOnClickListener(view -> onHome());
        mBinding.control.action.line.setOnClickListener(view -> onLine());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.config.setOnClickListener(view -> onConfig());
        mBinding.control.action.action.setOnClickListener(view -> onAction());
        mBinding.control.action.invert.setOnClickListener(view -> onInvert());
        mBinding.control.action.across.setOnClickListener(view -> onAcross());
        mBinding.control.action.change.setOnClickListener(view -> onChange());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.group.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mGroupAdapter.getItemCount() > 0) onRegionSelected(child, mGroupAdapter.get(position));
            }
        });
        mBinding.subgroup.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mSubGroupAdapter.getItemCount() > 0) onSubGroupSelected(child, mSubGroupAdapter.get(position));
            }
        });
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.epgData.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new GroupAdapter(this));
        mBinding.subgroup.setAdapter(mSubGroupAdapter = new GroupAdapter(this, this::getSubGroupName));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
        mBinding.epgData.setAdapter(mEpgDataAdapter = new EpgDataAdapter(this));
    }

    private void setVideoView() {
        setScale(Setting.getLiveScale());
        findViewById(R.id.timeBar).setNextFocusUpId(R.id.config);
        mBinding.control.action.invert.setActivated(Setting.isInvert());
        mBinding.control.action.across.setActivated(Setting.isAcross());
        mBinding.control.action.change.setActivated(Setting.isChange());
    }

    private void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    private void setScale(int scale) {
        Setting.putLiveScale(scale);
        mBinding.exo.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(LiveViewModel.class);
        mViewModel.url().observeForever(mObserveUrl);
        mViewModel.xml().observe(this, this::setEpg);
        mViewModel.epg().observeForever(mObserveEpg);
        mViewModel.live().observe(this, live -> {
            mViewModel.parseXml(live);
            setGroup(live);
        });
    }

    private void checkLive() {
        if (isEmpty()) {
            LiveConfig.get().init().load(getCallback());
        } else {
            getLive();
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                getLive();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void getLive() {
        mBinding.control.action.home.setText(LiveConfig.isOnly() ? getString(R.string.live_refresh) : getHome().getName());
        mViewModel.parse(getHome());
        showProgress();
    }

    private void setGroup(Live live) {
        mHierarchy = LiveConfig.get().isMergedLive(live);
        mHides.clear();
        mLeafGroups.clear();
        mGroupTree.clear();
        if (!mHierarchy) {
            mSubGroupAdapter.clear();
            mBinding.subgroup.setVisibility(View.GONE);
        }
        List<Group> items = mHierarchy ? getTreeGroups(live) : getFlatGroups(live);
        mGroupAdapter.addAll(items);
        setWidth(live);
        if (restoreGroupPosition()) return;
        setPosition(LiveConfig.get().findKeepPosition(mHierarchy ? mLeafGroups : items));
    }

    private List<Group> getFlatGroups(Live live) {
        List<Group> items = new ArrayList<>();
        for (Group group : live.getGroups()) {
            if (group.isHidden()) mHides.add(group);
            else items.add(group);
        }
        return items;
    }

    private List<Group> getTreeGroups(Live live) {
        List<Group> items = new ArrayList<>();
        for (Group group : live.getGroups()) {
            if (group.isHidden()) {
                mHides.add(group);
            } else if (group.isKeep() || !isTreeLeaf(group)) {
                items.add(group);
                mLeafGroups.add(group);
            } else {
                String region = getRegionName(group);
                if (!mGroupTree.containsKey(region)) {
                    mGroupTree.put(region, new ArrayList<>());
                    items.add(Group.create(region, false));
                }
                mGroupTree.get(region).add(group);
                mLeafGroups.add(group);
            }
        }
        return items;
    }

    private boolean restoreGroupPosition() {
        if (mChannel == null || mGroup == null) return false;
        if (mHierarchy) {
            Group group = findLeafGroup(mGroup);
            if (group == null) return false;
            group.setPosition(mGroup.getPosition());
            showLeafGroup(group);
            return true;
        }
        int groupPosition = mGroupAdapter.indexOf(mGroup);
        if (groupPosition < 0) return false;
        mBinding.group.setSelectedPosition(groupPosition);
        mChannelAdapter.addAll(setWidth(mGroup).getChannel());
        int channelPosition = Math.max(mGroup.getPosition(), 0);
        if (channelPosition < mChannelAdapter.getItemCount()) mBinding.channel.setSelectedPosition(channelPosition);
        return true;
    }

    private void setWidth(Live live) {
        if (mHierarchy) {
            setListWidth(mBinding.group, mGroupAdapter.unmodifiableList(), Group::getName);
            setSubGroupWidth();
            return;
        }
        int padding = ResUtil.dp2px(52);
        if (live.getWidth() == 0) for (Group item : live.getGroups()) live.setWidth(Math.max(live.getWidth(), ResUtil.getTextWidth(item.getName(), 16)));
        int width = live.getWidth() == 0 ? 0 : Math.min(live.getWidth() + padding, ResUtil.getScreenWidth() / 4);
        setWidth(mBinding.group, width);
        setWidth(mBinding.subgroup, 0);
    }

    private Group setWidth(Group group) {
        int padding = ResUtil.dp2px(64);
        if (group.isKeep()) group.setWidth(0);
        if (group.getWidth() == 0 && mGroupWidthCache.containsKey(group.getName())) group.setWidth(mGroupWidthCache.get(group.getName()));
        if (group.getWidth() == 0) for (Channel item : group.getChannel()) group.setWidth(Math.max(group.getWidth(), ResUtil.getTextWidth(item.getNumber() + item.getName(), 16)));
        if (group.getWidth() != 0) mGroupWidthCache.put(group.getName(), group.getWidth());
        int width = group.getWidth() == 0 ? 0 : Math.min(group.getWidth() + padding, ResUtil.getScreenWidth() / 2);
        setWidth(mBinding.channel, width);
        return group;
    }

    private void setSubGroupWidth() {
        setListWidth(mBinding.subgroup, mSubGroupAdapter.unmodifiableList(), this::getSubGroupName);
    }

    private void setListWidth(View view, List<Group> groups, Function<Group, String> display) {
        int padding = ResUtil.dp2px(52);
        int width = 0;
        for (Group item : groups) width = Math.max(width, ResUtil.getTextWidth(display.apply(item), 16));
        setWidth(view, width == 0 ? 0 : Math.min(width + padding, ResUtil.getScreenWidth() / 4));
    }

    private void setWidth(Epg epg) {
        int padding = ResUtil.dp2px(52);
        if (epg.getList().isEmpty()) return;
        int minWidth = ResUtil.getTextWidth(epg.getList().get(0).getTime(), 14);
        if (epg.getWidth() == 0) for (EpgData item : epg.getList()) epg.setWidth(Math.max(epg.getWidth(), ResUtil.getTextWidth(item.getTitle(), 16)));
        int width = epg.getWidth() == 0 ? 0 : Math.min(Math.max(epg.getWidth(), minWidth) + padding, ResUtil.getScreenWidth() / 2);
        setWidth(mBinding.epgData, width);
    }

    private void setWidth(View view, int width) {
        view.post(() -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width == width) return;
            params.width = width;
            view.setLayoutParams(params);
        });
    }

    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        if (mHierarchy) {
            setLeafPosition(position[0], position[1], true);
            return;
        }
        int size = mGroupAdapter.getItemCount();
        if (size == 1 || position[0] >= size) return;
        mGroup = mGroupAdapter.get(position[0]);
        mBinding.group.setSelectedPosition(position[0]);
        mGroup.setPosition(position[1]);
        onItemClick(mGroup);
        onItemClick(mGroup.current());
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        if (mHierarchy) {
            Group group = findLeafGroup(mGroup);
            if (group != null) showLeafGroup(group);
            return;
        }
        int position = mGroupAdapter.indexOf(mGroup);
        boolean change = mBinding.group.getSelectedPosition() != position;
        if (change) mBinding.group.setSelectedPosition(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        mBinding.channel.setSelectedPosition(mGroup.getPosition());
    }

    private void setLeafPosition(int groupPosition, int channelPosition, boolean play) {
        if (groupPosition < 0 || groupPosition >= mLeafGroups.size()) return;
        Group group = mLeafGroups.get(groupPosition);
        if (group.getChannel().isEmpty()) return;
        group.setPosition(Math.min(Math.max(channelPosition, 0), group.getChannel().size() - 1));
        showLeafGroup(group);
        if (play) onItemClick(group.current());
    }

    private void showLeafGroup(Group group) {
        mGroup = group;
        if (isTreeLeaf(group)) {
            setRegion(getRegionName(group), false);
            int position = mSubGroupAdapter.indexOf(group);
            if (position >= 0 && mBinding.subgroup.getSelectedPosition() != position) mBinding.subgroup.setSelectedPosition(position);
        } else {
            mRegion = group;
            mBinding.subgroup.setVisibility(View.GONE);
            setWidth(mBinding.subgroup, 0);
            int position = mGroupAdapter.indexOf(group);
            if (position >= 0 && mBinding.group.getSelectedPosition() != position) mBinding.group.setSelectedPosition(position);
        }
        mChannelAdapter.addAll(setWidth(group).getChannel());
        int position = Math.max(group.getPosition(), 0);
        if (position < mChannelAdapter.getItemCount()) mBinding.channel.setSelectedPosition(position);
    }

    private void setRegion(String region, boolean pickFirst) {
        Group item = findTopGroup(region);
        if (item == null) return;
        mRegion = item;
        int position = mGroupAdapter.indexOf(item);
        if (position >= 0 && mBinding.group.getSelectedPosition() != position) mBinding.group.setSelectedPosition(position);
        List<Group> groups = mGroupTree.get(region);
        if (groups == null || groups.isEmpty()) return;
        mBinding.subgroup.setVisibility(View.VISIBLE);
        mSubGroupAdapter.addAll(groups);
        setSubGroupWidth();
        if (pickFirst) showLeafGroup(groups.get(0));
    }

    private void onRegionSelected(@Nullable RecyclerView.ViewHolder child, Group group) {
        mOldGroupView = selectChild(mOldGroupView, child);
        if (mHierarchy && mGroupTree.containsKey(group.getName())) {
            boolean sameRegion = mRegion != null && mRegion.getName().equals(group.getName());
            setRegion(group.getName(), !sameRegion);
        }
        else onItemClick(group);
        resetPass();
    }

    private void onSubGroupSelected(@Nullable RecyclerView.ViewHolder child, Group group) {
        mOldSubGroupView = selectChild(mOldSubGroupView, child);
        if (mGroup != null && mGroup.getName().equals(group.getName())) return;
        onItemClick(group);
        resetPass();
    }

    private View selectChild(View old, @Nullable RecyclerView.ViewHolder child) {
        if (old != null) old.setSelected(false);
        View view = child == null ? null : child.itemView;
        if (view != null) view.setSelected(true);
        return view;
    }

    private boolean isTreeLeaf(Group group) {
        return group != null && group.getName().contains(LiveConfig.MERGED_GROUP_SEPARATOR);
    }

    private String getRegionName(Group group) {
        String[] split = group.getName().split(LiveConfig.MERGED_GROUP_SEPARATOR, 2);
        return split.length > 0 ? split[0] : group.getName();
    }

    private String getSubGroupName(Group group) {
        String[] split = group.getName().split(LiveConfig.MERGED_GROUP_SEPARATOR, 2);
        return split.length > 1 ? split[1] : group.getName();
    }

    private Group findLeafGroup(Group group) {
        if (group == null) return null;
        for (Group item : mLeafGroups) if (item.getName().equals(group.getName())) return item;
        return null;
    }

    private Group findTopGroup(String name) {
        for (Group item : mGroupAdapter.unmodifiableList()) if (item.getName().equals(name)) return item;
        return null;
    }

    private void setActivated() {
        mChannelAdapter.setSelected(mChannel);
        notifyItemChanged(mBinding.channel, mChannelAdapter);
        fetch();
    }

    private void setActivated(EpgData item) {
        mEpgDataAdapter.setSelected(item);
        notifyItemChanged(mBinding.epgData, mEpgDataAdapter);
    }

    private void checkPlay() {
        if (player().isPlaying()) onPaused();
        else onPlay();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onHome() {
        if (LiveConfig.isOnly()) setLive(getHome());
        else LiveDialog.create(this).show();
        hideControl();
    }

    private void onLine() {
        nextLine(false);
    }

    private void onScale() {
        int index = Setting.getLiveScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(player().addSpeed());
    }

    private void onSpeedAdd() {
        mBinding.control.action.speed.setText(player().addSpeed(0.25f));
    }

    private void onSpeedSub() {
        mBinding.control.action.speed.setText(player().subSpeed(0.25f));
    }

    private boolean onSpeedLong() {
        mBinding.control.action.speed.setText(player().toggleSpeed());
        return true;
    }

    private void onConfig() {
        HistoryDialog.create(this).readOnly().type(1).show();
        hideControl();
    }

    private void onAction() {
        checkPlay();
    }

    private void onInvert() {
        Setting.putInvert(!Setting.isInvert());
        mBinding.control.action.invert.setActivated(Setting.isInvert());
    }

    private void onAcross() {
        Setting.putAcross(!Setting.isAcross());
        mBinding.control.action.across.setActivated(Setting.isAcross());
    }

    private void onChange() {
        Setting.putChange(!Setting.isChange());
        mBinding.control.action.change.setActivated(Setting.isChange());
    }

    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.widget.title.getText());
        setRedirect(true);
    }

    private void onDecode() {
        player().toggleDecode();
        setDecode();
    }

    private void hideUI() {
        App.removeCallbacks(mR4);
        if (isGone(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.GONE);
        setPosition();
    }

    private void showUI() {
        if (isVisible(mBinding.recycler) || mGroupAdapter.getItemCount() == 0) return;
        mBinding.recycler.setVisibility(View.VISIBLE);
        setPosition();
        setUITimer();
        hideEpg();
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            nextChannel();
        }

        @Override
        public void onPrev() {
            prevChannel();
        }

        @Override
        public void onStop() {
            finish();
        }
    };

    @Override
    protected void onPrepare() {
        setDecode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onError(String msg) {
        cancelBufferTimeout();
        Track.delete(player().getKey());
        player().resetTrack();
        player().reset();
        player().stop();
        if (autoSwitchLineOnError()) return;
        if (autoSwitchChannelOnError()) return;
        showError(msg);
        startFlow();
    }

    @Override
    protected void onReclaim() {
        Result result = mViewModel.url().getValue();
        if (result != null) start(result);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                scheduleBufferTimeout();
                break;
            case Player.STATE_READY:
                hideProgress();
                cancelBufferTimeout();
                player().reset();
                resetFailover();
                resetChannelFailover();
                break;
            case Player.STATE_ENDED:
                cancelBufferTimeout();
                checkEnded();
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            mBinding.control.action.action.setText(R.string.pause);
        } else if (isPaused()) {
            mBinding.control.action.action.setText(R.string.play);
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
    }

    @Override
    public void showEpg(Channel item) {
        if (mChannel == null || mChannel.getData(mViewModel.getZoneId()).getList().isEmpty() || mEpgDataAdapter.getItemCount() == 0 || !mChannel.equals(item) || !mChannel.getGroup().equals(mGroup)) return;
        mBinding.epgData.setSelectedPosition(mChannel.getData(mViewModel.getZoneId()).getSelected());
        mBinding.epgData.setVisibility(View.VISIBLE);
        mBinding.channel.setVisibility(View.GONE);
        mBinding.subgroup.setVisibility(View.GONE);
        mBinding.group.setVisibility(View.GONE);
        mBinding.epgData.requestFocus();
    }

    @Override
    public void hideEpg() {
        mBinding.channel.setVisibility(View.VISIBLE);
        mBinding.subgroup.setVisibility(mHierarchy && mRegion != null && mGroupTree.containsKey(mRegion.getName()) ? View.VISIBLE : View.GONE);
        mBinding.group.setVisibility(View.VISIBLE);
        mBinding.epgData.setVisibility(View.GONE);
        mBinding.channel.requestFocus();
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void scheduleBufferTimeout() {
        if (mChannel == null || service() == null || !player().isLive()) return;
        App.post(mR5, getBufferTimeout());
    }

    private void cancelBufferTimeout() {
        App.removeCallbacks(mR5);
    }

    private long getBufferTimeout() {
        long timeout = getHome().getTimeout();
        return mChannel.isOnly() ? timeout : Math.min(timeout, LIVE_BUFFER_FAILOVER_TIMEOUT);
    }

    private void onBufferTimeout() {
        if (mChannel == null || service() == null || !player().isLive()) return;
        if (player().getPlaybackState() != Player.STATE_BUFFERING || player().isPlaying()) return;
        onError(ResUtil.getString(R.string.error_play_timeout));
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        mBinding.widget.top.setVisibility(View.VISIBLE);
        App.post(view::requestFocus, 25);
        setR1Callback();
        hideInfo();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        mBinding.widget.top.setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showInfo() {
        mBinding.widget.bottom.setVisibility(View.VISIBLE);
        setR3Callback();
        setInfo();
    }

    private void hideInfo() {
        mBinding.widget.bottom.setVisibility(View.GONE);
        App.removeCallbacks(mR3);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void setR3Callback() {
        App.post(mR3, Constant.INTERVAL_HIDE);
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else if (isVisible(mBinding.recycler)) hideUI();
        else showUI();
        hideInfo();
    }

    private void resetPass() {
        this.count = 0;
    }

    private void setArtwork() {
        ImgUtil.load(this, mChannel.getLogo(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.exo.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.exo.setDefaultArtwork(errorDrawable);
            }
        });
    }

    @Override
    public void onItemClick(Group item) {
        if (mHierarchy && mGroupTree.containsKey(item.getName())) {
            setRegion(item.getName(), true);
            return;
        }
        mGroup = item;
        mChannelAdapter.addAll(setWidth(item).getChannel());
        mBinding.channel.setSelectedPosition(Math.max(item.getPosition(), 0));
        if (!item.isKeep() || ++count < 5 || mHides.isEmpty()) return;
        PassDialog.create().show(this);
        App.removeCallbacks(mR4);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        if (!item.getData(mViewModel.getZoneId()).getList().isEmpty() && item.isSelected() && mChannel != null && mChannel.equals(item) && mChannel.getGroup().equals(mGroup)) {
            showEpg(item);
        } else if (mGroup != null) {
            resetChannelFailover();
            mGroup.setPosition(mBinding.channel.getSelectedPosition());
            setChannel(item.group(mGroup));
            hideUI();
        }
    }

    @Override
    public boolean onLongClick(Channel item) {
        if (mGroup.isHidden()) return false;
        boolean exist = Keep.exist(item.getName());
        Notify.show(exist ? R.string.keep_del : R.string.keep_add);
        if (exist) delKeep(item);
        else addKeep(item);
        return true;
    }

    @Override
    public void onItemClick(EpgData item) {
        if (item.isSelected()) {
            fetch(item);
        } else if (mChannel.hasCatchup() || mChannel.isRtsp()) {
            mBinding.widget.title.setText(getString(R.string.detail_title, mChannel.getShow(), item.getTitle()));
            Notify.show(getString(R.string.play_ready, item.getTitle()));
            setActivated(item);
            fetch(item);
        }
    }

    private void addKeep(Channel item) {
        getKeep().add(item);
        Keep keep = new Keep();
        keep.setKey(item.getName());
        keep.setType(1);
        keep.save();
    }

    private void delKeep(Channel item) {
        if (mGroup.isKeep()) mChannelAdapter.remove(item);
        if (mChannelAdapter.getItemCount() == 0) mBinding.group.requestFocus();
        getKeep().getChannel().remove(item);
        Keep.delete(item.getName());
    }

    private void setChannel(Channel item) {
        App.post(mR0, 100);
        resetFailover();
        mChannel = item;
        setArtwork();
        showInfo();
    }

    private void setInfo() {
        mViewModel.getEpg(mChannel);
        mBinding.widget.play.setText("");
        mBinding.widget.name.setMaxEms(48);
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.widget.title.setSelected(true);
        mBinding.widget.line.setText(mChannel.getLine());
        mBinding.widget.name.setText(mChannel.getShow());
        mBinding.widget.title.setText(mChannel.getShow());
        mBinding.control.action.line.setText(mChannel.getLine());
        mBinding.widget.number.setText(mChannel.getNumber());
        mBinding.widget.line.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setVisibility(mChannel.getLineVisible());
    }

    private void setEpg(Epg epg) {
        if (mChannel == null || !mChannel.getTvgId().equals(epg.getKey())) return;
        EpgData data = epg.getEpgData();
        boolean hasTitle = !data.getTitle().isEmpty();
        mEpgDataAdapter.addAll(epg.getList());
        if (hasTitle) mBinding.widget.title.setText(getString(R.string.detail_title, mChannel.getShow(), data.getTitle()));
        mBinding.widget.name.setMaxEms(hasTitle ? 12 : 48);
        mBinding.widget.play.setText(data.format());
        setWidth(epg);
        setMetadata();
    }

    private void setEpg(boolean success) {
        if (mChannel != null && success) mViewModel.getEpg(mChannel);
    }

    private void fetch(EpgData item) {
        if (mChannel == null) return;
        mViewModel.getUrl(mChannel, item);
        player().clear();
        player().stop();
        hideUI();
    }

    private void fetch() {
        if (mChannel == null) return;
        LiveConfig.get().setKeep(mChannel);
        mViewModel.getUrl(mChannel);
        player().clear();
        player().stop();
        showProgress();
    }

    private void start(Result result) {
        mPlaybackKey = result.getRealUrl();
        RouteReporter.reportLive(mPlaybackKey, mChannel, mGroup, getHome());
        startPlayer(mPlaybackKey, result, false, getHome().getTimeout(), buildMetadata());
    }

    private void resetAdapter() {
        mBinding.control.action.line.setVisibility(View.GONE);
        mBinding.widget.title.setText("");
        mEpgDataAdapter.clear();
        mChannelAdapter.clear();
        mSubGroupAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mLeafGroups.clear();
        mGroupTree.clear();
        mChannel = null;
        mRegion = null;
        mGroup = null;
        resetChannelFailover();
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(true).show(this);
        App.post(this::hideControl, 100);
    }

    @Override
    public void setConfig(Config config) {
        Config current = LiveConfig.get().getConfig();
        LiveConfig.load(config, getCallback(current));
    }

    private Callback getCallback(Config config) {
        return new Callback() {
            @Override
            public void start() {
                showProgress();
            }

            @Override
            public void success() {
                setLive(getHome());
            }

            @Override
            public void error(String msg) {
                LiveConfig.load(config, new Callback());
                Notify.show(msg);
                hideProgress();
            }
        };
    }

    @Override
    public void setLive(Live item) {
        if (item.isActivated()) item.getGroups().clear();
        LiveConfig.get().setHome(item);
        player().reset();
        player().clear();
        player().stop();
        resetAdapter();
        hideControl();
        getLive();
    }

    @Override
    public void setPass(String pass) {
        unlock(pass);
    }

    private void unlock(String pass) {
        boolean first = true;
        int position = mGroupAdapter.getItemCount();
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            if (mHierarchy && isTreeLeaf(item)) {
                addTreeGroup(item);
                if (first) showLeafGroup(item);
                iterator.remove();
                first = false;
                continue;
            }
            mGroupAdapter.add(mGroupAdapter.getItemCount(), item);
            if (first) mBinding.group.setSelectedPosition(position);
            if (first) onItemClick(mGroup = item);
            iterator.remove();
            first = false;
        }
    }

    private void addTreeGroup(Group item) {
        String region = getRegionName(item);
        if (!mGroupTree.containsKey(region)) {
            mGroupTree.put(region, new ArrayList<>());
            mGroupAdapter.add(mGroupAdapter.getItemCount(), Group.create(region, false));
            setListWidth(mBinding.group, mGroupAdapter.unmodifiableList(), Group::getName);
        }
        mGroupTree.get(region).add(item);
        mLeafGroups.add(item);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case LIVE:
                setLive(getHome());
                break;
            case PLAYER:
                fetch();
                break;
        }
    }

    private void checkEnded() {
        if (player().isLive()) {
            checkNext();
        } else {
            nextChannel();
        }
    }

    private void setTrackVisible() {
        mBinding.control.action.text.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.video.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.speed.setVisibility(player().isVod() ? View.VISIBLE : View.GONE);
    }

    private MediaMetadata buildMetadata() {
        String artist = mBinding.widget.play.getText().toString();
        return PlayerManager.buildMetadata(mChannel.getShow(), artist, mChannel.getLogo());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void resetFailover() {
        mFailoverKey = "";
        mFailoverCount = 0;
    }

    private void resetChannelFailover() {
        mChannelFailoverCount = 0;
    }

    private String getFailoverKey() {
        if (mChannel == null) return "";
        String group = mChannel.getGroup() == null ? "" : mChannel.getGroup().getName();
        return group + "/" + mChannel.getName();
    }

    private boolean autoSwitchLineOnError() {
        if (mChannel == null || mChannel.isOnly()) return false;
        String key = getFailoverKey();
        if (!key.equals(mFailoverKey)) {
            mFailoverKey = key;
            mFailoverCount = 0;
        }
        int max = Math.max(0, mChannel.getUrls().size() - 1);
        if (mFailoverCount >= max) return false;
        mFailoverCount++;
        nextLine(false);
        Notify.show(getString(R.string.play_switch_flag, mChannel.getLine()));
        return true;
    }

    private boolean autoSwitchChannelOnError() {
        if (mChannel == null || mGroup == null || !Setting.isChange()) return false;
        int max = Math.min(8, Math.max(0, mChannelAdapter.getItemCount() - 1));
        if (max == 0 || mChannelFailoverCount >= max) return false;
        mChannelFailoverCount++;
        nextChannel();
        Notify.show(getString(R.string.play_switch_channel, mChannel.getShow()));
        return true;
    }

    private void startFlow() {
        if (!Setting.isChange()) return;
        if (!mChannel.isLast()) nextLine(true);
    }

    private void prevChannel() {
        if (mGroup == null) return;
        int position = mGroup.getPosition() - 1;
        boolean limit = position < 0;
        if (Setting.isAcross() & limit) prevGroup();
        else mGroup.setPosition(limit ? mChannelAdapter.getItemCount() - 1 : position);
        if (!mGroup.isEmpty()) setChannel(mGroup.current());
    }

    private void nextChannel() {
        if (mGroup == null) return;
        int position = mGroup.getPosition() + 1;
        boolean limit = position > mChannelAdapter.getItemCount() - 1;
        if (Setting.isAcross() && limit) nextGroup();
        else mGroup.setPosition(limit ? 0 : position);
        if (!mGroup.isEmpty()) setChannel(mGroup.current());
    }

    private boolean nextGroup() {
        if (mHierarchy) return moveLeafGroup(1);
        int position = mBinding.group.getSelectedPosition() + 1;
        if (position > mGroupAdapter.getItemCount() - 1) position = 0;
        if (mGroup.equals(mGroupAdapter.get(position))) return false;
        mGroup = mGroupAdapter.get(position);
        mBinding.group.setSelectedPosition(position);
        if (mGroup.skip()) return nextGroup();
        mChannelAdapter.addAll(mGroup.getChannel());
        mGroup.setPosition(0);
        return true;
    }

    private boolean prevGroup() {
        if (mHierarchy) return moveLeafGroup(-1);
        int position = mBinding.group.getSelectedPosition() - 1;
        if (position < 0) position = mGroupAdapter.getItemCount() - 1;
        if (mGroup.equals(mGroupAdapter.get(position))) return false;
        mGroup = mGroupAdapter.get(position);
        mBinding.group.setSelectedPosition(position);
        if (mGroup.skip()) return prevGroup();
        mChannelAdapter.addAll(mGroup.getChannel());
        mGroup.setPosition(mGroup.getChannel().size() - 1);
        return true;
    }

    private boolean moveLeafGroup(int step) {
        if (mLeafGroups.isEmpty() || mGroup == null) return false;
        int position = indexOfLeafGroup(mGroup);
        if (position < 0) return false;
        for (int i = 0; i < mLeafGroups.size(); i++) {
            position = (position + step + mLeafGroups.size()) % mLeafGroups.size();
            Group group = mLeafGroups.get(position);
            if (group.skip() || group.isEmpty()) continue;
            group.setPosition(step > 0 ? 0 : group.getChannel().size() - 1);
            showLeafGroup(group);
            return true;
        }
        return false;
    }

    private int indexOfLeafGroup(Group group) {
        if (group == null) return -1;
        for (int i = 0; i < mLeafGroups.size(); i++) if (mLeafGroups.get(i).getName().equals(group.getName())) return i;
        return -1;
    }

    private void checkNext() {
        int current = mChannel.getData(mViewModel.getZoneId()).getInRange();
        int position = mChannel.getData(mViewModel.getZoneId()).getSelected() + 1;
        boolean hasNext = position <= current && position > 0;
        if (hasNext) onItemClick(mChannel.getData(mViewModel.getZoneId()).getList().get(position));
        else fetch();
    }

    private void prevLine() {
        if (mChannel == null || mChannel.isOnly()) return;
        mChannel.switchLine(false);
        showInfo();
        fetch();
    }

    private void nextLine(boolean show) {
        if (mChannel == null || mChannel.isOnly()) return;
        mChannel.switchLine(true);
        if (show) showInfo();
        else setInfo();
        fetch();
    }

    private void seek(long time) {
        mKeyDown.reset();
        seekTo(time);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        controller().play();
    }

    private View getFocus2() {
        return mFocus2 == null || mFocus2.getVisibility() != View.VISIBLE ? mBinding.control.action.config : mFocus2;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleListFocus(event)) return true;
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (isVisible(mBinding.control.getRoot())) mFocus2 = getCurrentFocus();
        if (mKeyDown.hasEvent(event) && service() != null) mKeyDown.onKeyDown(event);
        return super.dispatchKeyEvent(event);
    }

    private boolean handleListFocus(KeyEvent event) {
        if (!isVisible(mBinding.recycler) || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        View focus = getCurrentFocus();
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (isFocusInside(mBinding.channel, focus)) return requestListFocus(isVisible(mBinding.subgroup) ? mBinding.subgroup : mBinding.group);
            if (isFocusInside(mBinding.subgroup, focus)) return requestListFocus(mBinding.group);
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (isFocusInside(mBinding.group, focus)) return requestListFocus(isVisible(mBinding.subgroup) ? mBinding.subgroup : mBinding.channel);
            if (isFocusInside(mBinding.subgroup, focus)) return requestListFocus(mBinding.channel);
        }
        return false;
    }

    private boolean requestListFocus(CustomLiveListView view) {
        if (!isVisible(view) || view.getAdapter() == null || view.getAdapter().getItemCount() == 0) return false;
        int position = Math.max(view.getSelectedPosition(), 0);
        if (position >= view.getAdapter().getItemCount()) position = view.getAdapter().getItemCount() - 1;
        RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(position);
        if (holder != null) return holder.itemView.requestFocus();
        int target = position;
        view.setSelectedPosition(target);
        view.post(() -> {
            RecyclerView.ViewHolder child = view.findViewHolderForAdapterPosition(target);
            if (child != null) child.itemView.requestFocus();
        });
        return true;
    }

    private boolean isFocusInside(View root, View focus) {
        if (root == null || focus == null) return false;
        if (root == focus) return true;
        for (ViewParent parent = focus.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == root) return true;
        }
        return false;
    }

    @Override
    public void setUITimer() {
        App.post(mR4, Constant.INTERVAL_HIDE);
    }

    @Override
    public boolean dispatch(boolean check) {
        return !check || isGone(mBinding.recycler) && isGone(mBinding.control.getRoot());
    }

    @Override
    public void onShow(String number) {
        mBinding.widget.digital.setText(number);
        mBinding.widget.digital.setVisibility(View.VISIBLE);
    }

    @Override
    public void onFind(String number) {
        mBinding.widget.digital.setVisibility(View.GONE);
        setPosition(LiveConfig.get().findByChannelNumber(number, mHierarchy ? mLeafGroups : mGroupAdapter.unmodifiableList()));
    }

    @Override
    public void onSeeking(long time) {
        if (player().isLive()) return;
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onKeyUp() {
        if (Setting.isInvert()) nextChannel();
        else prevChannel();
    }

    @Override
    public void onKeyDown() {
        if (Setting.isInvert()) prevChannel();
        else nextChannel();
    }

    @Override
    public void onKeyLeft(long time) {
        if (player().isLive()) prevLine();
        else App.post(() -> seek(time), 250);
    }

    @Override
    public void onKeyRight(long time) {
        if (player().isLive()) nextLine(true);
        else App.post(() -> seek(time), 250);
    }

    @Override
    public void onKeyCenter() {
        hideInfo();
        showUI();
    }

    @Override
    public void onMenu() {
        showControl(getFocus2());
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        else if (isVisible(mBinding.control.getRoot())) hideControl();
        else onMenu();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Setting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.bottom)) {
            hideInfo();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else {
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mClock.release();
        Source.get().exit();
        App.removeCallbacks(mR0, mR1, mR2, mR3, mR4);
        mViewModel.url().removeObserver(mObserveUrl);
        mViewModel.epg().removeObserver(mObserveEpg);
        super.onDestroy();
    }
}
