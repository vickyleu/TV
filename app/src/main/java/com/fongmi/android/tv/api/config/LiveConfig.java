package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();
    private static final String MERGED_LIVE_NAME = "全部直播";
    public static final String MERGED_GROUP_SEPARATOR = " / ";

    private Live home;
    private List<Live> lives;
    private List<Rule> rules;
    private List<String> ads;

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasUrl() {
        return !TextUtils.isEmpty(getUrl());
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        return config(Config.live());
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        ads = null;
        home = null;
        lives = null;
        rules = null;
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            super.ensureLoaded();
            if (prepareMergedLive(getHome())) return;
            LiveApi.parse(getHome());
            LiveApi.parseXml(getHome());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        setHome(config, live, false);
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, LIVE));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
    }

    public void parse(JsonObject object) {
        parseConfig(getConfig(), object);
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        List<Live> parsedLives = Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new));
        Map<String, Live> storedLives = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        parsedLives.forEach(live -> live.sync(storedLives.get(live.getName())));
        setLives(addMergedLive(parsedLives, storedLives));
        setHome(config, chooseHome(config), false);
    }

    private List<Live> addMergedLive(List<Live> sourceLives, Map<String, Live> storedLives) {
        if (sourceLives.size() <= 1) return sourceLives;
        List<Live> items = new ArrayList<>();
        items.add(new Live(MERGED_LIVE_NAME, "").sync(storedLives.get(MERGED_LIVE_NAME)));
        items.addAll(sourceLives);
        return items;
    }

    private Live chooseHome(Config config) {
        Live merged = getLive(MERGED_LIVE_NAME);
        if (!merged.isEmpty()) return merged;
        return getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0));
    }

    public boolean isMergedLive(Live live) {
        return live != null && MERGED_LIVE_NAME.equals(live.getName());
    }

    private List<Live> getSourceLives() {
        return getLives().stream().filter(live -> !live.isEmpty() && !isMergedLive(live)).collect(Collectors.toList());
    }

    public boolean prepareMergedLive(Live live) {
        return prepareMergedLive(live, null);
    }

    public boolean prepareMergedLive(Live live, Runnable onProgress) {
        if (!isMergedLive(live)) return false;
        loadMergedLive(live, onProgress);
        return true;
    }

    private void loadMergedLive(Live merged, Runnable onProgress) {
        merged.getGroups().clear();
        for (Live live : getSourceLives()) {
            try {
                LiveApi.parse(live);
                mergeLive(merged, live);
                publishMergedLive(merged, onProgress);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        publishMergedLive(merged, onProgress);
    }

    private void publishMergedLive(Live merged, Runnable onProgress) {
        merged.getGroups().removeIf(group -> group.isKeep() || group.isEmpty());
        if (merged.getGroups().isEmpty()) return;
        merged.getGroups().add(0, Group.create(R.string.keep));
        applyKeepsToGroups(merged.getGroups());
        if (onProgress != null) onProgress.run();
    }

    private void mergeLive(Live merged, Live source) {
        for (Group group : source.getGroups()) {
            if (group.isKeep() || group.isEmpty()) continue;
            String region = regionName(source.getName());
            Group target = merged.find(Group.create(leafGroupName(region, source, group), false));
            if (!group.getPass().isEmpty()) target.setPass(group.getPass());
            group.getChannel().forEach(channel -> target.add(copyForMerged(channel, source.getName())));
        }
    }

    private Channel copyForMerged(Channel channel, String sourceName) {
        Channel item = Channel.create(channel);
        List<String> urls = new ArrayList<>();
        for (String url : channel.getUrls()) urls.add(labelUrl(url, sourceName));
        item.setUrls(urls);
        item.setIndex(0);
        return item;
    }

    private String labelUrl(String url, String sourceName) {
        return url.contains("$") ? url : url + "$" + sourceName;
    }

    private String leafGroupName(String region, Live live, Group group) {
        String name = subGroupName(region, live, group);
        return region + MERGED_GROUP_SEPARATOR + (name.isEmpty() ? live.getName() : name);
    }

    private String subGroupName(String region, Live live, Group group) {
        String name = group.getName();
        if (name.isEmpty() || name.equals(live.getName())) return "";
        if (name.equals(region) || name.contains(region)) return "";
        if (region.equals(live.getName())) return name;
        return name;
    }

    private String regionName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.contains("港澳台")) return "港澳台";
        if (name.contains("香港") || lower.contains("hong") || lower.contains("hk")) return "香港";
        if (name.contains("台湾") || name.contains("臺灣") || lower.contains("taiwan") || lower.contains("tw")) return "台湾";
        if (name.contains("大陆") || name.contains("中国") || name.contains("中國") || lower.contains("migu") || lower.contains("ipv6")) return "大陆";
        if (name.contains("国际") || lower.contains("global") || lower.contains("yuechan")) return "国际";
        return name;
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = Keep.getLive().stream().map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{1, 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        int num = Integer.parseInt(number);
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(num);
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        return lives == null ? lives = new ArrayList<>() : lives;
    }

    private void setLives(List<Live> lives) {
        this.lives = lives;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    public Live getLive(String key) {
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElse(new Live());
    }

    private void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setActivated(true);
        config.setHome(home.getName());
        if (save) config.save();
        getLives().forEach(item -> item.setActivated(home));
        if (!save && (home.isBoot() || Setting.isBootLive())) ConfigEvent.boot();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
