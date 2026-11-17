package de.fallingsea.client;

import com.google.gson.JsonObject;
import de.fallingsea.network.ConfigRequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-authoritative config editor with bounded rows and explicit paging. */
@Environment(EnvType.CLIENT)
public final class FallingSeaConfigScreen extends Screen {
    private record Option(String key, String name, String kind) {}
    private static Component tr(String key, Object... args) { return Component.translatable("fallingsea." + key, args); }
    private static String local(String key, Object... args) { return tr(key, args).getString(); }
    private static Component label(Option option) { return tr("option." + option.key); }
    private static String selection(String group, String value) { return local(group + "." + value); }
    private static Option n(String k, String name) { return new Option(k, name, "number"); }
    private static Option t(String k, String name) { return new Option(k, name, "toggle"); }
    private static Option action(String k, String name) { return new Option(k, name, "action"); }
    private static final List<List<Option>> PAGES = List.of(
        List.of(action("mode", "mode"), t("randomschedule", "randomschedule"),
            n("randommindays", "randommindays"), n("randommaxdays", "randommaxdays"),
            n("interval", "interval"),
            n("fallblocks", "fallblocks"), n("startlevel", "startlevel"),
            n("minlevel", "minlevel"), n("countdownseconds", "countdownseconds"),
            t("enabled", "enabled"), t("countdown", "countdown"),
            t("debug", "debug")),
        List.of(action("processing", "processing"), n("preload", "preload"),
            n("radius", "radius"), n("budget", "budget"),
            n("rescan", "rescan"), n("flowdepth", "flowdepth"), t("newchunks", "newchunks"),
            t("guard", "guard"), t("caves", "caves"),
            t("pauseempty", "pauseempty")),
        List.of(t("waterlogged", "waterlogged"), t("ice", "ice"),
            t("sources", "sources"),
            n("globallevel", "globallevel"),
            action("setlevel", "setlevel"),
            action("status", "status"),
            action("refresh", "refresh"))
    );
    private static final Map<String, String> JSON_NUMBERS = Map.ofEntries(
        Map.entry("interval", "intervalSeconds"),
        Map.entry("randommindays", "randomMinDays"), Map.entry("randommaxdays", "randomMaxDays"),
        Map.entry("fallblocks", "fallBlocks"),
        Map.entry("startlevel", "startingWaterLevel"), Map.entry("minlevel", "minWaterLevel"),
        Map.entry("countdownseconds", "countdownLastSeconds"), Map.entry("preload", "preloadRadiusChunks"),
        Map.entry("radius", "chunkRadius"), Map.entry("budget", "blocksPerTick"),
        Map.entry("rescan", "rescanSeconds"), Map.entry("flowdepth", "flowCleanupDepth"), Map.entry("globallevel", "currentWaterLevel"));
    private static final Map<String, String> JSON_TOGGLES = Map.ofEntries(
        Map.entry("enabled", "enableDrying"),
        Map.entry("randomschedule", "randomSchedule"), Map.entry("countdown", "showCountdown"),
        Map.entry("debug", "debug"), Map.entry("newchunks", "processNewChunks"),
        Map.entry("guard", "blockUnpreparedChunkEntry"), Map.entry("caves", "dryCaves"),
        Map.entry("pauseempty", "pauseWhenEmpty"), Map.entry("waterlogged", "dryWaterlogged"),
        Map.entry("ice", "meltIce"), Map.entry("sources", "suppressInfiniteSources"));

    private final Screen parent;
    private final Map<String,String> numbers = new LinkedHashMap<>();
    private final Map<String,Boolean> toggles = new LinkedHashMap<>();
    private final Set<String> changes = new HashSet<>();
    private String mode = "SEA_LEVEL", processing = "AHEAD";
    private String message = local("message.loading");
    private boolean synced;
    private int page, scroll;

    public FallingSeaConfigScreen(Screen parent) {
        super(tr("screen.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        clearWidgets();
        int x = width/2 - Math.min(320, width - 24)/2;
        int w = Math.min(320, width - 24);
        int footerY = height - 50;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> navigate(-1))
            .bounds(x, 28, 48, 20).build());
        addRenderableWidget(Button.builder(tr("page", page+1, PAGES.size()), b -> {})
            .bounds(x + 52, 28, w-104, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> navigate(1))
            .bounds(x+w-48, 28, 48, 20).build());
        int top = 57;
        int available = Math.max(20, footerY - 16 - top);
        int perPage = Math.max(1, available / 25);
        List<Option> all = PAGES.get(page);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - perPage)));
        int contentWidth = w - 46;
        for (int idx=scroll; idx<Math.min(all.size(), scroll+perPage); idx++) {
            Option opt = all.get(idx);
            int y = top + (idx-scroll)*25;
            if (opt.kind.equals("number")) {
                int leftWidth = Math.max(88, contentWidth * 58 / 100);
                addRenderableWidget(Button.builder(label(opt), b -> {})
                    .bounds(x, y, leftWidth-4, 20).build()).active = false;
                EditBox field = new EditBox(font, x+leftWidth, y, contentWidth-leftWidth, 20, label(opt));
                field.setValue(numbers.getOrDefault(opt.key, ""));
                field.setResponder(v -> {numbers.put(opt.key, v);changes.add(opt.key);});
                field.active = !(opt.key.equals("interval") && toggles.getOrDefault("randomschedule", false))
                    && !((opt.key.equals("randommindays") || opt.key.equals("randommaxdays"))
                        && !toggles.getOrDefault("randomschedule", false));
                addRenderableWidget(field);
            } else if (opt.kind.equals("toggle")) {
                boolean value = toggles.getOrDefault(opt.key,false);
                addRenderableWidget(Button.builder(tr("toggle", label(opt).getString(), local(value ? "on" : "off")), b -> {
                    toggles.put(opt.key,!toggles.getOrDefault(opt.key,false));
                    changes.add(opt.key);
                    b.setMessage(tr("toggle", label(opt).getString(), local(toggles.get(opt.key) ? "on" : "off")));
                    if (opt.key.equals("randomschedule")) init();
                }).bounds(x,y,contentWidth,20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal(actionLabel(opt)), b -> invoke(opt.key))
                    .bounds(x,y,contentWidth,20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {scroll=Math.max(0, scroll-1);init();})
            .bounds(x+w-40, top, 40, 20).build()).active = scroll > 0;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {scroll=Math.min(Math.max(0,all.size()-perPage),scroll+1);init();})
            .bounds(x+w-40, Math.max(top+23,footerY-39),40,20).build()).active = scroll+perPage < all.size();
        int bwidth = (w - 10)/3;
        addRenderableWidget(Button.builder(tr("apply"), b -> apply())
            .bounds(x,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(tr("refresh"), b -> request())
            .bounds(x+bwidth+5,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(tr("close"), b -> onClose())
            .bounds(x+(bwidth+5)*2,footerY,w-(bwidth+5)*2,20).build());
    }

    @Override public void added() {
        super.added();
        request();
    }

    private String actionLabel(Option o) {
        return switch(o.key) {
            case "mode" -> local("selected.mode", selection("mode", mode));
            case "processing" -> local("selected.processing", selection("processing", processing));
            default -> label(o).getString();
        };
    }
    private void invoke(String key) {
        switch(key) {
            case "mode" -> {mode = "SEA_LEVEL".equals(mode)?"DROUGHT":"SEA_LEVEL";changes.add("mode");init();}
            case "processing" -> {processing = switch(processing){case "AHEAD"->"GLOBAL";case "GLOBAL"->"LOADED";default->"AHEAD";};changes.add("processing");init();}
            case "setlevel" -> setGlobalLevel();
            case "status" -> send("fallingsea status");
            case "refresh" -> request();
        }
    }
    private void setGlobalLevel() {
        String value = numbers.get("globallevel");
        try {
            int level = Integer.parseInt(value.trim());
            if (level < -64 || level > 319) throw new NumberFormatException();
            send("fallingsea setlevel "+level);
            changes.remove("globallevel");
            message = local("message.level", level);
        } catch (Exception e) {message=local("message.level_invalid");}
    }
    private void apply() {
        // Validate the inclusive Minecraft-day range before sending any commands.
        try {
            int minDays = Integer.parseInt(numbers.getOrDefault("randommindays", "1").trim());
            int maxDays = Integer.parseInt(numbers.getOrDefault("randommaxdays", "5").trim());
            if (minDays < 1 || maxDays > 365 || minDays > maxDays) {
                message = local("message.random_days_invalid");
                return;
            }
        } catch (NumberFormatException exception) {
            message = local("message.random_days_invalid");
            return;
        }
        List<String> commands = new ArrayList<>();
        if (changes.contains("mode")) commands.add("fallingsea mode "+mode.toLowerCase(Locale.ROOT));
        if (changes.contains("processing")) commands.add("fallingsea processing "+processing.toLowerCase(Locale.ROOT));
        for (var entry: JSON_NUMBERS.entrySet()) {
            String key = entry.getKey();
            if (!changes.contains(key) || key.equals("globallevel")) continue;
            if (key.equals("interval") && toggles.getOrDefault("randomschedule", false)) continue;
            String value = numbers.getOrDefault(key,"").trim();
            try {Integer.parseInt(value);}catch(NumberFormatException e){message=local("message.number_invalid", local("option." + key));return;}
            commands.add("fallingsea set "+key+" "+value);
        }
        for (var entry: JSON_TOGGLES.entrySet()) {
            if (changes.contains(entry.getKey())) commands.add("fallingsea set "+entry.getKey()+" "+toggles.get(entry.getKey()));
        }
        // Apply the mode selection before numeric settings so server-side exclusivity is honored.
        commands.sort((a,b) -> Boolean.compare(!a.startsWith("fallingsea set randomschedule "), !b.startsWith("fallingsea set randomschedule ")));
        for(String command:commands) send(command);
        changes.removeIf(k -> !k.equals("globallevel"));
        message = local(commands.isEmpty() ? "message.no_change" : "message.sent");
        request();
    }
    private void navigate(int diff) {page=Math.floorMod(page+diff,PAGES.size());scroll=0;init();}
    private void request() {
        if (Minecraft.getInstance().player == null) {
            message=local("message.need_world");
            return;
        }
        ClientPlayNetworking.send(new ConfigRequestPayload("read"));
        message=local("message.fetching");
    }
    public void receiveSnapshot(JsonObject data) {
        for(var e:JSON_NUMBERS.entrySet()) {
            if (!changes.contains(e.getKey()) && data.has(e.getValue())) numbers.put(e.getKey(),data.get(e.getValue()).getAsString());
        }
        for(var e:JSON_TOGGLES.entrySet()) {
            if (!changes.contains(e.getKey()) && data.has(e.getValue())) toggles.put(e.getKey(),data.get(e.getValue()).getAsBoolean());
        }
        if(!changes.contains("mode") && data.has("dryingMode"))mode=data.get("dryingMode").getAsString();
        if(!changes.contains("processing") && data.has("chunkProcessingMode"))processing=data.get("chunkProcessingMode").getAsString();
        synced=true; message=local("message.synced", numbers.getOrDefault("globallevel","?"));
        init();
    }
    private void send(String command) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand(command);
        else message=local("message.commands_world");
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
