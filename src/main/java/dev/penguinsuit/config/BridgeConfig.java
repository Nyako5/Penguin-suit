package dev.penguinsuit.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BridgeConfig {
    public static final String FILE_NAME = "penguin-suit.jsonc";
    public static final String LEGACY_FILE_NAME = "penguin-media-bridge.json";
    public static final String LEGACY_JSONC_FILE_NAME = "penguin-media-bridge.jsonc";
    public dev.penguinsuit.mention.FakePlayerFilter fakePlayerFilter = new dev.penguinsuit.mention.FakePlayerFilter();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public FeatureOptions.Forwarding forwarding = new FeatureOptions.Forwarding();
    public FeatureOptions.Mentions mentions = new FeatureOptions.Mentions();
    public FeatureOptions.Logs logs = new FeatureOptions.Logs();
    public FeatureOptions.Filters filters = new FeatureOptions.Filters();
    public FeatureOptions.Media media = new FeatureOptions.Media();
    public FeatureOptions.Events events = new FeatureOptions.Events();
    public FeatureOptions.Authentication authentication = new FeatureOptions.Authentication();
    public FeatureOptions.Network network = new FeatureOptions.Network();
    public FeatureOptions.Directory directory = new FeatureOptions.Directory();
    public FeatureOptions.Messages messages = new FeatureOptions.Messages();
    public boolean enabled = true;
    /** Known QQ system faces use the image channel; readable names remain when disabled. */
    public boolean qqEmojiImagesEnabled = true;
    public boolean repliesEnabled = true;
    public boolean replyDebugLog = true;
    public int replyRetentionMinutes = 10080;
    public int replyMaxEntries = 20000;
    public long replyMaxBytes = 32L * 1024 * 1024;
    public boolean authenticationEnabled = true;
    /** Full AppID/GroupOpenID/member_openid identities; never display names. */
    public List<String> qqBotExcludedMembers = List.of();
    private transient Path sourceFile;
    public boolean gameToQqEnabled = true;
    public boolean advancementEventsEnabled = true;
    public boolean deathEventsEnabled = true;
    public boolean gameEventsRespectGameRules = false;
    public boolean gameEventsChinese = true;
    public String deathEventFormat = "{message}";
    public int gameToQqQueueCapacity = 16;
    public long gameToQqMaxBytes = 8L * 1024 * 1024;
    public long gameToQqMaxPendingBytes = 32L * 1024 * 1024;
    public int gameToQqTimeoutSeconds = 120;
    public int gameToQqMaterialMaxImages = 90;
    public int gameToQqMaterialTtlMinutes = 1440;
    public int gameToQqMaterialGraceSeconds = 120;
    public int workerThreads = 2;
    public int queueCapacity = 32;
    public int maxImagesPerMessage = 5;
    public long maxDownloadBytes = 8L * 1024 * 1024;
    public int connectTimeoutMillis = 5000;
    public int downloadTimeoutMillis = 15000;
    public int maxRedirects = 3;
    public int maxDimension = 4096;
    public long maxPixels = 16_777_216;
    public int maxGifFrames = 240;
    public long maxGifDecodedPixels = 80_000_000;
    public long maxOwnedCacheBytes = 256L * 1024 * 1024;
    public int maxOwnedImages = 256;
    public int cacheTtlMinutes = 1440;
    public int cacheDeliveryGraceSeconds = 120;
    public int dedupSeconds = 120;
    public int minSenderIntervalMillis = 1000;
    public int minGroupIntervalMillis = 250;
    public List<String> allowedHostSuffixes = List.of("qq.com", "qq.com.cn", "qpic.cn");
    public List<String> blockedGroups = List.of();
    public List<String> blockedUsers = List.of();

    public static BridgeConfig load(Path file) throws IOException {
        // Only the canonical config has a legacy sibling. Validate before writing anything;
        // keep the old file byte-for-byte as a migration backup. JSONC always takes priority.
        Path input = file;
        if (Files.notExists(file) && file.getFileName().toString().equals(FILE_NAME)) {
            for(String name:List.of(LEGACY_JSONC_FILE_NAME,LEGACY_FILE_NAME)){
                Path legacy=file.resolveSibling(name);
                if(Files.exists(legacy)){input=legacy;break;}
            }
        }
        if (Files.notExists(input)) {
            BridgeConfig config = installationDefaults();
            Files.createDirectories(file.getParent());
            // Migrate the former directory-owned list once, including on first config creation.
            config.qqBotExcludedMembers = legacyExclusions(file);
            config.validate();
            writeDocument(file, ConfigLayout.external(GSON.toJsonTree(config).getAsJsonObject()), null);
            config.sourceFile = file;
            return config;
        }
        String previous=Files.readString(input);
        JsonObject document=ConfigLayout.normalize(ConfigComments.parse(previous));
        BridgeConfig config=decode(document);
        if(ConfigLayout.get(document,"filters.excludedBots")==null)config.qqBotExcludedMembers=legacyExclusions(file);
        config.validate();
        JsonObject completed=complete(document,config);
        if(!input.equals(file)||!ConfigComments.parse(previous).equals(completed)||!ConfigComments.documented(previous))writeDocument(file,completed,previous);
        config.sourceFile=file;
        return config;
    }

    static JsonObject readDocument(Path file)throws IOException{
        try{return ConfigLayout.normalize(ConfigComments.parse(Files.readString(file)));}
        catch(RuntimeException e){throw new IOException("Invalid bridge JSON",e);}
    }
    static BridgeConfig decode(JsonObject normalized)throws IOException{
        try{
            var runtime=ConfigLayout.internal(normalized);ConfigTypes.validate(runtime);
            var config=GSON.fromJson(runtime,BridgeConfig.class);config.validate();return config;
        }catch(RuntimeException e){throw new IOException("Invalid bridge configuration",e);}
    }
    static JsonObject complete(JsonObject document,BridgeConfig config)throws IOException{
        var result=ConfigLayout.external(GSON.toJsonTree(config).getAsJsonObject());mergeMissing(result,document);return result;
    }
    public static String toJson(BridgeConfig config){
        try{return GSON.toJson(ConfigLayout.external(GSON.toJsonTree(config).getAsJsonObject()));}
        catch(IOException e){throw new IllegalStateException("Invalid internal configuration layout",e);}
    }

    private static List<String> legacyExclusions(Path file) throws IOException {
        Path legacy = file.getParent().resolve("penguin-suit/members.json");
        if (!Files.exists(legacy)) legacy=file.getParent().resolve("penguin-media-bridge/members.json");
        if (!Files.exists(legacy)) return List.of();
        try {
            var root = JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
            if (!root.has("qqManualExcluded")) return List.of();
            return root.getAsJsonArray("qqManualExcluded").asList().stream().map(e -> e.getAsString()).distinct().toList();
        } catch (RuntimeException e) { throw new IOException("Cannot migrate manual bot exclusions", e); }
    }

    public synchronized void setBotExcluded(String scopedId, boolean excluded) throws IOException {
        var next = new java.util.LinkedHashSet<>(qqBotExcludedMembers);
        if (excluded) next.add(scopedId); else next.remove(scopedId);
        if (sourceFile != null) {
            // Preserve unrelated and unknown options, including edits awaiting reload.
            JsonObject root = readDocument(sourceFile);
            var disk = decode(root);
            next = new java.util.LinkedHashSet<>(disk.qqBotExcludedMembers);
            if (excluded) next.add(scopedId); else next.remove(scopedId);
            disk.qqBotExcludedMembers=List.copyOf(next);disk.validate();
            ConfigLayout.put(root,"filters.excludedBots",GSON.toJsonTree(next));
            writeDocument(sourceFile, complete(root,disk), Files.readString(sourceFile));
        }
        qqBotExcludedMembers = List.copyOf(next);
    }

    static void writeDocument(Path file,JsonObject document,String previous)throws IOException{
        atomicWrite(file,ConfigComments.render(document,previous));
    }

    public static void atomicWrite(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName()+".tmp");
        try (var channel = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            var bytes = java.nio.ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName()+".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    public static boolean mergeMissing(JsonObject document, JsonObject defaults) {
        boolean changed=false;
        for(var entry:defaults.entrySet()) {
            if(!document.has(entry.getKey())) {document.add(entry.getKey(),entry.getValue().deepCopy());changed=true;}
            else if(document.get(entry.getKey()).isJsonObject()&&entry.getValue().isJsonObject())
                changed|=mergeMissing(document.getAsJsonObject(entry.getKey()),entry.getValue().getAsJsonObject());
        }
        return changed;
    }
    public static String defaultsJson(){return toJson(new BridgeConfig());}
    public static BridgeConfig installationDefaults(){var c=new BridgeConfig();c.messages.fromGroup="<[QQ群] {name}> {message}";c.messages.fromGame="[MC] {name}：{message}";return c;}
    public static String installationDefaultsJson(){return toJson(installationDefaults());}
    /** Build-time export; never reads a server's credentials or member data. */
    public static void main(String[] args)throws IOException{var c=installationDefaults();c.validate();Path out=Path.of(args[0]);Files.createDirectories(out.toAbsolutePath().getParent());Files.writeString(out,ConfigComments.render(ConfigLayout.external(GSON.toJsonTree(c).getAsJsonObject()),null));}
    public void validate() throws IOException {
        FeatureOptions.validate(this);
        dev.penguinsuit.events.EventFormat.validate(deathEventFormat, java.util.Set.of("player", "message"));
        if(replyRetentionMinutes<1||replyRetentionMinutes>43200||replyMaxEntries<100||replyMaxEntries>100000
                ||replyMaxBytes<1024*1024||replyMaxBytes>128L*1024*1024)throw new IOException("Invalid reply retention limits");
        if (qqBotExcludedMembers == null || qqBotExcludedMembers.size() > 4096) throw new IOException("Invalid qqBotExcludedMembers");
        for (String id : qqBotExcludedMembers)
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}/[A-Za-z0-9_-]{1,256}/[A-Za-z0-9_-]{1,256}"))
                throw new IOException("qqBotExcludedMembers requires AppID/GroupOpenID/member_openid");
        if (fakePlayerFilter == null) throw new IOException("fakePlayerFilter must not be null");
        fakePlayerFilter.validate();
        if (workerThreads < 1 || workerThreads > 4 || queueCapacity < 1 || queueCapacity > 256
                || gameToQqQueueCapacity < 1 || gameToQqQueueCapacity > 64
                || gameToQqMaxBytes < 1024 || gameToQqMaxBytes > 20L * 1024 * 1024
                || gameToQqMaxPendingBytes < gameToQqMaxBytes || gameToQqMaxPendingBytes > 128L * 1024 * 1024
                || gameToQqTimeoutSeconds < 10 || gameToQqTimeoutSeconds > 300
                || gameToQqMaterialMaxImages < 1 || gameToQqMaterialMaxImages > 95
                || gameToQqMaterialTtlMinutes < 10 || gameToQqMaterialTtlMinutes > 10080
                || gameToQqMaterialGraceSeconds < 0 || gameToQqMaterialGraceSeconds > 600
                || maxImagesPerMessage < 1 || maxImagesPerMessage > 10
                || maxDownloadBytes < 1024 || maxDownloadBytes > 32L * 1024 * 1024
                || connectTimeoutMillis < 100 || connectTimeoutMillis > 30000
                || downloadTimeoutMillis < 100 || downloadTimeoutMillis > 60000
                || maxRedirects < 0 || maxRedirects > 5
                || maxDimension < 1 || maxDimension > 8192 || maxPixels < 1 || maxPixels > 67_108_864
                || maxGifFrames < 1 || maxGifFrames > 500
                || maxGifDecodedPixels < 1 || maxGifDecodedPixels > 200_000_000
                || maxOwnedCacheBytes < maxDownloadBytes || maxOwnedCacheBytes > 2L * 1024 * 1024 * 1024
                || maxOwnedImages < 1 || maxOwnedImages > 2000 || cacheTtlMinutes < 1 || cacheTtlMinutes > 10080
                || cacheDeliveryGraceSeconds < 0 || cacheDeliveryGraceSeconds > 600
                || dedupSeconds < 1 || dedupSeconds > 3600
                || minSenderIntervalMillis < 0 || minSenderIntervalMillis > 60000
                || minGroupIntervalMillis < 0 || minGroupIntervalMillis > 60000
                || allowedHostSuffixes == null || allowedHostSuffixes.isEmpty()
                || blockedGroups == null || blockedUsers == null)
            throw new IOException("Bridge config value outside supported range");
        FeatureOptions.list(blockedGroups);FeatureOptions.list(blockedUsers);
        for (String host : allowedHostSuffixes) {
            if (host == null || !host.matches("[a-zA-Z0-9-]+(?:[.][a-zA-Z0-9-]+)+"))
                throw new IOException("Invalid allowedHostSuffixes entry");
        }
    }
}
