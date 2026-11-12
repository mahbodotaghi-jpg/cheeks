package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.Anchor;
import dev.nitro.util.Gfx;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;

import java.util.ArrayDeque;

/**
 * Weather Notifier: shows a small toast when the weather changes (rain starts, a thunderstorm rolls in, the sky clears).
 * The changes are read from the server's weather packets (ClientPlayNetworkHandlerMixin), so it keeps working while
 * Clear Weather hides the rain on your screen. Nothing is sent to the server.
 */
public final class WeatherNotifier extends Module {
    public static WeatherNotifier INSTANCE;

    private final Setting.Bool rain = new Setting.Bool("Rain started", true);
    private final Setting.Bool thunder = new Setting.Bool("Thunderstorm", true);
    private final Setting.Bool clear = new Setting.Bool("Weather cleared", true);
    private final Setting.Num duration = new Setting.Num("Duration (s)", 3.5, 1.5, 8.0, 0.5);
    private final Setting.Choice<Anchor> anchor = new Setting.Choice<>("Position", Anchor.TOP_RIGHT, Anchor.class);
    private final Setting.Choice<Gfx.PanelStyle> style = new Setting.Choice<>("Style", Gfx.PanelStyle.GLASS, Gfx.PanelStyle.class);
    private final Setting.Color tint = new Setting.Color("Panel colour", 0x14121F);
    private final Setting.Num opacity = new Setting.Num("Opacity", 85, 20, 100, 5);
    private final Setting.Num scale = new Setting.Num("Scale", 1.0, 0.6, 2.0, 0.05);
    private final Setting.Num offX = new Setting.Num("X offset", 8, 0, 400, 1);
    private final Setting.Num offY = new Setting.Num("Y offset", 8, 0, 300, 1);

    private record Toast(String title, String sub, ItemStack icon, int accent) {}

    private final ArrayDeque<Toast> queue = new ArrayDeque<>();
    private Toast current;
    private long shownAt;

    // weather state as told by the server (tracked even while the module is off)
    private boolean raining, thundering;
    private ClientWorld seenWorld;
    private long quietUntil;

    public WeatherNotifier() {
        super("Weather Notifier", "Shows a toast when the weather changes (rain, thunderstorm, clear sky).", Category.HUD);
        add(rain, thunder, clear, duration, anchor, style, tint, opacity, scale, offX, offY);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        queue.clear();
        current = null;
    }

    @Override
    public void onWorldChanged() {
        queue.clear();
        current = null;
    }

    /** Called from ClientPlayNetworkHandlerMixin on the main thread for every game-state packet. */
    public static void onGameState(GameStateChangeS2CPacket p) {
        WeatherNotifier s = INSTANCE;
        if (s == null) return;

        // the server re-sends the current weather right after you join: remember it but stay quiet for a moment
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w != s.seenWorld) {
            s.seenWorld = w;
            s.raining = false;
            s.thundering = false;
            s.quietUntil = System.currentTimeMillis() + 4000;
        }

        var reason = p.getReason();
        if (reason == GameStateChangeS2CPacket.RAIN_STARTED) {
            if (!s.raining) {
                s.raining = true;
                s.push(s.rain.on(), new Toast("Rain started", "Rain or snow, depending on the biome", new ItemStack(Items.WATER_BUCKET), 0x38BDF8));
            }
        } else if (reason == GameStateChangeS2CPacket.RAIN_STOPPED) {
            if (s.raining) {
                s.raining = false;
                s.thundering = false;
                s.push(s.clear.on(), new Toast("Weather cleared", "The rain has stopped", new ItemStack(Items.SUNFLOWER), 0x34D399));
            }
        } else if (reason == GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED) {
            float v = p.getValue();
            if (v > 0.9f && !s.thundering) {
                s.thundering = true;
                s.push(s.thunder.on(), new Toast("Thunderstorm", "Thunder and lightning", new ItemStack(Items.LIGHTNING_ROD), 0xFACC15));
            } else if (v < 0.2f && s.thundering) {
                s.thundering = false;
                s.push(s.thunder.on(), new Toast("Thunder stopped", "The storm is passing", new ItemStack(Items.WATER_BUCKET), 0x7DD3FC));
            }
        }
    }

    private void push(boolean wanted, Toast t) {
        if (!enabled || !wanted || System.currentTimeMillis() < quietUntil) return;
        if (queue.size() >= 3) queue.poll();
        queue.add(t);
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.nanoTime();
        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            shownAt = now;
        }
        float life = (now - shownAt) / 1_000_000_000f;
        float dur = duration.f();
        if (life >= dur) {
            current = null;
            return;
        }

        // slide + fade in over 0.35 s, and the same on the way out
        float in = Math.min(1f, life / 0.35f), out = Math.min(1f, (dur - life) / 0.35f);
        float p = Math.min(in, out);
        float alpha = p;
        if (alpha < 0.06f) return; // vanilla text treats a near-zero alpha as opaque
        float e = 1f - (1f - p) * (1f - p) * (1f - p);

        TextRenderer tr = mc.textRenderer;
        Toast t = current;
        int textW = Math.max(tr.getWidth(t.title()), (int) (tr.getWidth(t.sub()) * 0.85f));
        int W = 8 + 16 + 8 + textW + 10, H = 30;
        float sc = scale.f();

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int x = anchor.value.x(sw, (int) (W * sc), offX.i());
        int y = anchor.value.y(sh, (int) (H * sc), offY.i());
        boolean right = x + W * sc / 2f > sw / 2f;
        float slide = (1f - e) * (W * sc + offX.i() + 8) * (right ? 1f : -1f);

        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x + slide, y, 0f);
        ms.scale(sc, sc, 1f);
        Gfx.card(ctx, 0, 0, W, H, 7, tint.rgb(), opacity.f() / 100f, t.accent(), style.value, alpha, now / 1_000_000_000f);
        if (alpha > 0.6f) ctx.drawItem(t.icon(), 8, 7);
        ctx.drawText(tr, t.title(), 8 + 16 + 8, 6, Gfx.mulA(0xFFFFFFFF, alpha), false);
        Gfx.text(ctx, tr, t.sub(), 8 + 16 + 8, 18, 0.85f, Gfx.mulA(0xFFB8B8C0, alpha), false);
        ms.pop();
    }
}
