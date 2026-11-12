package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block Entity ESP: outlines block entities (chests, barrels, shulkers, spawners, signs, beds, ...) through walls.
 * Instead of reading the world, it records them straight from the server's packets (chunk data, block entity updates and
 * block updates, see ClientPlayNetworkHandlerMixin), so it also learns about block entities the moment the server sends
 * them. Only chunks the server has sent you are known. Nothing is sent to the server.
 */
public final class BlockEntityEsp extends Module {
    private enum Cat {CHEST, TRAPPED, ENDER, BARREL, SHULKER, SPAWNER, CONTAINER, SIGN, BED, OTHER}

    public static BlockEntityEsp INSTANCE;

    private final Setting.Num range = new Setting.Num("Range (chunks)", 8, 1, 32, 1);
    private final Setting.Bool fChest = new Setting.Bool("Chests", true);
    private final Setting.Bool fTrapped = new Setting.Bool("Trapped chests", true);
    private final Setting.Bool fEnder = new Setting.Bool("Ender chests", true);
    private final Setting.Bool fBarrel = new Setting.Bool("Barrels", true);
    private final Setting.Bool fShulker = new Setting.Bool("Shulker boxes", true);
    private final Setting.Bool fSpawner = new Setting.Bool("Spawners", true);
    private final Setting.Bool fContainer = new Setting.Bool("Furnaces, hoppers & co.", false);
    private final Setting.Bool fSign = new Setting.Bool("Signs", false);
    private final Setting.Bool fBed = new Setting.Bool("Beds", false);
    private final Setting.Bool fOther = new Setting.Bool("Everything else", false);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 15, 0, 50, 1);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 90, 10, 100, 5);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);
    private final Setting.Color[] colors = {
            new Setting.Color("Chest colour", 0xE8A33D),
            new Setting.Color("Trapped chest colour", 0xFF4D4D),
            new Setting.Color("Ender chest colour", 0xB77CFF),
            new Setting.Color("Barrel colour", 0xC98B5A),
            new Setting.Color("Shulker colour", 0xFF7AD9),
            new Setting.Color("Spawner colour", 0x3DFFB0),
            new Setting.Color("Furnace/hopper colour", 0x6EA8FF),
            new Setting.Color("Sign colour", 0xF2E8A0),
            new Setting.Color("Bed colour", 0xFF6B8B),
            new Setting.Color("Other colour", 0xBFC7D5)
    };

    private record Hit(Box box, Cat cat) {}

    /** Everything the server told us about, grouped by chunk (key = packed chunk x/z). */
    private final Map<Long, Map<BlockPos, Cat>> chunks = new HashMap<>();
    private ClientWorld knownWorld;
    private List<Hit> hits = new ArrayList<>();
    private int tick;

    public BlockEntityEsp() {
        super("Block Entity ESP", "Highlights block entities (chests, spawners, signs, beds...) using the server's packets.", Category.RENDER);
        add(range, fChest, fTrapped, fEnder, fBarrel, fShulker, fSpawner, fContainer, fSign, fBed, fOther,
                fill, fillOpacity, outlineOpacity, lineWidth, tracers);
        add(colors);
        fillOpacity.showIf(fill::on);
        INSTANCE = this;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int cxOf(long k) {
        return (int) (k >> 32);
    }

    private static int czOf(long k) {
        return (int) k;
    }

    private static Cat classify(BlockEntityType<?> type) {
        Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(type);
        if (id == null) return Cat.OTHER;
        return switch (id.getPath()) {
            case "chest" -> Cat.CHEST;
            case "trapped_chest" -> Cat.TRAPPED;
            case "ender_chest" -> Cat.ENDER;
            case "barrel" -> Cat.BARREL;
            case "shulker_box" -> Cat.SHULKER;
            case "mob_spawner", "spawner", "trial_spawner" -> Cat.SPAWNER;
            case "hopper", "dispenser", "dropper", "furnace", "blast_furnace", "smoker", "brewing_stand",
                 "chiseled_bookshelf", "decorated_pot", "crafter", "jukebox", "lectern" -> Cat.CONTAINER;
            case "sign", "hanging_sign" -> Cat.SIGN;
            case "bed" -> Cat.BED;
            default -> Cat.OTHER;
        };
    }

    private boolean wanted(Cat c) {
        return switch (c) {
            case CHEST -> fChest.on();
            case TRAPPED -> fTrapped.on();
            case ENDER -> fEnder.on();
            case BARREL -> fBarrel.on();
            case SHULKER -> fShulker.on();
            case SPAWNER -> fSpawner.on();
            case CONTAINER -> fContainer.on();
            case SIGN -> fSign.on();
            case BED -> fBed.on();
            case OTHER -> fOther.on();
        };
    }

    // ---- packet hooks (main thread, called by ClientPlayNetworkHandlerMixin) ----

    private void syncWorld() {
        if (knownWorld != mc.world) {
            chunks.clear();
            knownWorld = mc.world;
        }
    }

    public static void onChunkData(ChunkDataS2CPacket p) {
        BlockEntityEsp s = INSTANCE;
        if (s == null || !s.enabled) return;
        s.syncWorld();
        int cx = p.getChunkX(), cz = p.getChunkZ();
        Map<BlockPos, Cat> m = new HashMap<>();
        p.getChunkData().getBlockEntities(cx, cz).accept((pos, type, nbt) -> m.put(pos.toImmutable(), classify(type)));
        if (m.isEmpty()) s.chunks.remove(key(cx, cz));
        else s.chunks.put(key(cx, cz), m); // a chunk packet is a full re-send, so it replaces what we had
    }

    public static void onBlockEntityUpdate(BlockEntityUpdateS2CPacket p) {
        BlockEntityEsp s = INSTANCE;
        if (s == null || !s.enabled) return;
        s.syncWorld();
        BlockPos pos = p.getPos();
        s.chunks.computeIfAbsent(key(pos.getX() >> 4, pos.getZ() >> 4), k -> new HashMap<>())
                .put(pos.toImmutable(), classify(p.getBlockEntityType()));
    }

    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        BlockEntityEsp s = INSTANCE;
        if (s == null || !s.enabled) return;
        s.syncWorld();
        long k = key(pos.getX() >> 4, pos.getZ() >> 4);
        if (!state.hasBlockEntity()) {
            Map<BlockPos, Cat> m = s.chunks.get(k);
            if (m != null) m.remove(pos);
            return;
        }
        // a block that carries a block entity was placed or changed: work out its type from the block itself
        if (state.getBlock() instanceof BlockEntityProvider provider) {
            BlockEntity be = provider.createBlockEntity(pos, state);
            if (be != null) s.chunks.computeIfAbsent(k, kk -> new HashMap<>()).put(pos.toImmutable(), classify(be.getType()));
        }
    }

    public static void onChunkDelta(ChunkDeltaUpdateS2CPacket p) {
        BlockEntityEsp s = INSTANCE;
        if (s == null || !s.enabled) return;
        p.visitUpdates(BlockEntityEsp::onBlockUpdate);
    }

    // ---- module ----

    @Override
    public void onEnable() {
        tick = 0;
        chunks.clear();
        knownWorld = mc.world;
        seedFromWorld();
    }

    @Override
    public void onDisable() {
        chunks.clear();
        hits = new ArrayList<>();
    }

    @Override
    public void onWorldChanged() {
        hits = new ArrayList<>(); // the packet map resets itself when it sees the new world (syncWorld)
    }

    /** Picks up chunks that were already loaded before the module was switched on. */
    private void seedFromWorld() {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        int r = mc.options.getViewDistance().getValue() + 1;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
                if (ch == null) continue;
                Map<BlockPos, Cat> m = new HashMap<>();
                for (Map.Entry<BlockPos, BlockEntity> e : ch.getBlockEntities().entrySet()) {
                    BlockEntity be = e.getValue();
                    if (be == null || be.isRemoved()) continue;
                    m.put(e.getKey().toImmutable(), classify(be.getType()));
                }
                if (!m.isEmpty()) chunks.put(key(cx, cz), m);
            }
        }
    }

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (w == null) return;
        syncWorld();
        tick++;
        if (tick % 20 == 0) {
            // forget chunks the client no longer has loaded
            chunks.entrySet().removeIf(e -> w.getChunkManager().getWorldChunk(cxOf(e.getKey()), czOf(e.getKey())) == null);
        }
        if (tick % 6 != 0) return;

        Vec3d c = mc.gameRenderer.getCamera().getPos(); // follows Freecam
        int ccx = (int) Math.floor(c.x) >> 4, ccz = (int) Math.floor(c.z) >> 4;
        int r = range.i();

        List<Hit> out = new ArrayList<>();
        for (Map.Entry<Long, Map<BlockPos, Cat>> ce : chunks.entrySet()) {
            int cx = cxOf(ce.getKey()), cz = czOf(ce.getKey());
            if (Math.abs(cx - ccx) > r || Math.abs(cz - ccz) > r) continue;
            for (Map.Entry<BlockPos, Cat> e : ce.getValue().entrySet()) {
                Cat cat = e.getValue();
                if (!wanted(cat)) continue;
                BlockPos p = e.getKey();
                VoxelShape sh = w.getBlockState(p).getOutlineShape(w, p);
                Box b = sh.isEmpty() ? new Box(p) : sh.getBoundingBox().offset(p);
                out.add(new Hit(b, cat));
            }
        }
        hits = out;
    }

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        List<Hit> list = hits;
        if (list.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Matrix4f m = ms.peek().getPositionMatrix();
        float oa = outlineOpacity.f() / 100f, fa = fillOpacity.f() / 100f;

        RenderUtil.begin();
        if (fill.on()) {
            BufferBuilder q = RenderUtil.quads();
            for (Hit h : list) {
                Box b = h.box();
                RenderUtil.boxFill(q, m, (float) (b.minX - cam.x), (float) (b.minY - cam.y), (float) (b.minZ - cam.z),
                        (float) (b.maxX - cam.x), (float) (b.maxY - cam.y), (float) (b.maxZ - cam.z),
                        ColorUtil.argb(colors[h.cat().ordinal()].rgb(), fa));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        float sx = (float) (look.x * 0.2), sy = (float) (look.y * 0.2), sz = (float) (look.z * 0.2);
        for (Hit h : list) {
            Box b = h.box();
            int col = ColorUtil.argb(colors[h.cat().ordinal()].rgb(), oa);
            float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
            float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
            RenderUtil.boxLines(l, m, x1, y1, z1, x2, y2, z2, col);
            if (tracers.on()) RenderUtil.line(l, m, sx, sy, sz, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f, col);
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }
}
