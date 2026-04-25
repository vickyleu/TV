package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LiveViewModel extends ViewModel {

    private final MutableLiveData<Boolean> xml;
    private final MutableLiveData<Result> url;
    private final MutableLiveData<Live> live;
    private final MutableLiveData<Epg> epg;

    private final Map<TaskType, ListenableFuture<?>> futures;
    private final Map<TaskType, AtomicInteger> taskIds;
    private volatile ZoneId zoneId;

    public LiveViewModel() {
        this.epg = new MutableLiveData<>();
        this.xml = new MutableLiveData<>();
        this.url = new MutableLiveData<>();
        this.live = new MutableLiveData<>();
        this.zoneId = ZoneId.systemDefault();
        this.futures = new EnumMap<>(TaskType.class);
        this.taskIds = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) taskIds.put(type, new AtomicInteger(0));
    }

    public LiveData<Result> url() {
        return url;
    }

    public LiveData<Boolean> xml() {
        return xml;
    }

    public LiveData<Epg> epg() {
        return epg;
    }

    public LiveData<Live> live() {
        return live;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void parse(Live item) {
        if (LiveConfig.get().isMergedLive(item)) {
            parseMergedLive(item);
            return;
        }
        execute(TaskType.LIVE, () -> {
            LiveApi.parse(item);
            setTimeZone(item);
            return item;
        }, live::postValue, error -> {
            if (error instanceof ExtractException) url.postValue(Result.error(error.getMessage()));
            else live.postValue(new Live());
        });
    }

    private void parseMergedLive(Live item) {
        AtomicInteger taskId = taskIds.get(TaskType.LIVE);
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(TaskType.LIVE);
        if (old != null) old.cancel(true);
        long[] lastPostTime = {0};
        int[] lastPostSize = {0};
        FluentFuture<Live> future = FluentFuture.from(Task.executor().submit(() -> {
            LiveConfig.get().prepareMergedLive(item, () -> {
                setTimeZone(item);
                long now = System.currentTimeMillis();
                int size = item.getGroups().size();
                boolean first = lastPostSize[0] == 0;
                boolean changed = size != lastPostSize[0];
                if (!first && (!changed || now - lastPostTime[0] < 1200)) return;
                lastPostSize[0] = size;
                lastPostTime[0] = now;
                if (taskId.get() == currentId) live.postValue(snapshotLive(item));
            });
            setTimeZone(item);
            return snapshotLive(item);
        })).withTimeout(Math.max(Constant.TIMEOUT_LIVE, TimeUnit.SECONDS.toMillis(90)), TimeUnit.MILLISECONDS, Task.scheduler());
        futures.put(TaskType.LIVE, future);
        future.addCallback(Task.callback(
                result -> {
                    if (taskId.get() == currentId) live.postValue(result);
                },
                error -> {
                    if (error instanceof CancellationException) return;
                    if (taskId.get() != currentId) return;
                    if (item.getGroups().isEmpty()) live.postValue(new Live());
                }
        ), MoreExecutors.directExecutor());
    }

    private Live snapshotLive(Live item) {
        Live copy = new Live(item.getName(), item.getUrl());
        copy.setActivated(item.isActivated());
        copy.setWidth(item.getWidth());
        for (Group group : item.getGroups()) copy.getGroups().add(snapshotGroup(group));
        return copy;
    }

    private Group snapshotGroup(Group group) {
        Group copy = Group.create(group.getName(), false);
        copy.setPass(group.getPass());
        copy.setPosition(group.getPosition());
        group.getChannel().forEach(channel -> copy.getChannel().add(snapshotChannel(channel)));
        return copy;
    }

    private Channel snapshotChannel(Channel channel) {
        Channel copy = Channel.create(channel);
        copy.setUrls(new ArrayList<>(channel.getUrls()));
        copy.setIndex(channel.getIndex());
        return copy;
    }

    public void parseXml(Live item) {
        execute(TaskType.XML, () -> LiveApi.parseXml(item), xml::postValue, error -> xml.postValue(false));
    }

    public void getEpg(Channel item) {
        execute(TaskType.EPG, () -> LiveApi.getEpg(item, zoneId), epg::postValue, error -> epg.postValue(new Epg()));
    }

    public void getUrl(Channel item) {
        execute(TaskType.URL, () -> LiveApi.getUrl(item), url::postValue, this::handleUrlError);
    }

    public void getUrl(Channel item, EpgData data) {
        execute(TaskType.URL, () -> LiveApi.getUrl(item, data), url::postValue, this::handleUrlError);
    }

    private void handleUrlError(Throwable t) {
        if (t instanceof ExtractException) url.postValue(Result.error(t.getMessage()));
        else url.postValue(new Result());
    }

    private void setTimeZone(Live live) {
        try {
            this.zoneId = live.getTimeZone().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(live.getTimeZone());
        } catch (Exception ignored) {
        }
    }

    private <T> void execute(TaskType type, Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        AtomicInteger taskId = taskIds.get(type);
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        FluentFuture<T> future = FluentFuture.from(Task.executor().submit(callable)).withTimeout(type.timeout, TimeUnit.MILLISECONDS, Task.scheduler());
        futures.put(type, future);
        future.addCallback(Task.callback(
                result -> {
                    if (taskId.get() == currentId) onSuccess.accept(result);
                },
                error -> {
                    if (error instanceof CancellationException) return;
                    if (taskId.get() != currentId) return;
                    onError.accept(error);
                }
        ), MoreExecutors.directExecutor());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        futures.values().forEach(future -> future.cancel(true));
    }

    private enum TaskType {

        LIVE(Constant.TIMEOUT_LIVE),
        EPG(Constant.TIMEOUT_EPG),
        XML(Constant.TIMEOUT_XML),
        URL(Constant.TIMEOUT_PARSE_LIVE);

        final long timeout;

        TaskType(long timeout) {
            this.timeout = timeout;
        }
    }
}
