package id.alzhardcore.nebula;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class NebulaMod implements ModInitializer {
    private static final int WAIT_TICKS = 5 * 60 * 20;
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static NebulaPoint nebulaPoint;

    private enum Stage { DROP, WAIT, RISE, RETURN_DROP }

    private static final class NebulaPoint {
        final ServerWorld world;
        final Vec3d pos;
        NebulaPoint(ServerWorld world, Vec3d pos) { this.world = world; this.pos = pos; }
    }

    private static final class State {
        Stage stage;
        ServerWorld returnWorld;
        Vec3d returnPos;
        int waitTicks;
        double riseTarget;
        State(Stage stage, ServerWorld world, Vec3d pos) {
            this.stage = stage; this.returnWorld = world; this.returnPos = pos;
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("setnebula").requires(source -> source.hasPermissionLevel(2)).executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                Vec3d at = player.getPos();
                BlockPos check = BlockPos.ofFloored(at.x, at.y + 6, at.z);
                if (!player.getWorld().getBlockState(check).isAir() || !player.getWorld().getBlockState(check.up()).isAir()) {
                    ctx.getSource().sendError(Text.literal("[✦ NEBULA ✦] Ruang di atas lokasi harus kosong."));
                    return 0;
                }
                nebulaPoint = new NebulaPoint((ServerWorld) player.getWorld(), at);
                player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 1f, 1f);
                ctx.getSource().sendFeedback(() -> Text.literal("[✦ NEBULA ✦] Lokasi Nebula disimpan."), false);
                return 1;
            }));

            dispatcher.register(literal("gonebula").requires(source -> source.hasPermissionLevel(2)
                    .and(source.getEntity() instanceof ServerPlayerEntity))
                .then(argument("nama", StringArgumentType.word()).suggests((ctx, builder) -> {
                    for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) builder.suggest(p.getName().getString());
                    return builder.buildFuture();
                }).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "nama");
                    ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
                    if (target == null) { ctx.getSource().sendError(Text.literal("Pemain tidak ditemukan atau sedang offline.")); return 0; }
                    if (nebulaPoint == null) { ctx.getSource().sendError(Text.literal("Lokasi Nebula belum diatur. Gunakan /setnebula.")); return 0; }
                    if (STATES.containsKey(target.getUuid())) { ctx.getSource().sendError(Text.literal("Pemain sudah dalam proses Nebula.")); return 0; }
                    begin(target, Stage.DROP, target.getServerWorld(), target.getPos());
                    ctx.getSource().sendFeedback(() -> Text.literal("[✦ NEBULA ✦] Mengirim " + target.getName().getString() + " ke Nebula."), false);
                    return 1;
                })));

            dispatcher.register(literal("returnnebula").requires(source -> source.hasPermissionLevel(2))
                .then(argument("nama", StringArgumentType.word()).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "nama");
                    ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
                    if (target == null) { ctx.getSource().sendError(Text.literal("Pemain tidak ditemukan atau sedang offline.")); return 0; }
                    State state = STATES.get(target.getUuid());
                    if (state == null) { ctx.getSource().sendError(Text.literal("Pemain tidak sedang dalam proses Nebula.")); return 0; }
                    state.stage = Stage.RISE;
                    state.riseTarget = target.getY() + 4;
                    state.waitTicks = 0;
                    target.playSoundToPlayer(SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.MASTER, 1f, 1f);
                    target.sendMessage(Text.literal("✦ Nebula » Proses kembali dimulai."), false);
                    return 1;
                })));
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity player) || player.isSpectator() || nebulaPoint == null) return;
            BlockPos spawnBlock = player.getSpawnPointPosition();
            ServerWorld returnWorld = player.getServer().getWorld(player.getSpawnPointDimension());
            if (returnWorld == null) returnWorld = player.getServerWorld();
            Vec3d returnPos = spawnBlock == null ? returnWorld.getSpawnPos().toCenterPos() : spawnBlock.toCenterPos();
            STATES.put(player.getUuid(), new State(Stage.DROP, returnWorld, returnPos));
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            State state = STATES.get(newPlayer.getUuid());
            if (state == null || nebulaPoint == null) return;
            if (state.stage == Stage.DROP) {
                teleport(newPlayer, nebulaPoint.world, nebulaPoint.pos.add(0, 6, 0));
                newPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 2400, 0, false, true, true));
                newPlayer.playSoundToPlayer(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.MASTER, 1f, 0.8f);
                newPlayer.sendMessage(Text.literal("✦ NEBULA ✦ Menuju dunia antara."), false);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(NebulaMod::tick);
    }

    private static void begin(ServerPlayerEntity player, Stage stage, ServerWorld returnWorld, Vec3d returnPos) {
        State state = new State(stage, returnWorld, returnPos);
        STATES.put(player.getUuid(), state);
        teleport(player, nebulaPoint.world, nebulaPoint.pos.add(0, 6, 0));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 2400, 0, false, true, true));
        player.playSoundToPlayer(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.MASTER, 1f, 0.8f);
        player.sendMessage(Text.literal("✦ NEBULA ✦ Menuju dunia antara."), false);
    }

    private static void teleport(ServerPlayerEntity player, ServerWorld world, Vec3d pos) {
        player.teleport(world, pos.x, pos.y, pos.z, java.util.Set.of(), player.getYaw(), player.getPitch());
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            State state = STATES.get(player.getUuid());
            if (state == null) continue;
            ServerWorld world = player.getServerWorld();
            if (state.stage == Stage.DROP) {
                particles(world, player, false);
                if (player.isOnGround()) {
                    state.stage = Stage.WAIT;
                    state.waitTicks = WAIT_TICKS;
                    world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.MASTER, 1f, 1f);
                    player.sendMessage(Text.literal("✦ NEBULA ✦ Kamu harus menunggu 5 menit."), false);
                }
            } else if (state.stage == Stage.WAIT) {
                particles(world, player, false);
                if (--state.waitTicks <= 0) {
                    state.stage = Stage.RISE;
                    state.riseTarget = player.getY() + 4;
                    player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.MASTER, 1f, 1f);
                    player.sendMessage(Text.literal("✦ NEBULA ✦ Energi memanggilmu."), false);
                } else if (state.waitTicks % 20 == 0) {
                    int secs = state.waitTicks / 20;
                    player.sendMessage(Text.literal("Nebula » Kembali dalam " + (secs / 60) + ":" + String.format("%02d", secs % 60)), true);
                }
            } else if (state.stage == Stage.RISE) {
                particles(world, player, false);
                double nextY = Math.min(player.getY() + 0.18, state.riseTarget);
                teleport(player, world, new Vec3d(player.getX(), nextY, player.getZ()));
                if (nextY >= state.riseTarget) {
                    state.stage = Stage.RETURN_DROP;
                    teleport(player, state.returnWorld, state.returnPos.add(0, 6, 0));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 2400, 0, false, true, true));
                    player.playSoundToPlayer(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.MASTER, 1f, 0.8f);
                }
            } else if (state.stage == Stage.RETURN_DROP) {
                particles(world, player, false);
                if (player.isOnGround()) {
                    player.removeStatusEffect(StatusEffects.SLOW_FALLING);
                    // The vanilla totem particles are sent to all clients tracking this world position.
                    world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 100, 0.6, 0.9, 0.6, 0.1);
                    world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 60, 0.35, 0.8, 0.35, 0.02);
                    world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 35, 0.45, 0.7, 0.45, 0.04);
                    world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.MASTER, 1f, 1f);
                    player.sendMessage(Text.literal("✦ RETURN ✦ Kamu telah kembali."), false);
                    STATES.remove(player.getUuid());
                }
            }
        }
    }

    private static void particles(ServerWorld world, ServerPlayerEntity player, boolean ignored) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        world.spawnParticles(ParticleTypes.END_ROD, x + 0.32, y + 0.15, z, 8, 0, 0.75, 0, 0.01);
        world.spawnParticles(ParticleTypes.END_ROD, x - 0.32, y + 0.15, z, 8, 0, 0.75, 0, 0.01);
        world.spawnParticles(ParticleTypes.END_ROD, x, y + 0.15, z + 0.32, 8, 0, 0.75, 0, 0.01);
        world.spawnParticles(ParticleTypes.END_ROD, x, y + 0.15, z - 0.32, 8, 0, 0.75, 0, 0.01);
        world.spawnParticles(ParticleTypes.CLOUD, x, y + 1, z, 6, 0.25, 0.65, 0.25, 0.005);
    }
}
