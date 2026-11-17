package de.fallingsea;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.fallingsea.network.ConfigRequestPayload;
import de.fallingsea.network.ConfigSnapshotPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-authoritative water removal. Never edits chunks off the main server thread. */
public final class FallingSea implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PREFIX = "[Falling Sea] ";
    private static final int TICKS_DAY = 24000;
    public static final class Settings {
        public String dryingMode = "SEA_LEVEL"; // SEA_LEVEL or DROUGHT
        public int intervalSeconds = 300;
        // Mutually exclusive modes: false = fixed seconds; true = randomized whole Minecraft days.
        public boolean randomSchedule = false;
        public int randomMinDays = 1;
        public int randomMaxDays = 5;
        public int fallBlocks = 1;
        public int startingWaterLevel = 63;
        public int minWaterLevel = -64;
        public int blocksPerTick = 5000;
        public int chunkRadius = 3;
        public int preloadRadiusChunks = 3;
        public int rescanSeconds = 5;
        public int flowCleanupDepth = 48; // Bloecke unter Zielhoehe, die auf Stroemungen geprueft werden
        public String chunkProcessingMode = "AHEAD";
        public boolean enableDrying = true;
        public boolean dryCaves = true;
        public boolean dryWaterlogged = false;
        public boolean meltIce = false;
        public boolean showCountdown = true;
        public int countdownLastSeconds = 10;
        public boolean announceFall = true; // legacy preference: only applies if debug is enabled
        public boolean debug = false;
        public boolean pauseWhenEmpty = true;
        public boolean processNewChunks = true;
        public boolean blockUnpreparedChunkEntry = false;
        public boolean suppressInfiniteSources = true; // true: Nachfliessen blockweise erneut entfernen
    }
    private static final class Save {
        int level = 63;
        long elapsedTicks;
        // Stored per world: the next randomly chosen interval does not reroll on restart.
        long scheduledIntervalTicks;
        boolean scheduledRandomMode;
        boolean running = true;
    }
    private Settings config = new Settings();
    private Save save = new Save();
    private MinecraftServer activeServer;
    private Path statePath;
    private int autosave;
    private int hudSyncTicks;
    private final DryWorker worker = new DryWorker();
    private final Map<UUID, BlockPos> safePositions = new HashMap<>();

    @Override public void onInitialize() {
        readConfig();
        PayloadTypeRegistry.serverboundPlay().register(ConfigRequestPayload.TYPE, ConfigRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigSnapshotPayload.TYPE, ConfigSnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestPayload.TYPE, (payload, ctx) -> snapshot(ctx.player()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            statePath = server.getWorldPath(LevelResource.ROOT).resolve("fallingsea-state.json");
            readSave();
            synchronizeSchedule(false);
            worker.reset();
            worker.load(server.getWorldPath(LevelResource.ROOT).resolve("fallingsea-drought-baseline.dat.gz"));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            persist(); worker.reset(); worker.clearBaselines(); safePositions.clear(); statePath = null; activeServer = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        registerCommands();
    }
    private void tick(MinecraftServer server) {
        if (statePath == null) return;
        if (config.pauseWhenEmpty && server.getPlayerList().getPlayers().isEmpty()) return;
        if (config.enableDrying && save.running) {
            if (save.level > config.minWaterLevel) {
                long interval = currentIntervalTicks();
                // Do not push the next sea-level target while nearby chunks are
                // still draining their visible layers or elevated water sources.
                // This bounds the visible height mismatch between adjacent chunks.
                if (save.elapsedTicks < interval) save.elapsedTicks++;
                if (save.elapsedTicks >= interval && worker.readyForNextAutomaticStep(save.level)) {
                    save.elapsedTicks = 0;
                    save.level = Math.max(config.minWaterLevel, save.level - config.fallBlocks);
                    // A new random interval is chosen only after an actual lowering.
                    if (config.randomSchedule) save.scheduledIntervalTicks = chooseRandomIntervalTicks();
                    // A running pass must finish before scanning the newer target.
                    persist(); snapshotAll();
                    if (config.debug && config.announceFall) announce(server, Component.translatable("fallingsea.chat.water_target", save.level));
                }
            }
            worker.tick(server, config, save.level);
            if (++hudSyncTicks >= 20) { hudSyncTicks = 0; snapshotAll(); }
            if (config.blockUnpreparedChunkEntry) guard(server);
        }
        // Boats are intentionally NOT teleported downward into terrain or dry blocks.
        // Vanilla buoyancy and gravity naturally keep passengers on board as water recedes.
        if (++autosave >= 1200) { autosave = 0; persist(); }
    }
    private long chooseRandomIntervalTicks() {
        // Inclusive day range; even the shortest interval spans a full Minecraft day.
        int days = java.util.concurrent.ThreadLocalRandom.current().nextInt(
            config.randomMinDays, config.randomMaxDays + 1);
        return (long) days * TICKS_DAY;
    }
    private long currentIntervalTicks() {
        if (!config.randomSchedule) return (long) config.intervalSeconds * 20L;
        if (save.scheduledIntervalTicks < (long) config.randomMinDays * TICKS_DAY
            || save.scheduledIntervalTicks > (long) config.randomMaxDays * TICKS_DAY) {
            save.scheduledIntervalTicks = chooseRandomIntervalTicks();
        }
        return save.scheduledIntervalTicks;
    }
    private void synchronizeSchedule(boolean resetClock) {
        if (save.scheduledRandomMode != config.randomSchedule) resetClock = true;
        if (resetClock) save.elapsedTicks = 0;
        long minimum = (long) config.randomMinDays * TICKS_DAY;
        long maximum = (long) config.randomMaxDays * TICKS_DAY;
        if (resetClock || (config.randomSchedule &&
                (save.scheduledIntervalTicks < minimum || save.scheduledIntervalTicks > maximum))) {
            save.scheduledIntervalTicks = config.randomSchedule ? chooseRandomIntervalTicks() : 0;
        }
        save.scheduledRandomMode = config.randomSchedule;
    }
    private void guard(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(Level.OVERWORLD)) continue;
            BlockPos pos = player.blockPosition();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            if (worker.ready(cx,cz, save.level)) safePositions.put(player.getUUID(), pos);
            else {
                BlockPos previous = safePositions.get(player.getUUID());
                if (previous != null && ((previous.getX() >> 4) != cx || (previous.getZ() >> 4) != cz)) {
                    player.setPos(previous.getX() + .5, previous.getY(), previous.getZ() + .5);
                    player.setDeltaMovement(0,0,0);
                }
            }
        }
    }
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("fallingsea")
            .then(Commands.literal("status").executes(ctx -> {
                if (config.debug) ctx.getSource().sendSuccess(() -> Component.literal(PREFIX).append(Component.translatable("fallingsea.chat.status", save.level, Component.translatable("fallingsea.mode." + config.dryingMode), flag(config.enableDrying && save.running && !config.randomSchedule), flag(config.enableDrying), flag(!config.suppressInfiniteSources), worker.progressComponent(), Math.max(0,(currentIntervalTicks()-save.elapsedTicks+19)/20), Component.translatable(config.randomSchedule ? "fallingsea.schedule.random" : "fallingsea.schedule.fixed"))),false);
                return 1;
            }))
            .then(Commands.literal("config").executes(ctx -> {
                if (config.debug) ctx.getSource().sendSuccess(() -> Component.literal(PREFIX+GSON.toJson(config)),false); return 1;
            }))
            .then(Commands.literal("start").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                save.running = true; config.enableDrying = true; writeConfig(); worker.invalidate(); persist(); snapshotAll(); announce(ctx.getSource().getServer(), Component.translatable("fallingsea.chat.started")); return 1;
            }))
            .then(Commands.literal("stop").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                save.running = false; persist(); snapshotAll(); announce(ctx.getSource().getServer(), Component.translatable("fallingsea.chat.stopped")); return 1;
            }))
            .then(Commands.literal("mode").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("sea_level").executes(ctx -> { mode("SEA_LEVEL"); return 1; }))
                .then(Commands.literal("drought").executes(ctx -> { mode("DROUGHT"); return 1; })))
            .then(Commands.literal("processing").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("ahead").executes(ctx -> { processing("AHEAD"); return 1; }))
                .then(Commands.literal("loaded").executes(ctx -> { processing("LOADED"); return 1; }))
                .then(Commands.literal("global").executes(ctx -> { processing("GLOBAL"); return 1; })))
            .then(Commands.literal("setlevel").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                .then(Commands.argument("y",IntegerArgumentType.integer(-64,319)).executes(ctx -> {
                    save.level = Math.max(config.minWaterLevel,Math.min(config.startingWaterLevel,IntegerArgumentType.getInteger(ctx,"y")));
                    config.enableDrying=true; save.running=true; writeConfig(); worker.invalidate();
                    save.elapsedTicks=0; persist(); snapshotAll(); return 1;
                })))
            .then(Commands.literal("lower").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                .then(Commands.argument("blocks",IntegerArgumentType.integer(1,256)).executes(ctx -> {
                    save.level = Math.max(config.minWaterLevel,save.level-IntegerArgumentType.getInteger(ctx,"blocks"));
                    config.enableDrying=true; save.running=true; writeConfig(); worker.invalidate();
                    save.elapsedTicks=0; persist(); snapshotAll(); return 1;
                })))
            .then(Commands.literal("reload").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                readConfig(); synchronizeSchedule(false); worker.invalidate(); snapshotAll(); return 1;
            }))
            .then(Commands.literal("set").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("interval").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("interval",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("randommindays").then(Commands.argument("value",IntegerArgumentType.integer(1,365)).executes(ctx -> numeric("randommindays",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("randommaxdays").then(Commands.argument("value",IntegerArgumentType.integer(1,365)).executes(ctx -> numeric("randommaxdays",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("fallblocks").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("fallblocks",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("startlevel").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("startlevel",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("minlevel").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("minlevel",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("budget").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("budget",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("radius").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("radius",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("preload").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("preload",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("rescan").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("rescan",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("flowdepth").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("flowdepth",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("countdownseconds").then(Commands.argument("value",IntegerArgumentType.integer()).executes(ctx -> numeric("countdownseconds",IntegerArgumentType.getInteger(ctx,"value")))))
                .then(Commands.literal("enabled").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("enabled",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("caves").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("caves",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("waterlogged").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("waterlogged",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("ice").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("ice",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("randomschedule").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("randomschedule",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("countdown").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("countdown",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("announce").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("announce",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("debug").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("debug",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("pauseempty").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("pauseempty",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("newchunks").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("newchunks",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("guard").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("guard",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("sources").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("sources",BoolArgumentType.getBool(ctx,"value")))))
                .then(Commands.literal("refill").then(Commands.argument("value",BoolArgumentType.bool()).executes(ctx -> toggle("refill",BoolArgumentType.getBool(ctx,"value"))))))
        ));
    }
    private int numeric(String key,int value) {
        // Never allow fixed and random intervals to be enabled at once.
        if ("interval".equals(key) && config.randomSchedule) return 0;
        switch(key) {
            case "interval" -> config.intervalSeconds=value;
            case "randommindays" -> config.randomMinDays=value;
            case "randommaxdays" -> config.randomMaxDays=value;
            case "fallblocks" -> config.fallBlocks=value;
            case "startlevel" -> config.startingWaterLevel=value;
            case "minlevel" -> config.minWaterLevel=value;
            case "budget" -> config.blocksPerTick=value;
            case "radius" -> config.chunkRadius=value;
            case "preload" -> config.preloadRadiusChunks=value;
            case "rescan" -> config.rescanSeconds=value;
            case "flowdepth" -> config.flowCleanupDepth=value;
            case "countdownseconds" -> config.countdownLastSeconds=value;
        }
        validate();
        if ("interval".equals(key)) save.elapsedTicks = 0;
        if ("randommindays".equals(key) || "randommaxdays".equals(key)) synchronizeSchedule(true);
        writeConfig(); persist();
        if (!Set.of("interval", "randommindays", "randommaxdays").contains(key)) worker.invalidate();
        snapshotAll(); return 1;
    }
    private int toggle(String key,boolean value) {
        switch(key) {
            case "enabled" -> config.enableDrying=value;
            case "randomschedule" -> config.randomSchedule=value;
            case "caves" -> config.dryCaves=value;
            case "waterlogged" -> config.dryWaterlogged=value;
            case "ice" -> config.meltIce=value;
            case "countdown" -> config.showCountdown=value;
            case "announce" -> config.announceFall=value;
            case "debug" -> config.debug=value;
            case "pauseempty" -> config.pauseWhenEmpty=value;
            case "newchunks" -> config.processNewChunks=value;
            case "guard" -> config.blockUnpreparedChunkEntry=value;
            case "sources" -> config.suppressInfiniteSources=value;
            case "refill" -> config.suppressInfiniteSources=!value;
        }
        if ("randomschedule".equals(key)) synchronizeSchedule(false);
        writeConfig(); persist();
        if (!"randomschedule".equals(key)) worker.invalidate();
        snapshotAll(); return 1;
    }
    private void mode(String s) { config.dryingMode=s; writeConfig();worker.invalidate();snapshotAll(); }
    private void processing(String s) { config.chunkProcessingMode=s;writeConfig();worker.invalidate();snapshotAll(); }
    private static Component flag(boolean value) { return Component.translatable(value ? "fallingsea.on" : "fallingsea.off"); }
    private void announce(MinecraftServer server,Component msg) { if (config.debug) server.getPlayerList().broadcastSystemMessage(Component.literal(PREFIX).append(msg),false); }
    private void snapshot(ServerPlayer player) {
        if (activeServer == null || player == null) return;
        JsonObject json=GSON.toJsonTree(config).getAsJsonObject();
        json.addProperty("currentWaterLevel",save.level);json.addProperty("running",save.running);
        long remainingTicks = Math.max(0, currentIntervalTicks() - save.elapsedTicks);
        // If scanning is still in progress, the next lowering time is unknown:
        // hide the countdown rather than displaying a misleading 0:01 indefinitely.
        json.addProperty("countdownRemainingSeconds", (remainingTicks + 19) / 20);
        json.addProperty("countdownScanReady", worker.readyForNextAutomaticStep(save.level));
        ServerPlayNetworking.send(player,new ConfigSnapshotPayload(json.toString()));
    }
    private void snapshotAll() { if(activeServer!=null)for(ServerPlayer p:activeServer.getPlayerList().getPlayers())snapshot(p); }
    private Path configPath() { return FabricLoader.getInstance().getConfigDir().resolve("fallingsea.json"); }
    private void validate() {
        if (config.dryingMode == null || !Set.of("SEA_LEVEL","DROUGHT").contains(config.dryingMode))config.dryingMode="SEA_LEVEL";
        if (config.chunkProcessingMode == null || !Set.of("AHEAD","LOADED","GLOBAL").contains(config.chunkProcessingMode))config.chunkProcessingMode="AHEAD";
        config.intervalSeconds=Math.max(1,Math.min(86400,config.intervalSeconds));
        config.randomMinDays=Math.max(1,Math.min(365,config.randomMinDays));
        config.randomMaxDays=Math.max(config.randomMinDays,Math.min(365,config.randomMaxDays));
        config.fallBlocks=Math.max(1,Math.min(100,config.fallBlocks));
        config.startingWaterLevel=Math.max(-64,Math.min(319,config.startingWaterLevel));
        config.minWaterLevel=Math.max(-64,Math.min(config.startingWaterLevel,config.minWaterLevel));
        config.blocksPerTick=Math.max(256,Math.min(20000,config.blocksPerTick));
        config.chunkRadius=Math.max(0,Math.min(8,config.chunkRadius));
        config.preloadRadiusChunks=Math.max(0,Math.min(16,config.preloadRadiusChunks));
        config.rescanSeconds=Math.max(1,Math.min(600,config.rescanSeconds));
        config.flowCleanupDepth=Math.max(0,Math.min(128,config.flowCleanupDepth));
        config.countdownLastSeconds=Math.max(0,Math.min(60,config.countdownLastSeconds));
    }
    private void readConfig() {
        try {
            if(Files.exists(configPath()))try(Reader in=Files.newBufferedReader(configPath())) {Settings x=GSON.fromJson(in,Settings.class);if(x!=null)config=x;}
            validate();writeConfig();
        } catch(Exception e) {System.err.println(PREFIX+"Konfiguration: "+e);}
    }
    private void writeConfig() {
        try {
            Path target = configPath();
            Files.createDirectories(target.getParent());
            Path temporary=target.resolveSibling(target.getFileName()+".tmp");
            try(Writer w=Files.newBufferedWriter(temporary)){GSON.toJson(config,w);}
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception e){System.err.println(PREFIX+"Konfiguration schreiben: "+e);}
    }
    private void readSave() {
        save=new Save();save.level=config.startingWaterLevel;
        try {if(Files.exists(statePath))try(Reader r=Files.newBufferedReader(statePath)){Save x=GSON.fromJson(r,Save.class);if(x!=null)save=x;}}
        catch(Exception e){System.err.println(PREFIX+"Spielstand lesen: "+e);}
        save.level=Math.max(config.minWaterLevel,Math.min(config.startingWaterLevel,save.level));
        save.elapsedTicks=Math.max(0,save.elapsedTicks);
        save.scheduledIntervalTicks=Math.max(0,save.scheduledIntervalTicks);
    }
    private void persist() {
        if(statePath==null)return;
        try {
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            try(Writer w=Files.newBufferedWriter(temporary)){GSON.toJson(save,w);}
            try { Files.move(temporary,statePath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temporary,statePath,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception e){System.err.println(PREFIX+"Spielstand speichern: "+e);}
        worker.save(statePath.resolveSibling("fallingsea-drought-baseline.dat.gz"));
    }

    /** Round-robin worker: distribute work across nearby chunks to avoid tall water walls. */
    private static final class DryWorker {
        private record ColumnKey(int x,int z) {}
        // Visit the 60 outer edge cells of every chunk layer before its 196 inner
        // cells. Border water sources are the main cause of visible chunk seams.
        // The order is stable and visits every cell exactly once.
        private static final int[] BORDER_FIRST_COLUMNS = borderFirstColumns();
        private static int[] borderFirstColumns() {
            int[] result = new int[256];
            int index = 0;
            for (int pass=0;pass<2;pass++) {
                for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                    boolean edge = x==0 || x==15 || z==0 || z==15;
                    if (edge == (pass==0)) result[index++]=(z<<4)|x;
                }
            }
            if (index!=256) throw new IllegalStateException("Invalid chunk edge ordering");
            return result;
        }
        private static final class Work {
            final ServerLevel world;
            final int x,z;
            final boolean flowCleanup;
            final boolean elevatedSweep;
            int column,y; // Horizontal layers: visit all x/z before descending in Y.
            Work(ServerLevel world,int x,int z,int top,boolean flowCleanup,boolean elevatedSweep) {
                this.world=world; this.x=x; this.z=z; this.y=top;
                this.flowCleanup=flowCleanup;
                this.elevatedSweep=elevatedSweep;
            }
        }
        // Critical: visible sea-level work always takes precedence over deep flow cleanup.
        // Deeper fluid checks never delay processing the upper water layers.
        private final ArrayDeque<Work> surfaceQueue=new ArrayDeque<>();
        private final ArrayDeque<Work> elevatedQueue=new ArrayDeque<>();
        private final ArrayDeque<Work> flowQueue=new ArrayDeque<>();
        private final Set<Long> queued=new HashSet<>();
        private final Set<Long> visited=new HashSet<>();
        private final Map<Long,Integer> ready=new HashMap<>();
        private final Map<ColumnKey,Integer> originalSurface=new HashMap<>();
        private int scanCooldown;
        // Fluid updates may occur after block removal. Two short surface-only
        // verification passes catch newly generated sources before accepting a step.
        private static final int VERIFICATION_DELAY_TICKS = 10;
        // Do not declare a regenerated water mound clean after a pass that
        // itself removed newly formed water. Bound the retries to protect TPS.
        private static final int MAX_VERIFICATION_PASSES = 8;
        private int verificationAttempts;
        private long removedAtVerificationStart;
        private int verificationRemaining;
        private int settlingTicks;
        private boolean verificationPass;
        private boolean passCompletionRecorded;
        private int completedPassLevel=Integer.MAX_VALUE;
        private boolean firstScan;
        private int completedChunks;
        private long examinedBlocks,removedBlocks;
        private int scannedLevel=Integer.MIN_VALUE;
        private String scannedMode;
        private static long chunkKey(int cx,int cz){return ((long)cx<<32)^(cz&0xffffffffL);}
        void reset(){surfaceQueue.clear();elevatedQueue.clear();flowQueue.clear();queued.clear();visited.clear();ready.clear();scanCooldown=0;verificationRemaining=0;settlingTicks=0;verificationPass=false;verificationAttempts=0;removedAtVerificationStart=0;passCompletionRecorded=false;firstScan=false;completedChunks=0;examinedBlocks=0;removedBlocks=0;scannedLevel=Integer.MIN_VALUE;completedPassLevel=Integer.MAX_VALUE;scannedMode=null;}
        void invalidate(){surfaceQueue.clear();elevatedQueue.clear();flowQueue.clear();queued.clear();ready.clear();scanCooldown=0;verificationRemaining=0;settlingTicks=0;verificationPass=false;verificationAttempts=0;removedAtVerificationStart=0;passCompletionRecorded=false;firstScan=false;completedChunks=0;examinedBlocks=0;removedBlocks=0;scannedLevel=Integer.MIN_VALUE;completedPassLevel=Integer.MAX_VALUE;scannedMode=null;}
        void clearBaselines(){originalSurface.clear();}
        Component progressComponent() {
            Component current = scannedLevel == Integer.MIN_VALUE
                ? Component.translatable("fallingsea.chat.none_started") : Component.literal(Integer.toString(scannedLevel));
            Component last = completedPassLevel == Integer.MAX_VALUE
                ? Component.translatable("fallingsea.chat.none") : Component.literal(Integer.toString(completedPassLevel));
            return Component.translatable("fallingsea.chat.progress", current, last, verificationRemaining,
                MAX_VERIFICATION_PASSES, settlingTicks, completedChunks,
                surfaceQueue.size() + elevatedQueue.size() + flowQueue.size(),
                surfaceQueue.size(), elevatedQueue.size(), flowQueue.size(),
                examinedBlocks, removedBlocks);
        }
        boolean ready(int cx,int cz,int level){return ready.getOrDefault(chunkKey(cx,cz),Integer.MAX_VALUE)==level;}
        private boolean pending(){ return !surfaceQueue.isEmpty() || !elevatedQueue.isEmpty() || !flowQueue.isEmpty(); }
        boolean readyForNextAutomaticStep(int target) {
            // Only the visible surface must finish before the next lowering step.
            // Deep/elevated cleanup and post-update verification are best-effort
            // maintenance: gating on them could freeze the water level for minutes
            // in dense worlds, or forever when no chunks are currently loaded.
            return scannedLevel==target && surfaceQueue.isEmpty();
        }

        void tick(MinecraftServer server,Settings cfg,int target) {
            if(scannedMode!=null && !cfg.dryingMode.equals(scannedMode))invalidate();
            // The next level supersedes unfinished maintenance but not visible
            // surface work. This prevents automatic lowering from becoming stuck
            // behind high-altitude spring scans or flowing-water verification.
            if(scannedLevel!=Integer.MIN_VALUE && target!=scannedLevel && surfaceQueue.isEmpty()){
                verificationRemaining=0;
                settlingTicks=0;
                verificationAttempts=0;
                elevatedQueue.clear();
                flowQueue.clear();
                queued.clear();
                ready.clear();
                startPass(server,cfg,target);
            }
            if(!pending() && settlingTicks>0) {
                if(--settlingTicks==0 && verificationRemaining>0) {
                    verificationRemaining--;
                    startVerificationPass(server,cfg,target);
                }
            }
            if(!pending() && settlingTicks==0){
                if(scannedLevel!=target || !cfg.dryingMode.equals(scannedMode))startPass(server,cfg,target);
                else if(++scanCooldown>=Math.max(20,cfg.rescanSeconds*20)){
                    scanCooldown=0;
                    if(cfg.suppressInfiniteSources){
                        completedChunks=0;
                        ready.clear();
                    }
                    discover(server,cfg);
                }
            } else if(++scanCooldown>=20){
                scanCooldown=0;
                discover(server,cfg);
            }
            int remaining=cfg.blocksPerTick;
            while(remaining>0 && pending()){
                // Surface work is always selected before deep cleanup work.
                final boolean isSurface=!surfaceQueue.isEmpty();
                final boolean isElevated=!isSurface && !elevatedQueue.isEmpty();
                Work work=(isSurface?surfaceQueue:(isElevated?elevatedQueue:flowQueue)).removeFirst();
                long key=chunkKey(work.x,work.z);
                if(!work.world.hasChunk(work.x,work.z)){
                    queued.remove(key);
                    continue;
                }
                int bottom=work.elevatedSweep
                    ? Math.max(work.world.getMinY(),cfg.startingWaterLevel+1)
                    : work.flowCleanup
                    ? bottomY(work.world,cfg,scannedLevel)
                    : ("SEA_LEVEL".equals(cfg.dryingMode)
                        ? Math.max(work.world.getMinY(),scannedLevel+1)
                        : work.world.getMinY());
                int quantum=Math.min(remaining,256);
                int used=0;
                while(used<quantum && work.y>=bottom){
                    int local=BORDER_FIRST_COLUMNS[work.column];
                    int x=(work.x<<4)+(local&15);
                    int z=(work.z<<4)+(local>>>4);
                    int y=work.y;
                    work.column++;
                    if(work.column==256){ work.column=0; work.y--; }
                    used++;
                    examinedBlocks++;
                    BlockPos pos=new BlockPos(x,y,z);
                    BlockState block=work.world.getBlockState(pos);
                    var fluid=work.world.getFluidState(pos);
                    boolean water=block.is(Blocks.WATER);
                    boolean flowingWater=fluid.is(FluidTags.WATER) && !fluid.isSource();
                    boolean waterlogged=block.hasProperty(BlockStateProperties.WATERLOGGED) && block.getValue(BlockStateProperties.WATERLOGGED);
                    boolean aquaticPlant=block.is(Blocks.KELP)||block.is(Blocks.KELP_PLANT)||block.is(Blocks.SEAGRASS)||block.is(Blocks.TALL_SEAGRASS);
                    boolean bubbleColumn=block.is(Blocks.BUBBLE_COLUMN);
                    boolean aquaticWater=fluid.is(FluidTags.WATER)&&(aquaticPlant||bubbleColumn);
                    boolean ice=block.is(Blocks.ICE)||block.is(Blocks.FROSTED_ICE);
                    // Waterlogged building blocks can contain SOURCE water. Leaving
                    // them wet above the target creates seemingly inexplicable
                    // waterfalls around chests, stairs and slabs. Drain their water
                    // without destroying the underlying block when refill prevention
                    // is enabled. The sea-level sweep still preserves water at/below
                    // the target even when dryWaterlogged is explicitly configured.
                    boolean dryWaterloggedHere=waterlogged && (cfg.dryWaterlogged
                        || (cfg.suppressInfiniteSources && y>scannedLevel));
                    if(!water&&!aquaticWater&&!dryWaterloggedHere&&!(cfg.meltIce&&ice))continue;
                    // Water above the ocean floor is open water even when water blocks
                    // above it make canSeeSky(pos) false. Avoid skipping the sea itself.
                    if(!work.elevatedSweep && !cfg.dryCaves && !cfg.suppressInfiniteSources && y<work.world.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,x,z))continue;

                    boolean remove;
                    if(work.elevatedSweep){
                        // Optional high-altitude cleanup: water SOURCE and flowing water
                        // above the configured starting sea level are not exempt anymore.
                        // The regular surface scan already handles levels below this band.
                        remove=cfg.suppressInfiniteSources && y>scannedLevel;
                    }else if(work.flowCleanup){
                        // Never erase stable underwater source blocks just to dry a stream.
                        remove=cfg.suppressInfiniteSources && flowingWater && (water || aquaticWater);
                    }else if("SEA_LEVEL".equals(cfg.dryingMode)){
                        remove=y>scannedLevel && y<=cfg.startingWaterLevel;
                    }else{
                        ColumnKey columnKey=new ColumnKey(x,z);
                        int surface=originalSurface.computeIfAbsent(columnKey,k->y);
                        int dryDepth=cfg.startingWaterLevel-scannedLevel;
                        remove=y>surface-dryDepth;
                    }
                    if(!remove)continue;
                    boolean changed=(water||aquaticWater||ice)
                        ? work.world.setBlock(pos,Blocks.AIR.defaultBlockState(),3)
                        : work.world.setBlock(pos,block.setValue(BlockStateProperties.WATERLOGGED,false),3);
                    if(changed)removedBlocks++;
                }
                remaining-=used;
                if(work.y<bottom){
                    if(!verificationPass && !work.elevatedSweep && !work.flowCleanup && "SEA_LEVEL".equals(cfg.dryingMode)
                        && cfg.suppressInfiniteSources && work.world.getMaxY()-1>cfg.startingWaterLevel){
                        // Keep visible sea-level lowering fast. Only after surface
                        // work do we sweep elevated springs, waterfalls and their sources.
                        int top = highestSurfaceY(work.world,work.x,work.z);
                        if(top>cfg.startingWaterLevel){
                            elevatedQueue.addLast(new Work(work.world,work.x,work.z,
                                top,false,true));
                        }else if(cfg.flowCleanupDepth>0){
                            flowQueue.addLast(new Work(work.world,work.x,work.z,
                                Math.min(work.world.getMaxY()-1,scannedLevel),true,false));
                        }else{
                            queued.remove(key);
                            ready.put(key,scannedLevel);
                            completedChunks++;
                        }
                    }else if(!verificationPass && !work.flowCleanup && "SEA_LEVEL".equals(cfg.dryingMode)
                        && cfg.suppressInfiniteSources && cfg.flowCleanupDepth>0){
                        // Deep flows are processed after the source sweep.
                        flowQueue.addLast(new Work(work.world,work.x,work.z,
                            Math.min(work.world.getMaxY()-1,scannedLevel),true,false));
                    }else{
                        queued.remove(key);
                        ready.put(key,scannedLevel);
                        completedChunks++;
                    }
                }else{
                    (isSurface?surfaceQueue:(isElevated?elevatedQueue:flowQueue)).addLast(work);
                }
            }
            if(!pending() && scannedLevel!=Integer.MIN_VALUE && completedChunks>0 && !passCompletionRecorded){
                passCompletionRecorded=true;
                // Minecraft can refill water during a verification pass. A pass
                // which actually removed blocks is not evidence of stability.
                // Check again after fluid updates settle, but never indefinitely.
                if (verificationPass && removedBlocks>removedAtVerificationStart
                    && verificationAttempts<MAX_VERIFICATION_PASSES) {
                    verificationRemaining=Math.max(verificationRemaining,1);
                }
                if(verificationRemaining>0){
                    settlingTicks=VERIFICATION_DELAY_TICKS;
                }else{
                    completedPassLevel=scannedLevel;
                }
            }
        }
        private void startVerificationPass(MinecraftServer server,Settings cfg,int target) {
            verificationPass=true;
            verificationAttempts++;
            removedAtVerificationStart=removedBlocks;
            scannedLevel=target;
            completedChunks=0;
            passCompletionRecorded=false;
            scanCooldown=0;
            ready.clear();
            discover(server,cfg);
        }
        private void startPass(MinecraftServer server,Settings cfg,int target){
            verificationPass=false;
            verificationAttempts=0;
            removedAtVerificationStart=removedBlocks;
            verificationRemaining=cfg.suppressInfiniteSources && "SEA_LEVEL".equals(cfg.dryingMode) ? 2 : 0;
            settlingTicks=0;
            passCompletionRecorded=false;
            scannedLevel=target;
            scannedMode=cfg.dryingMode;
            completedChunks=0;
            scanCooldown=0;
            ready.clear();
            discover(server,cfg);
            firstScan=true;
        }
        private int highestSurfaceY(ServerLevel world,int cx,int cz){
            int top=world.getMinY();
            for(int dz=0;dz<16;dz++) for(int dx=0;dx<16;dx++){
                int height=world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    (cx<<4)+dx,(cz<<4)+dz)-1;
                if(height>top) top=height;
            }
            return Math.min(world.getMaxY()-1,top);
        }
        private int topY(ServerLevel world,Settings cfg){
            return "SEA_LEVEL".equals(cfg.dryingMode)
                ? Math.min(world.getMaxY()-1,cfg.startingWaterLevel) : world.getMaxY()-1;
        }
        private int bottomY(ServerLevel world,Settings cfg,int target){
            // Scanning the entire 384-block world height for every shallow stream
            // starves the nearby water surface of processing time. Prioritise the
            // sea-level band plus a configurable waterfall cleanup depth.
            if ("SEA_LEVEL".equals(cfg.dryingMode)) {
                int depth = cfg.suppressInfiniteSources ? cfg.flowCleanupDepth : 0;
                return Math.max(world.getMinY(), target - depth + 1);
            }
            return world.getMinY();
        }
        private void discover(MinecraftServer server,Settings cfg){
            List<Work> candidates=new ArrayList<>();
            Set<Long> round=new HashSet<>();
            for(ServerPlayer player:server.getPlayerList().getPlayers()){
                ServerLevel world=player.level();
                if(!world.dimension().equals(Level.OVERWORLD))continue;
                int cx=player.blockPosition().getX()>>4,cz=player.blockPosition().getZ()>>4;
                int radius="LOADED".equals(cfg.chunkProcessingMode)?cfg.chunkRadius:Math.max(cfg.chunkRadius,cfg.preloadRadiusChunks);
                for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
                    int x=cx+dx,z=cz+dz;
                    long key=chunkKey(x,z);
                    if(!world.hasChunk(x,z)||!round.add(key)||queued.contains(key))continue;
                    if(ready.getOrDefault(key,Integer.MAX_VALUE)==scannedLevel)continue;
                    if(!cfg.processNewChunks&&firstScan&&!visited.contains(key))continue;
                    visited.add(key);
                    if(queued.add(key))candidates.add(new Work(world,x,z,topY(world,cfg),false,false));
                }
            }
            candidates.sort(Comparator.comparingDouble(w->distance(w,server)));
            if(!candidates.isEmpty()) passCompletionRecorded=false;
            surfaceQueue.addAll(candidates);
        }
        private double distance(Work work,MinecraftServer server){
            double best=Double.MAX_VALUE;
            for(ServerPlayer player:server.getPlayerList().getPlayers()){
                if(player.level()!=work.world)continue;
                double dx=work.x*16+8-player.getX(),dz=work.z*16+8-player.getZ();
                double forward=dx*player.getLookAngle().x+dz*player.getLookAngle().z;
                best=Math.min(best,Math.hypot(dx,dz)-Math.max(0,forward)*.65);
            }
            return best;
        }
        void load(Path file){
            originalSurface.clear();if(!Files.exists(file))return;
            try(DataInputStream in=new DataInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(file)))){
                int count=in.readInt();if(count<0 || count>5_000_000)throw new IOException("Invalid baseline count");
                for(int i=0;i<count;i++)originalSurface.put(new ColumnKey(in.readInt(),in.readInt()),in.readInt());
            }catch(Exception e){System.err.println(PREFIX+"Drought-Baselines lesen: "+e);}
        }
        void save(Path file){
            if(file==null)return;
            try {
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try(DataOutputStream out=new DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(temporary)))){
                    out.writeInt(originalSurface.size());
                    for(var entry:originalSurface.entrySet()){
                        out.writeInt(entry.getKey().x());out.writeInt(entry.getKey().z());out.writeInt(entry.getValue());
                    }
                }
                try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException ex) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
            }catch(Exception e){System.err.println(PREFIX+"Drought-Baselines schreiben: "+e);}
        }
    }
}
